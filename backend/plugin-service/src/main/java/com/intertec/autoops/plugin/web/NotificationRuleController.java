package com.intertec.autoops.plugin.web;

import com.intertec.autoops.plugin.domain.NotificationRule;
import com.intertec.autoops.plugin.domain.PluginInstallation;
import com.intertec.autoops.plugin.exception.PluginException;
import com.intertec.autoops.plugin.service.InstallationService;
import com.intertec.autoops.plugin.service.NotificationRuleService;
import com.intertec.autoops.plugin.web.dto.RuleRequest;
import com.intertec.autoops.plugin.web.dto.RuleResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The rules that decide which lifecycle events reach which channel.
 *
 * <p>Jobs, workflows, agents and alerts are covered by the same endpoints —
 * they differ in what raises the event, not in what a customer wants said about
 * it. {@code targetType} is what distinguishes them.
 *
 * <p>They are NOT interchangeable, though: an alert never stalls and a job
 * never waits for approval. Which events belong to which target is published by
 * {@code /api/plugins/events} and enforced here, so a rule that could never
 * fire is refused rather than saved and left looking armed.
 */
@RestController
public class NotificationRuleController {

    private final NotificationRuleService ruleService;
    private final InstallationService installationService;

    public NotificationRuleController(NotificationRuleService ruleService,
                                      InstallationService installationService) {
        this.ruleService = ruleService;
        this.installationService = installationService;
    }

    @GetMapping("/api/notification-rules")
    public List<RuleResponse> list(@AuthenticationPrincipal Jwt jwt) {
        String tenantId = tenant(jwt);
        List<NotificationRule> rules = ruleService.list(tenantId);
        // One lookup per distinct channel, not per rule.
        Map<Long, PluginInstallation> channels = new HashMap<>();
        return rules.stream().map(rule -> {
            PluginInstallation installation = channels.computeIfAbsent(rule.getInstallationId(),
                    id -> installationService.get(tenantId, id));
            return RuleResponse.from(rule, installation.getDisplayName(),
                    installation.getPluginKey());
        }).toList();
    }

    @GetMapping("/api/notification-rules/{id}")
    public RuleResponse get(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        String tenantId = tenant(jwt);
        return toResponse(tenantId, ruleService.get(tenantId, id));
    }

    @PostMapping("/api/notification-rules")
    @ResponseStatus(HttpStatus.CREATED)
    public RuleResponse create(@Valid @RequestBody RuleRequest request,
                               @AuthenticationPrincipal Jwt jwt) {
        String tenantId = tenant(jwt);
        NotificationRule rule = ruleService.create(tenantId, jwt.getSubject(),
                request.installationId(), request.targetType(), request.targetId(),
                request.projectId(), request.events(), request.minSeverity());
        return toResponse(tenantId, rule);
    }

    @PutMapping("/api/notification-rules/{id}")
    public RuleResponse update(@PathVariable Long id, @Valid @RequestBody RuleRequest request,
                               @AuthenticationPrincipal Jwt jwt) {
        String tenantId = tenant(jwt);
        NotificationRule rule = ruleService.update(tenantId, id, request.installationId(),
                request.targetType(), request.targetId(), request.projectId(),
                request.events(), request.enabled(), request.minSeverity());
        return toResponse(tenantId, rule);
    }

    @DeleteMapping("/api/notification-rules/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        ruleService.delete(tenant(jwt), id);
    }

    /** The rules attached to one channel — "what would break if I removed this?". */
    @GetMapping("/api/plugins/installations/{installationId}/rules")
    public List<RuleResponse> forInstallation(@PathVariable Long installationId,
                                              @AuthenticationPrincipal Jwt jwt) {
        String tenantId = tenant(jwt);
        PluginInstallation installation = installationService.get(tenantId, installationId);
        return ruleService.forInstallation(tenantId, installationId).stream()
                .map(rule -> RuleResponse.from(rule, installation.getDisplayName(),
                        installation.getPluginKey()))
                .toList();
    }

    private RuleResponse toResponse(String tenantId, NotificationRule rule) {
        PluginInstallation installation =
                installationService.get(tenantId, rule.getInstallationId());
        return RuleResponse.from(rule, installation.getDisplayName(), installation.getPluginKey());
    }

    private String tenant(Jwt jwt) {
        String tenantId = jwt.getClaimAsString("tenantId");
        if (tenantId == null || tenantId.isBlank()) {
            throw PluginException.badRequest("missing_tenant", "Token has no tenantId claim");
        }
        return tenantId;
    }
}
