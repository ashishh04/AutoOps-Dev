package com.intertec.autoops.core.web.dto;

import com.intertec.autoops.core.domain.Run;

import java.time.Instant;

/**
 * {@code summary} omits the log and the output (lists stay light);
 * {@code detail} carries both. {@code name} is the target-name snapshot taken
 * at trigger time.
 *
 * <p><b>{@code log} and {@code output} are separate, and that is the point.</b>
 * {@code log} is what the run DID — the engine's trace, for an operator asking
 * where something stopped. {@code output} is what it PRODUCED — the report, the
 * document, the thing the person actually asked for. They shared one field
 * once, and a customer who wanted a meeting summary got node timings and an
 * echo of their own transcript above it.
 *
 * <p>{@code output} is null for the many runs that produce no document —
 * restarting a service has a log and nothing to show.
 */
public record RunResponse(
        Long id,
        Long projectId,
        String targetType,
        Long targetId,
        String name,
        String status,
        String trigger,
        String triggeredBy,
        int stepTotal,
        int stepCompleted,
        Long durationMs,
        Instant startedAt,
        Instant finishedAt,
        Instant createdAt,
        String log,
        String output,
        String error) {

    public static RunResponse summary(Run run) {
        return build(run, null, null);
    }

    public static RunResponse detail(Run run) {
        return build(run, run.getLog(), run.getOutput());
    }

    private static RunResponse build(Run run, String log, String output) {
        return new RunResponse(run.getId(), run.getProjectId(),
                run.getTargetType().name(), run.getTargetId(), run.getTargetName(),
                run.getStatus().name(), run.getTrigger().name(), run.getTriggeredBy(),
                run.getStepTotal(), run.getStepCompleted(), run.getDurationMs(),
                run.getStartedAt(), run.getFinishedAt(), run.getCreatedAt(),
                log, output, run.getError());
    }
}
