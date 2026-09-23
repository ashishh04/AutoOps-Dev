package com.intertec.autoops.alerts.web;

import com.intertec.autoops.alerts.service.IncidentService;
import com.intertec.autoops.alerts.service.TenantScope;
import com.intertec.autoops.alerts.web.dto.IncidentDetailView;
import com.intertec.autoops.alerts.web.dto.IncidentSummaryView;
import com.intertec.autoops.alerts.web.dto.InvestigationStatusView;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Incidents — what the alert plane correlated, and what investigation made of
 * it.
 *
 * <p>Separate from {@code AlertController} because the two answer different
 * questions and change for different reasons: alerts are a feed, incidents are
 * a workflow with state someone owns.
 *
 * <p>Investigation is a POST even though it reads nothing: it spends money and
 * runs commands on a customer's estate. Behind the VIEWER rule for the same
 * reason — watching an incident is reading, launching an investigation is not.
 */
@RestController
public class IncidentController {

    private static final int MAX_PAGE = 200;

    private final IncidentService incidents;

    public IncidentController(IncidentService incidents) {
        this.incidents = incidents;
    }

    public record StatusRequest(String status, String comment) {
    }

    public record CommentRequest(String comment) {
    }

    public record AssignRequest(String user) {
    }

    public record InvestigateRequest(String model, String question) {
    }

    @GetMapping("/api/incidents")
    public List<IncidentSummaryView> list(@RequestParam(required = false) Long projectId,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(defaultValue = "100") int limit,
                                          @AuthenticationPrincipal Jwt jwt) {
        return incidents.list(TenantScope.of(jwt, projectId), status, page(limit));
    }

    @GetMapping("/api/incidents/{id}")
    public IncidentDetailView get(@PathVariable String id,
                                  @RequestParam(required = false) Long projectId,
                                  @AuthenticationPrincipal Jwt jwt) {
        return incidents.get(TenantScope.of(jwt, projectId), id);
    }

    /** Whether the console should offer investigation at all. */
    @GetMapping("/api/incidents/capabilities")
    public Map<String, Object> capabilities() {
        return Map.of("investigation", incidents.investigationEnabled(),
                "clusterEngine", incidents.clusterEngineEnabled());
    }

    @PostMapping("/api/incidents/{id}/status")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void status(@PathVariable String id,
                       @RequestParam(required = false) Long projectId,
                       @RequestBody StatusRequest request,
                       @AuthenticationPrincipal Jwt jwt) {
        incidents.setStatus(TenantScope.of(jwt, projectId), id,
                request == null ? null : request.status(),
                request == null ? null : request.comment());
    }

    @PostMapping("/api/incidents/{id}/comment")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void comment(@PathVariable String id,
                        @RequestParam(required = false) Long projectId,
                        @RequestBody CommentRequest request,
                        @AuthenticationPrincipal Jwt jwt) {
        incidents.comment(TenantScope.of(jwt, projectId), id,
                request == null ? null : request.comment());
    }

    @PostMapping("/api/incidents/{id}/assign")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void assign(@PathVariable String id,
                       @RequestParam(required = false) Long projectId,
                       @RequestBody AssignRequest request,
                       @AuthenticationPrincipal Jwt jwt) {
        incidents.assign(TenantScope.of(jwt, projectId), id,
                request == null ? null : request.user());
    }

    /**
     * Starts an investigation, on whichever engine can actually see this
     * estate. May come back {@code running} — the AWS analyst takes minutes,
     * so the console polls {@link #investigation} rather than this holding a
     * request open through a blocking gateway.
     */
    @PostMapping("/api/incidents/{id}/investigate")
    public InvestigationStatusView investigate(@PathVariable String id,
                                               @RequestParam(required = false) Long projectId,
                                               @RequestBody(required = false) InvestigateRequest request,
                                               @AuthenticationPrincipal Jwt jwt) {
        return incidents.investigate(TenantScope.of(jwt, projectId), id,
                request == null ? null : request.model(),
                request == null ? null : request.question(),
                jwt.getTokenValue(), projectId);
    }

    /** Where it has got to — or what it concluded, read back rather than re-run. */
    @GetMapping("/api/incidents/{id}/investigation")
    public InvestigationStatusView investigation(@PathVariable String id,
                                                 @RequestParam(required = false) Long projectId,
                                                 @AuthenticationPrincipal Jwt jwt) {
        return incidents.investigationStatus(TenantScope.of(jwt, projectId), id,
                jwt.getTokenValue());
    }

    private static int page(int requested) {
        return Math.max(1, Math.min(requested, MAX_PAGE));
    }
}
