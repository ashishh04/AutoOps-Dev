package com.intertec.autoops.alerts.web;

import com.intertec.autoops.alerts.exception.AlertException;
import com.intertec.autoops.alerts.service.CorrelationQuery;
import com.intertec.autoops.alerts.service.CorrelationService;
import com.intertec.autoops.alerts.web.dto.CorrelationRuleView;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * The rules that decide which alerts become one incident.
 *
 * <p>A customer's own, deliberately. Nothing correlates without a rule, so
 * making this an operator task means every workspace that wants its alerts
 * grouped files a ticket and waits — and files another one when their estate
 * changes.
 *
 * <p>Writes, so the VIEWER rule {@code SecurityConfig} enforces on POST and
 * DELETE applies: changing what becomes an incident is not something a
 * read-only role does.
 *
 * <p><b>No expression is accepted from a caller.</b> The request carries values
 * — a severity, a service, what to group by — and the CEL is written by
 * {@link CorrelationQuery} with the caller's own tenant ANDed in. That is what
 * makes this surface safe to expose to a tenant at all: there is no field here
 * whose contents reach the engine as logic.
 */
@RestController
public class CorrelationController {

    /** Minutes, because that is the unit an operator thinks in. */
    private static final int DEFAULT_WINDOW_MINUTES = 10;

    private final CorrelationService correlation;

    public CorrelationController(CorrelationService correlation) {
        this.correlation = correlation;
    }

    /**
     * @param field  severity, service or source — validated server-side
     * @param values any of these matches
     */
    public record ConditionRequest(String field, List<String> values) {
    }

    public record RuleRequest(String label,
                              List<ConditionRequest> conditions,
                              List<String> groupBy,
                              Integer windowMinutes) {
    }

    /**
     * What a rule may be built from, so the console renders the form from the
     * server's vocabulary rather than a copy of it. A field added here appears
     * in the console without a frontend change; more importantly, a field
     * REMOVED here stops being offered, instead of being offered and rejected.
     */
    @GetMapping("/api/correlation-rules/vocabulary")
    public Map<String, Object> vocabulary() {
        return Map.of(
                "fields", List.of(
                        Map.of("value", "severity", "label", "Severity"),
                        Map.of("value", "service", "label", "Service"),
                        Map.of("value", "source", "label", "Source")),
                "severities", List.of("critical", "high", "warning", "info", "low"),
                "defaultWindowMinutes", DEFAULT_WINDOW_MINUTES);
    }

    /**
     * {@code projectId} is optional and NARROWS, exactly as it does on the
     * alert feed. Omitted, this lists every rule the workspace owns.
     */
    @GetMapping("/api/correlation-rules")
    public List<CorrelationRuleView> list(@RequestParam(required = false) Long projectId,
                                          @AuthenticationPrincipal Jwt jwt) {
        return correlation.list(tenant(jwt), scope(projectId));
    }

    @PostMapping("/api/correlation-rules")
    @ResponseStatus(HttpStatus.CREATED)
    public CorrelationRuleView create(@RequestParam Long projectId,
                                      @RequestBody RuleRequest request,
                                      @AuthenticationPrincipal Jwt jwt) {
        if (request == null || request.label() == null || request.label().isBlank()) {
            throw AlertException.badRequest("missing_label", "Give this rule a name.");
        }
        List<CorrelationQuery.Condition> conditions =
                (request.conditions() == null ? List.<ConditionRequest>of() : request.conditions())
                        .stream()
                        .map(c -> new CorrelationQuery.Condition(c.field(), c.values()))
                        .toList();
        int minutes = request.windowMinutes() == null
                ? DEFAULT_WINDOW_MINUTES : request.windowMinutes();
        return correlation.create(tenant(jwt), String.valueOf(projectId),
                new CorrelationService.Draft(request.label(), conditions,
                        request.groupBy(), minutes * 60));
    }

    @DeleteMapping("/api/correlation-rules/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id,
                       @RequestParam(required = false) Long projectId,
                       @AuthenticationPrincipal Jwt jwt) {
        correlation.delete(tenant(jwt), scope(projectId), id);
    }

    /** Null rather than {@code "null"} — see ProviderController for why. */
    private static String scope(Long projectId) {
        return projectId == null ? null : String.valueOf(projectId);
    }

    private static String tenant(Jwt jwt) {
        String tenantId = jwt.getClaimAsString("tenantId");
        if (tenantId == null || tenantId.isBlank()) {
            throw AlertException.badRequest("missing_tenant", "Token has no tenantId claim");
        }
        return tenantId;
    }
}
