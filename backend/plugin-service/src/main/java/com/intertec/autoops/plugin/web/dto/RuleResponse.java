package com.intertec.autoops.plugin.web.dto;

import com.intertec.autoops.plugin.domain.LifecycleEvent;
import com.intertec.autoops.plugin.domain.NotificationRule;

import java.time.Instant;
import java.util.Set;

public record RuleResponse(
        Long id,
        Long installationId,
        String installationName,
        String pluginKey,
        String targetType,
        Long targetId,
        Long projectId,
        /** ALL_TARGETS | PROJECT | TARGET — what the rule actually covers. */
        String scope,
        Set<LifecycleEvent> events,
        /** Null when the rule takes every severity, which is most of them. */
        LifecycleEvent.Severity minSeverity,
        boolean enabled,
        String createdBy,
        Instant createdAt) {

    public static RuleResponse from(NotificationRule rule, String installationName,
                                    String pluginKey) {
        return new RuleResponse(
                rule.getId(),
                rule.getInstallationId(),
                installationName,
                pluginKey,
                rule.getTargetType().name(),
                rule.getTargetId(),
                rule.getProjectId(),
                scopeOf(rule),
                rule.eventSet(),
                rule.getMinSeverity(),
                rule.isEnabled(),
                rule.getCreatedBy(),
                rule.getCreatedAt());
    }

    private static String scopeOf(NotificationRule rule) {
        if (rule.getTargetId() != null) {
            return "TARGET";
        }
        return rule.getProjectId() != null ? "PROJECT" : "ALL_TARGETS";
    }
}
