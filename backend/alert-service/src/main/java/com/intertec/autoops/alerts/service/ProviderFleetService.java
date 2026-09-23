package com.intertec.autoops.alerts.service;

import com.intertec.autoops.alerts.client.IncidentEngineClient;
import com.intertec.autoops.alerts.client.KeepApiClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The fleet, seen from the provider's side.
 *
 * <p><b>This is not the tenant view with a wider filter, and the difference is
 * the whole reason it exists.</b> A customer asks "what is wrong with my
 * system"; a provider asks "which of my customers is on fire, and which one has
 * quietly stopped sending me anything at all". The second question cannot be
 * answered by looking at one tenant at a time, and the most important part of
 * its answer — the customer with ZERO alerts because nothing is connected — is
 * invisible in every per-tenant view by construction.
 *
 * <p>So these methods roll up ACROSS tenants and deliberately return counts per
 * tenant rather than alerts. A provider drowning in ten thousand alerts needs to
 * know which three customers they belong to.
 *
 * <p><b>On the silent tenant.</b> This service can only report tenants that
 * appear in the alert stream — a tenant with no alerts produces no rows, and is
 * therefore absent rather than zero. That absence is the finding, and resolving
 * it needs the tenant directory, which lives in auth-service. The console joins
 * the two: this returns who IS sending, the directory says who exists, and the
 * difference is the list worth looking at. Doing that join here would mean this
 * service calling auth-service for a presentation concern.
 */
@Service
public class ProviderFleetService {

    /**
     * How many alerts to pull for the rollup.
     *
     * <p>Higher than any per-tenant page because this is a fleet count and a
     * truncated one is a wrong one — a tenant whose alerts all fell past the
     * limit reads as quiet. The response says when it truncated so the number
     * is never quietly believed.
     */
    private static final int ROLLUP_LIMIT = 2000;

    private final KeepApiClient engine;
    private final IncidentEngineClient incidents;

    public ProviderFleetService(KeepApiClient engine, IncidentEngineClient incidents) {
        this.engine = engine;
        this.incidents = incidents;
    }

    /**
     * Alert volume per tenant: who is noisy, and how recently.
     *
     * @param scope must be a provider scope. A client scope would silently
     *              return a one-row "fleet" consisting of itself, which reads
     *              like a fleet view and is not one.
     */
    public Map<String, Object> alertRollup(TenantScope scope) {
        requireProvider(scope);

        List<Map<String, Object>> raw = engine.alerts();
        boolean truncated = raw.size() >= ROLLUP_LIMIT;
        List<Map<String, Object>> window =
                raw.size() > ROLLUP_LIMIT ? raw.subList(0, ROLLUP_LIMIT) : raw;

        Map<String, TenantTally> byTenant = new TreeMap<>();
        int unattributed = 0;

        for (Map<String, Object> alert : window) {
            String tenant = TenantScope.label(alert, TenantScope.TENANT_LABEL);
            if (tenant == null || tenant.isBlank()) {
                // An alert whose tenant cannot be determined. Counted, never
                // dropped: alerts arriving unlabelled mean a connected source is
                // not stamping them, which is a provider problem that a
                // per-tenant view can never show because it belongs to no
                // tenant.
                unattributed++;
                continue;
            }
            TenantTally tally = byTenant.computeIfAbsent(tenant, TenantTally::new);
            tally.count(alert);
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        byTenant.values().stream()
                .sorted(Comparator.comparingInt((TenantTally t) -> t.firing).reversed())
                .forEach(t -> rows.add(t.toMap()));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenants", rows);
        out.put("tenant_count", rows.size());
        out.put("alerts_examined", window.size());
        out.put("unattributed_alerts", unattributed);
        out.put("truncated", truncated);
        return out;
    }

    /**
     * Every open incident across every tenant, newest first.
     *
     * <p>Returned as incidents rather than counts because this is the one fleet
     * question where the individual row matters: an unassigned critical incident
     * on any customer is the thing a provider acts on, and a count of six tells
     * nobody which one.
     */
    public Map<String, Object> openIncidents(TenantScope scope, int limit) {
        requireProvider(scope);

        List<Map<String, Object>> rows = new ArrayList<>();
        int unassigned = 0;

        for (Map<String, Object> incident : incidents.incidents(limit)) {
            String status = str(incident.get("status"));
            if (status != null && ("resolved".equalsIgnoreCase(status)
                    || "closed".equalsIgnoreCase(status))) {
                continue;
            }
            String assignee = str(incident.get("assignee"));
            if (assignee == null || assignee.isBlank()) {
                unassigned++;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", str(incident.get("id")));
            row.put("name", str(incident.get("user_generated_name")) != null
                    ? str(incident.get("user_generated_name"))
                    : str(incident.get("ai_generated_name")));
            row.put("severity", str(incident.get("severity")));
            row.put("status", status);
            row.put("assignee", assignee);
            row.put("started_at", str(incident.get("start_time")));
            row.put("alert_count", incident.get("alerts_count"));
            rows.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("incidents", rows);
        out.put("open_count", rows.size());
        // The number a provider acts on first: open and nobody holding it.
        out.put("unassigned_count", unassigned);
        return out;
    }

    /**
     * Refuses a non-provider caller loudly.
     *
     * <p>Not defence in depth — the controller already requires the role — but
     * a guard against the shape of mistake that matters here. Every method above
     * reads across tenants by design, so a client scope reaching one would
     * return another customer's data. A thrown error is the only acceptable
     * outcome; silently narrowing to the caller's own tenant would produce a
     * plausible one-row "fleet" that nobody would question.
     */
    private static void requireProvider(TenantScope scope) {
        if (scope == null || !scope.isProvider()) {
            throw new IllegalStateException(
                    "the fleet view reads across tenants and is provider-only");
        }
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** One tenant's line in the rollup. */
    private static final class TenantTally {
        private final String tenantId;
        private int total;
        private int firing;
        private int critical;
        private String lastReceivedAt;

        private TenantTally(String tenantId) {
            this.tenantId = tenantId;
        }

        private void count(Map<String, Object> alert) {
            total++;
            String status = str(alert.get("status"));
            if (status != null && ("firing".equalsIgnoreCase(status)
                    || "alerting".equalsIgnoreCase(status))) {
                firing++;
            }
            String severity = str(alert.get("severity"));
            if (severity != null && ("critical".equalsIgnoreCase(severity)
                    || "high".equalsIgnoreCase(severity))) {
                critical++;
            }
            String received = str(alert.get("lastReceived"));
            // String comparison on ISO-8601, which sorts correctly and avoids
            // parsing a field whose format the engine owns and may change.
            if (received != null && (lastReceivedAt == null
                    || received.compareTo(lastReceivedAt) > 0)) {
                lastReceivedAt = received;
            }
        }

        private Map<String, Object> toMap() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("tenant_id", tenantId);
            row.put("alerts", total);
            row.put("firing", firing);
            row.put("critical", critical);
            row.put("last_received_at", lastReceivedAt);
            return row;
        }
    }
}
