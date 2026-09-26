package com.intertec.autoops.alerts.client;

import com.intertec.autoops.alerts.config.AlertProperties;
import com.intertec.autoops.alerts.service.IngestToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Reports arriving alerts to plugin-service, so a tenant's Slack or Teams can
 * hear about them under the same rules as their jobs and workflows.
 *
 * <h2>What this covers, and what it honestly does not</h2>
 * The public ingest door — the one a customer's monitoring tool posts to, using
 * the signed token AutoOps issued it. That is the only point in this service
 * where an alert is known to have ARRIVED, and it is where the tenant and
 * project are established from a value the platform signed.
 *
 * <p>It does <b>not</b> cover alerts the engine pulls for itself on a schedule,
 * or ones a source pushes straight to the engine without coming through here.
 * Those never pass a line of AutoOps code at the moment they arrive, so there
 * is nothing to hook. Claiming otherwise would be worse than the gap: a
 * customer who is told every alert is notified, and finds their pulled Datadog
 * alerts silently are not, stops trusting the ones that do arrive.
 *
 * <h2>Failure policy: never throw, never retry</h2>
 * Same as core-service's and agent-service's. Ingest must answer the monitoring
 * tool quickly and must not fail because a notification could not be queued —
 * a source that gets a 500 back will retry, and duplicate alerts are a worse
 * outcome than a missed Slack message.
 */
@Component
public class PluginClient {

    private static final Logger log = LoggerFactory.getLogger(PluginClient.class);

    private final RestClient pluginRestClient;
    private final String internalToken;
    private final boolean enabled;

    public PluginClient(@Qualifier("pluginRestClient") RestClient pluginRestClient,
                        AlertProperties properties) {
        this.pluginRestClient = pluginRestClient;
        this.internalToken = properties.getPlugin().getInternalToken();
        this.enabled = properties.getPlugin().isEnabled();
    }

    /**
     * Reports one alert arriving.
     *
     * @param scope the tenant and project this source was registered to, taken
     *              from the SIGNED ingest token and never from the payload.
     *              The alert body is a third party's and cannot be allowed to
     *              choose whose channels it reaches.
     * @param alert the alert as posted, read for its name, status and severity
     *              and for nothing else
     */
    public void publish(IngestToken.Scope scope, Map<String, Object> alert) {
        if (!enabled || scope == null) {
            return;
        }
        String event = eventFor(alert);
        if (event == null) {
            // A status this platform has no event for. Silence is right: there
            // is no honest thing to say about "suppressed", and inventing
            // TRIGGERED for it would page someone about an alert that was
            // deliberately muted.
            return;
        }

        Map<String, Object> body = new HashMap<>();
        body.put("tenantId", scope.tenantId());
        body.put("targetType", "ALERT");
        // Null, and that is not an omission. An alert has no numeric id — it is
        // identified by a fingerprint its monitoring tool chose, which is not
        // stable across a deduplication window — so an ALERT rule is always
        // project-wide or workspace-wide. See plugin-service's TargetType.
        body.put("targetId", null);
        body.put("targetName", nameOf(alert));
        body.put("event", event);
        body.put("runId", null);
        body.put("projectId", projectOf(scope));
        body.put("projectName", null);
        body.put("triggeredBy", scope.providerType());
        body.put("detail", text(alert.get("description")));
        body.put("occurredAt", Instant.now().toString());
        body.put("durationSeconds", null);
        // The ALERT's severity, not the event's. Every firing alert is a
        // TRIGGERED event; whether it is worth waking somebody for is this.
        body.put("severity", severityOf(alert));

        try {
            pluginRestClient.post()
                    .uri("/internal/events")
                    .header("X-Internal-Token", internalToken)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception ex) {
            log.debug("Could not report alert {} for tenant {}: {}",
                    event, scope.tenantId(), ex.getMessage());
        }
    }

    /**
     * Which lifecycle event an arriving alert represents.
     *
     * <p>Only the three that have a meaning here. An unrecognised status
     * returns null and is reported as nothing, rather than being rounded to
     * TRIGGERED — a monitoring tool AutoOps has not met yet must not be able to
     * page a customer by sending a word nobody anticipated.
     */
    private static String eventFor(Map<String, Object> alert) {
        String status = text(alert.get("status"));
        if (status == null) {
            // Keep's own default for an alert that omits it. Firing is the only
            // reading that makes sense of a tool bothering to send one.
            return "TRIGGERED";
        }
        return switch (status.toLowerCase(Locale.ROOT)) {
            case "firing" -> "TRIGGERED";
            case "resolved" -> "RESOLVED";
            case "acknowledged" -> "ACKNOWLEDGED";
            default -> null;
        };
    }

    /**
     * The alert's severity, flattened onto the three the notification plane
     * renders.
     *
     * <p>Unknown maps to CRITICAL, and that asymmetry is deliberate. Every
     * other unknown in this class is treated as "say nothing"; this one is
     * reached only once the alert has ALREADY qualified as something to report,
     * so the choice is between over- and under-stating it. An alert quietly
     * demoted below a customer's "critical only" floor is one they never see.
     */
    private static String severityOf(Map<String, Object> alert) {
        String severity = text(alert.get("severity"));
        if (severity == null) {
            return "CRITICAL";
        }
        return switch (severity.toLowerCase(Locale.ROOT)) {
            case "info", "low" -> "INFO";
            case "warning", "medium" -> "WARNING";
            default -> "CRITICAL";
        };
    }

    private static String nameOf(Map<String, Object> alert) {
        String name = text(alert.get("name"));
        return name == null ? "Alert" : name;
    }

    private static Long projectOf(IngestToken.Scope scope) {
        try {
            return Long.valueOf(scope.projectId());
        } catch (RuntimeException ex) {
            // A project-scoped rule simply will not match. Better than sending
            // a malformed id the far side would reject outright.
            return null;
        }
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }
}
