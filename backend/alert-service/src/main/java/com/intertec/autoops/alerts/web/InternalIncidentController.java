package com.intertec.autoops.alerts.web;

import com.intertec.autoops.alerts.service.IncidentService;
import com.intertec.autoops.alerts.service.TenantScope;
import com.intertec.autoops.alerts.web.dto.IncidentSummaryView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The open incidents, for a platform caller that holds no user token.
 *
 * <p><b>Why this exists at all.</b> The escalation agent decides where a page
 * should go, and it cannot do that without knowing what is open. Every other
 * consumer of this service is the console, which arrives through the gateway
 * with a user's token; an agent has no user. The incident engine sits behind
 * this service rather than beside it, so there is no way round.
 *
 * <p>Guarded by {@code X-Internal-Token}
 * ({@link com.intertec.autoops.alerts.security.InternalTokenFilter}), routed to
 * by nothing in the gateway.
 *
 * <p><b>The tenant is a parameter, and that is the thing to be careful about.</b>
 * A user token carries its own tenant and cannot lie about it; an internal
 * caller states one. So this endpoint is exactly as trustworthy as the token
 * holder, which is why the token refuses to work when unset rather than
 * defaulting to open. Every query below is scoped to the tenant given — there
 * is no path that returns across tenants, and none should ever be added here.
 *
 * <p>Read-only on purpose. Acknowledging, assigning and commenting stay on the
 * console's authenticated surface: an agent that could assign an incident could
 * silently move a page away from the person holding it, which is the failure
 * the escalation design exists to prevent.
 */
@RestController
public class InternalIncidentController {

    /**
     * Bounded, and low. The agent is choosing a route for what is open now, not
     * mining history — and a model handed three hundred incidents will find a
     * pattern in them because at that size something always resembles something.
     */
    private static final int MAX_LIMIT = 50;

    private final IncidentService incidents;

    public InternalIncidentController(IncidentService incidents) {
        this.incidents = incidents;
    }

    /**
     * @param status which incidents to return. Defaults to the open ones,
     *               because a routing decision about a resolved incident is not
     *               a decision anybody needs.
     */
    @GetMapping("/internal/incidents")
    public Map<String, Object> incidents(@RequestParam String tenantId,
                                         @RequestParam Long projectId,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(defaultValue = "25") int limit) {
        int bounded = Math.max(1, Math.min(limit, MAX_LIMIT));
        List<IncidentSummaryView> rows = incidents.list(
                TenantScope.internal(tenantId, projectId), status, bounded);

        // `truncated` rather than a silent cut. An agent that enumerates a scope
        // from a list it does not know was shortened will claim coverage of
        // everything it was not shown — the platform has this failure written up
        // in agent-runtime/TRUNCATION.md, and the fix there is a tool that says
        // when it truncated. This is that.
        return Map.of(
                "tenant_id", tenantId,
                "project_id", projectId,
                "status_filter", status == null ? "open" : status,
                "incident_count", rows.size(),
                "truncated", rows.size() >= bounded,
                "incidents", rows);
    }
}
