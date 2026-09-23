package com.intertec.autoops.alerts.web;

import com.intertec.autoops.alerts.exception.AlertException;
import com.intertec.autoops.alerts.service.ProviderFleetService;
import com.intertec.autoops.alerts.service.TenantScope;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The provider's view of every customer's alerting at once.
 *
 * <p>Separate from {@link AlertController} on purpose rather than as a
 * parameter on it. The two answer different questions — "what is wrong with my
 * system" against "which of my customers is on fire" — and a
 * {@code ?fleet=true} flag on the tenant endpoint would mean one path whose
 * blast radius depends on a boolean. A reader of that endpoint could no longer
 * tell, without tracing the flag, whether it crosses the tenant boundary.
 *
 * <p>Every method here is provider-only and refuses otherwise. That refusal is
 * doubled — the role check below and a second inside the service — because the
 * failure mode of getting it wrong is returning one customer's alerts to
 * another, and a silent narrowing to the caller's own tenant would look like a
 * working fleet view with one customer in it.
 */
@RestController
public class ProviderFleetController {

    private static final int MAX_INCIDENTS = 200;

    private final ProviderFleetService fleet;

    public ProviderFleetController(ProviderFleetService fleet) {
        this.fleet = fleet;
    }

    /**
     * Alert volume per tenant — who is noisy, and how recently.
     *
     * <p>Tenants with NO alerts are absent rather than zero: this service only
     * sees what arrived. The console joins against the tenant directory to turn
     * that absence into the list worth looking at.
     */
    @GetMapping("/api/provider/fleet/alerts")
    public Map<String, Object> alerts(@AuthenticationPrincipal Jwt jwt) {
        return fleet.alertRollup(provider(jwt));
    }

    /** Every open incident across every tenant, unassigned ones counted. */
    @GetMapping("/api/provider/fleet/incidents")
    public Map<String, Object> incidents(@RequestParam(defaultValue = "100") int limit,
                                         @AuthenticationPrincipal Jwt jwt) {
        return fleet.openIncidents(provider(jwt), Math.max(1, Math.min(limit, MAX_INCIDENTS)));
    }

    /**
     * Builds the scope and refuses anyone who is not a provider.
     *
     * <p>403 rather than an empty result. An empty fleet view is
     * indistinguishable from a quiet estate, and a customer who reached this by
     * accident should be told they cannot, not shown nothing.
     */
    private TenantScope provider(Jwt jwt) {
        TenantScope scope = TenantScope.of(jwt, null);
        if (!scope.isProvider()) {
            throw AlertException.forbidden(
                    "provider_only",
                    "The fleet view reads across every tenant and is available to the "
                            + "provider operator role only.");
        }
        return scope;
    }
}
