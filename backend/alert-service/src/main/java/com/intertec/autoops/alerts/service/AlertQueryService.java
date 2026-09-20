package com.intertec.autoops.alerts.service;

import com.intertec.autoops.alerts.client.KeepApiClient;
import com.intertec.autoops.alerts.exception.AlertException;
import com.intertec.autoops.alerts.web.dto.AlertView;
import com.intertec.autoops.alerts.web.dto.IncidentView;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** Fetch from the engine, scope to the caller, map to AutoOps types. In that order. */
@Service
public class AlertQueryService {

    private final KeepApiClient engine;
    private final ProviderCatalogService providers;

    public AlertQueryService(KeepApiClient engine, ProviderCatalogService providers) {
        this.engine = engine;
        this.providers = providers;
    }

    /**
     * Widens a scope to the monitoring sources it owns.
     *
     * <p>Kept out of {@code TenantScope.of} deliberately: that is a pure
     * function of the JWT and stays unit-testable without a running engine.
     * This is the call that costs a round trip, so it happens once per request,
     * here, where the cost is visible.
     */
    private TenantScope enrich(TenantScope scope) {
        // A connected source is scoped to ONE project, so without a project in
        // hand there is nothing to match against. Enriching with an empty
        // project would build a prefix no real provider carries — dead weight,
        // and misleading to read.
        if (scope.isProvider() || scope.tenantId() == null || scope.projectId() == null) {
            return scope;
        }
        try {
            return scope.owning(providers.connectedIds(
                    scope.tenantId(), String.valueOf(scope.projectId())));
        } catch (RuntimeException ex) {
            // Never widen on failure. If the source list cannot be read, the
            // label rule alone still applies — showing too little beats showing
            // another tenant's alerts because a lookup errored.
            return scope;
        }
    }

    /**
     * @param status   optional, matched case-insensitively (firing, resolved,
     *                 acknowledged, suppressed)
     * @param severity optional, matched case-insensitively
     */
    public List<AlertView> list(TenantScope rawScope, String status, String severity, int limit) {
        TenantScope scope = enrich(rawScope);
        return engine.alerts().stream()
                // Scope FIRST. Every later step is presentation; this one is the
                // boundary, and putting a filter before it would eventually let
                // someone reorder the stream and lose it.
                .filter(scope::admits)
                .filter(a -> matches(status, a.get("status")))
                .filter(a -> matches(severity, a.get("severity")))
                .limit(limit)
                .map(AlertMapper::alert)
                .toList();
    }

    /**
     * <p>404 rather than 403 when the caller may not see it. A 403 would confirm
     * that the fingerprint exists, which is the same probe
     * {@code /api/hooks/{token}} refuses to answer.
     */
    public AlertView get(TenantScope rawScope, String fingerprint) {
        TenantScope scope = enrich(rawScope);
        Map<String, Object> raw = engine.alert(fingerprint);
        if (raw == null || !scope.admits(raw)) {
            throw AlertException.notFound("alert_not_found", "No such alert");
        }
        return AlertMapper.alert(raw);
    }

    /**
     * Incidents are PROVIDER-only, and that is a boundary rather than a
     * convenience.
     *
     * <p>An incident carries no labels — it is a correlation over alerts, and
     * the engine does not copy their labels onto it. There is therefore nothing
     * on an incident to check a tenant against, and the honest options are to
     * resolve every incident's alerts on every list call, or to not show them to
     * tenants yet. Guessing from {@code services} would be a boundary made of a
     * naming convention.
     */
    public List<IncidentView> incidents(TenantScope scope, int limit) {
        if (!scope.isProvider()) {
            throw AlertException.forbidden("provider_only",
                    "Incident correlation is operated by your provider.");
        }
        return engine.incidents().stream()
                .limit(limit)
                .map(AlertMapper::incident)
                .toList();
    }

    private static boolean matches(String wanted, Object actual) {
        if (wanted == null || wanted.isBlank()) {
            return true;
        }
        return actual != null && wanted.equalsIgnoreCase(String.valueOf(actual));
    }
}
