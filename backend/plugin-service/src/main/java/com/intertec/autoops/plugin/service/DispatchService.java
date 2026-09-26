package com.intertec.autoops.plugin.service;

import com.intertec.autoops.plugin.config.PluginProperties;
import com.intertec.autoops.plugin.domain.NotificationRule;
import com.intertec.autoops.plugin.domain.PluginInstallation;
import com.intertec.autoops.plugin.domain.TargetType;
import com.intertec.autoops.plugin.repo.NotificationRuleRepository;
import com.intertec.autoops.plugin.repo.PluginInstallationRepository;
import com.intertec.autoops.plugin.spi.DeliveryResult;
import com.intertec.autoops.plugin.spi.NotificationMessage;
import com.intertec.autoops.plugin.spi.NotificationPlugin;
import com.intertec.autoops.plugin.spi.PluginContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns one lifecycle event into zero or more deliveries.
 *
 * <p>Matching is synchronous and cheap (one indexed query, then in-memory
 * wildcard checks); sending is handed to a bounded pool and the caller returns
 * immediately. core-service must never wait on Slack to finish a run.
 *
 * <p>Tenant isolation runs through every step: rules are queried by
 * {@code tenantId}, the installation behind a matched rule is re-loaded with
 * {@code findByIdAndTenantId}, and the resulting message carries the same
 * tenant. There is no point at which an id alone selects a row.
 */
@Service
public class DispatchService {

    private static final Logger log = LoggerFactory.getLogger(DispatchService.class);

    private final NotificationRuleRepository rules;
    private final PluginInstallationRepository installations;
    private final PluginRegistry registry;
    private final InstallationService installationService;
    private final DeliveryRecorder recorder;
    private final ThreadPoolTaskExecutor executor;
    private final PluginProperties properties;

    public DispatchService(NotificationRuleRepository rules,
                           PluginInstallationRepository installations,
                           PluginRegistry registry,
                           InstallationService installationService,
                           DeliveryRecorder recorder,
                           @Qualifier("deliveryTaskExecutor") ThreadPoolTaskExecutor executor,
                           PluginProperties properties) {
        this.rules = rules;
        this.installations = installations;
        this.registry = registry;
        this.installationService = installationService;
        this.recorder = recorder;
        this.executor = executor;
        this.properties = properties;
    }

    /**
     * Matches the event against the tenant's rules and queues a send for each
     * distinct channel. Returns how many were queued, which is what the
     * internal endpoint reports back so core-service can log it.
     */
    @Transactional(readOnly = true)
    public int dispatch(RunEvent event) {
        List<NotificationRule> matching = rules
                .findByTenantIdAndTargetTypeAndEnabledTrue(event.tenantId(), event.targetType())
                .stream()
                .filter(rule -> rule.matches(event.targetType(), event.targetId(),
                        event.projectId(), event.event(), event.severity()))
                .toList();
        if (matching.isEmpty()) {
            return 0;
        }

        NotificationMessage message = toMessage(event);
        // Two rules can point at the same channel — say a workspace-wide FAILED
        // rule and a per-job one. Without this the tenant gets the same alert
        // twice and starts ignoring both.
        Map<Long, NotificationRule> byInstallation = new HashMap<>();
        for (NotificationRule rule : matching) {
            byInstallation.putIfAbsent(rule.getInstallationId(), rule);
        }

        List<Runnable> sends = new ArrayList<>();
        for (Map.Entry<Long, NotificationRule> entry : byInstallation.entrySet()) {
            installations.findByIdAndTenantId(entry.getKey(), event.tenantId())
                    .filter(PluginInstallation::isDeliverable)
                    .ifPresent(installation -> {
                        // Decrypt on this thread, inside the transaction, so the
                        // worker never touches the database or the cipher.
                        PluginContext context = installationService.context(installation);
                        NotificationPlugin plugin = registry.require(installation.getPluginKey());
                        Long ruleId = entry.getValue().getId();
                        sends.add(() -> deliver(plugin, context, installation.getPluginKey(),
                                ruleId, message));
                    });
        }
        sends.forEach(executor::execute);
        return sends.size();
    }

    private void deliver(NotificationPlugin plugin, PluginContext context, String pluginKey,
                         Long ruleId, NotificationMessage message) {
        DeliveryResult result;
        try {
            result = plugin.send(context, message);
        } catch (Exception ex) {
            // The SPI forbids throwing; a plugin that does anyway must not kill
            // the worker or lose the record of what happened.
            log.warn("Plugin {} threw while delivering {} for tenant {}",
                    pluginKey, message.event(), context.tenantId(), ex);
            result = DeliveryResult.failure(ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
        try {
            recorder.record(context.tenantId(), context.installationId(), pluginKey, ruleId,
                    message, result);
        } catch (Exception ex) {
            log.error("Could not record delivery attempt for tenant {} channel {}",
                    context.tenantId(), context.installationId(), ex);
        }
        if (!result.ok()) {
            log.info("Delivery failed: tenant={} plugin={} event={} status={} detail={}",
                    context.tenantId(), pluginKey, message.event(),
                    result.statusCode(), result.detailForStorage());
        }
    }

    private NotificationMessage toMessage(RunEvent event) {
        return new NotificationMessage(
                event.tenantId(),
                event.targetType(),
                event.targetId(),
                event.targetName(),
                event.event(),
                event.runId(),
                event.projectId(),
                event.projectName(),
                event.triggeredBy(),
                event.detail(),
                event.occurredAt(),
                event.duration(),
                consoleUrl(event),
                event.severity());
    }

    /**
     * Deep link into the console — the screen that answers the question the
     * message just raised, which is a different screen per target type.
     *
     * <p>A notification whose link lands somewhere plausible but wrong is worse
     * than one with no link: the reader follows it, finds nothing, and concludes
     * the notification was spurious. An agent parked on an approval has to lead
     * to the agent, not to a list of job executions it does not appear in.
     */
    private String consoleUrl(RunEvent event) {
        String base = properties.getConsoleBaseUrl();
        if (base == null || base.isBlank()) {
            return null;
        }
        String trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;

        // Workspace-level, and deliberately not project-scoped: the alert plane
        // moved out from under projects precisely because a monitoring tool has
        // never heard of one.
        if (event.targetType() == TargetType.ALERT) {
            return trimmed + "/app/alerts";
        }
        if (event.projectId() == null) {
            return null;
        }
        if (event.targetType() == TargetType.AGENT) {
            return trimmed + "/app/projects/" + event.projectId() + "/agents";
        }
        return trimmed + "/app/projects/" + event.projectId() + "/executions";
    }
}
