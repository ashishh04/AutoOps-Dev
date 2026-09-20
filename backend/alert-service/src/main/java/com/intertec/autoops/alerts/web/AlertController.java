package com.intertec.autoops.alerts.web;

import com.intertec.autoops.alerts.service.AlertQueryService;
import com.intertec.autoops.alerts.service.TenantScope;
import com.intertec.autoops.alerts.web.dto.AlertView;
import com.intertec.autoops.alerts.web.dto.IncidentView;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The AutoOps face of the alert plane.
 *
 * <p>Read-only, deliberately. Acknowledging or resolving an alert is a write
 * back into the engine, and the platform key this service holds is provisioned
 * as a read-only role precisely so that a bug here cannot mutate a customer's
 * alert stream. Adding a write means widening that role on purpose, in
 * {@code docker-compose.keep.yml}, not discovering it works.
 *
 * <p>There is <b>no free-text query parameter and no filter expression</b>.
 * {@code status} and {@code severity} are compared against a value we already
 * hold; they are never forwarded to the engine. The engine's own API takes CEL,
 * and the moment a caller can influence that string the tenant boundary becomes
 * an escaping problem rather than a comparison.
 */
@RestController
public class AlertController {

    /**
     * A page the console can render. Independent of the engine fetch cap, which
     * bounds what is scanned rather than what is returned.
     */
    private static final int MAX_PAGE = 200;

    private final AlertQueryService alerts;

    public AlertController(AlertQueryService alerts) {
        this.alerts = alerts;
    }

    @GetMapping("/api/alerts")
    public List<AlertView> list(@RequestParam(required = false) Long projectId,
                                @RequestParam(required = false) String status,
                                @RequestParam(required = false) String severity,
                                @RequestParam(defaultValue = "100") int limit,
                                @AuthenticationPrincipal Jwt jwt) {
        return alerts.list(TenantScope.of(jwt, projectId), status, severity, page(limit));
    }

    /**
     * {@code projectId} is optional but the console always sends it, and it
     * WIDENS nothing: it is how a scope learns which monitoring sources it
     * owns, which is the only way an alert carrying no labels can be recognised
     * at all. Without it a source-provenance alert lists fine and then 404s
     * when opened.
     */
    @GetMapping("/api/alerts/{fingerprint}")
    public AlertView get(@PathVariable String fingerprint,
                         @RequestParam(required = false) Long projectId,
                         @AuthenticationPrincipal Jwt jwt) {
        return alerts.get(TenantScope.of(jwt, projectId), fingerprint);
    }

    @GetMapping("/api/incidents")
    public List<IncidentView> incidents(@RequestParam(defaultValue = "100") int limit,
                                        @AuthenticationPrincipal Jwt jwt) {
        return alerts.incidents(TenantScope.of(jwt, null), page(limit));
    }

    /** Clamped, not validated: a silly limit is a bad request to answer, not to reject. */
    private static int page(int requested) {
        return Math.max(1, Math.min(requested, MAX_PAGE));
    }
}
