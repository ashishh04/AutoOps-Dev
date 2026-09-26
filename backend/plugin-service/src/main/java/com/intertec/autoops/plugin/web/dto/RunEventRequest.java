package com.intertec.autoops.plugin.web.dto;

import com.intertec.autoops.plugin.domain.LifecycleEvent;
import com.intertec.autoops.plugin.domain.TargetType;
import com.intertec.autoops.plugin.service.RunEvent;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Duration;
import java.time.Instant;

/**
 * The body a service posts to {@code /internal/events}.
 *
 * <p>core-service for jobs and workflows, agent-service for agent runs,
 * alert-service for alerts arriving. All three are behind the same internal
 * token and all three state their own tenant.
 *
 * <p>{@code tenantId} is required and is the only thing that scopes the
 * fan-out. The internal token proves the caller is core-service; it says
 * nothing about which workspace the run belonged to, so this field has to be
 * right and has to be present.
 */
public record RunEventRequest(
        @NotBlank(message = "is required")
        String tenantId,

        @NotNull(message = "is required")
        TargetType targetType,

        /**
         * Null only for an ALERT, which has no numeric id — it is identified by
         * a fingerprint its monitoring tool chose, which is not stable across a
         * deduplication window. Required for everything that runs, because a
         * rule scoped to one job has nothing to compare against without it.
         */
        Long targetId,

        @NotBlank(message = "is required")
        String targetName,

        @NotNull(message = "is required")
        LifecycleEvent event,

        /** Null for MISSED — nothing ran, so there is no run row. */
        Long runId,

        Long projectId,
        String projectName,
        String triggeredBy,

        /** Error text for FAILED, the schedule for MISSED. */
        String detail,

        /** Defaults to now when the reporting service does not stamp it. */
        Instant occurredAt,

        Long durationSeconds,

        /** The alert's own severity. Null for everything that runs. */
        LifecycleEvent.Severity severity) {

    /**
     * {@code targetId} stopped being unconditionally required when ALERT was
     * added, and "optional" is not what it became — it is required for
     * everything that runs. Dropping the constraint entirely would let a
     * malformed job event through, where it would match only workspace-wide
     * rules and quietly bypass every rule scoped to that job.
     */
    @AssertTrue(message = "is required for anything other than an alert")
    public boolean isTargetIdentified() {
        return targetType == null || !targetType.identifiesTargets() || targetId != null;
    }

    public RunEvent toEvent() {
        return new RunEvent(
                tenantId,
                targetType,
                targetId,
                targetName,
                event,
                runId,
                projectId,
                projectName,
                triggeredBy,
                detail,
                occurredAt,
                durationSeconds == null ? null : Duration.ofSeconds(durationSeconds),
                severity);
    }
}
