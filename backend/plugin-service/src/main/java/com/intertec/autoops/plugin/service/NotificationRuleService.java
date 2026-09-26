package com.intertec.autoops.plugin.service;

import com.intertec.autoops.plugin.domain.LifecycleEvent;
import com.intertec.autoops.plugin.domain.NotificationRule;
import com.intertec.autoops.plugin.domain.PluginInstallation;
import com.intertec.autoops.plugin.domain.TargetType;
import com.intertec.autoops.plugin.exception.PluginException;
import com.intertec.autoops.plugin.repo.NotificationRuleRepository;
import com.intertec.autoops.plugin.repo.PluginInstallationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * CRUD for the rules that bind lifecycle events to channels.
 *
 * <p>The one rule that matters for isolation: an installation is only ever
 * resolved with {@code findByIdAndTenantId}. Without that pairing, a caller
 * could bind their own rule to another workspace's Slack webhook and read that
 * workspace's job names out of it.
 */
@Service
public class NotificationRuleService {

    /** Same reasoning as the installation cap — a bound, not a product limit. */
    private static final int MAX_RULES_PER_TENANT = 200;

    private final NotificationRuleRepository rules;
    private final PluginInstallationRepository installations;

    public NotificationRuleService(NotificationRuleRepository rules,
                                   PluginInstallationRepository installations) {
        this.rules = rules;
        this.installations = installations;
    }

    @Transactional(readOnly = true)
    public List<NotificationRule> list(String tenantId) {
        return rules.findByTenantIdOrderByIdDesc(tenantId);
    }

    @Transactional(readOnly = true)
    public NotificationRule get(String tenantId, Long id) {
        return rules.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> PluginException.notFound("rule_not_found", "No such rule"));
    }

    @Transactional
    public NotificationRule create(String tenantId, String createdBy, Long installationId,
                                   TargetType targetType, Long targetId, Long projectId,
                                   Set<LifecycleEvent> events,
                                   LifecycleEvent.Severity minSeverity) {
        requireInstallation(tenantId, installationId);
        TargetType type = requireTargetType(targetType);
        Set<LifecycleEvent> selected = requireEvents(events, type);
        Long scopedTarget = requireTargetIdFits(type, targetId);
        if (rules.countByTenantId(tenantId) >= MAX_RULES_PER_TENANT) {
            throw PluginException.badRequest("too_many_rules",
                    "A workspace can have at most " + MAX_RULES_PER_TENANT
                            + " notification rules");
        }

        NotificationRule rule = new NotificationRule();
        rule.setTenantId(tenantId);
        rule.setInstallationId(installationId);
        rule.setTargetType(type);
        rule.setTargetId(scopedTarget);
        // A specific target already implies its project; keeping both would let
        // them contradict each other and silently match nothing.
        rule.setProjectId(scopedTarget != null ? null : projectId);
        rule.setEventSet(selected);
        rule.setMinSeverity(minSeverity);
        rule.setCreatedBy(createdBy);
        return rules.save(rule);
    }

    @Transactional
    public NotificationRule update(String tenantId, Long id, Long installationId,
                                   TargetType targetType, Long targetId, Long projectId,
                                   Set<LifecycleEvent> events, Boolean enabled,
                                   LifecycleEvent.Severity minSeverity) {
        NotificationRule rule = get(tenantId, id);
        if (installationId != null) {
            requireInstallation(tenantId, installationId);
            rule.setInstallationId(installationId);
        }
        if (targetType != null) {
            rule.setTargetType(targetType);
        }
        // Validated against the type the rule will HAVE, not the one it had. An
        // update that switches a job rule to ALERT and keeps "Stalled" selected
        // would otherwise save a rule that can never fire.
        if (events != null) {
            rule.setEventSet(requireEvents(events, rule.getTargetType()));
        } else {
            rule.setEventSet(requireEvents(rule.eventSet(), rule.getTargetType()));
        }
        if (enabled != null) {
            rule.setEnabled(enabled);
        }
        rule.setMinSeverity(minSeverity);
        // Scope is replaced as a unit — a partial update could leave both ids
        // set, which the matcher would read as target-only and quietly widen.
        Long scopedTarget = requireTargetIdFits(rule.getTargetType(), targetId);
        rule.setTargetId(scopedTarget);
        rule.setProjectId(scopedTarget != null ? null : projectId);
        return rules.save(rule);
    }

    @Transactional
    public void delete(String tenantId, Long id) {
        rules.delete(get(tenantId, id));
    }

    @Transactional(readOnly = true)
    public List<NotificationRule> forInstallation(String tenantId, Long installationId) {
        requireInstallation(tenantId, installationId);
        return rules.findByTenantIdAndInstallationId(tenantId, installationId);
    }

    private PluginInstallation requireInstallation(String tenantId, Long installationId) {
        if (installationId == null) {
            throw PluginException.badRequest("missing_installation",
                    "A rule must name the integration it delivers through");
        }
        return installations.findByIdAndTenantId(installationId, tenantId)
                .orElseThrow(() -> PluginException.notFound("installation_not_found",
                        "No such integration"));
    }

    /**
     * Rejected rather than silently dropped.
     *
     * <p>An event that cannot happen to this kind of target produces a rule
     * that sits in the list looking armed and never fires — which a customer
     * reads as a broken notification channel, not as a rule they mis-wrote.
     * Quietly filtering the impossible ones out is no better: they would then
     * save a rule and find it listing fewer events than they selected.
     */
    private Set<LifecycleEvent> requireEvents(Set<LifecycleEvent> events, TargetType type) {
        if (events == null || events.isEmpty()) {
            throw PluginException.badRequest("no_events",
                    "Select at least one event to be notified about");
        }
        for (LifecycleEvent event : events) {
            if (!event.appliesTo(type)) {
                throw PluginException.badRequest("event_not_applicable",
                        event.name() + " never happens to " + type.name().toLowerCase()
                                + "s, so a rule watching for it would never fire");
            }
        }
        return EnumSet.copyOf(events);
    }

    private TargetType requireTargetType(TargetType targetType) {
        if (targetType == null) {
            throw PluginException.badRequest("missing_target_type",
                    "A rule must say what it watches: jobs, workflows, agents or alerts");
        }
        return targetType;
    }

    /**
     * An ALERT rule cannot name one alert, because an alert has no id a
     * customer could pick — see {@link TargetType#identifiesTargets}. Refused
     * rather than nulled out: nulling would widen the rule from "this one
     * thing" to "everything in the workspace", which is the largest possible
     * silent change to make to a notification rule.
     */
    private Long requireTargetIdFits(TargetType type, Long targetId) {
        if (targetId != null && !type.identifiesTargets()) {
            throw PluginException.badRequest("target_not_identifiable",
                    "An alert rule covers a project or the whole workspace. "
                            + "Individual alerts have no id to watch.");
        }
        return targetId;
    }
}
