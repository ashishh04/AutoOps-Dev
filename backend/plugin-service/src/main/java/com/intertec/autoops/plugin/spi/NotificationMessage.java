package com.intertec.autoops.plugin.spi;

import com.intertec.autoops.plugin.domain.LifecycleEvent;
import com.intertec.autoops.plugin.domain.TargetType;

import java.time.Duration;
import java.time.Instant;

/**
 * One thing that happened to one job, workflow, agent or alert, in the one
 * shape every plugin renders from. Plugins never see a run row or a database —
 * this record is the whole contract.
 *
 * <p>{@code tenantId} rides along so a plugin can log or tag by workspace, but
 * it is NOT what scopes delivery: the installation was already resolved for
 * this tenant before the message was built. See {@code DispatchService}.
 */
public record NotificationMessage(
        String tenantId,
        TargetType targetType,
        Long targetId,
        String targetName,
        LifecycleEvent event,
        /** Null for MISSED — nothing ran, so there is no run to link to. */
        Long runId,
        Long projectId,
        String projectName,
        /** Schedule, webhook, or the user who pressed Run. */
        String triggeredBy,
        /** Error text for FAILED, schedule expression for MISSED, else null. */
        String detail,
        Instant occurredAt,
        /** Elapsed time for terminal events; null while a run is still open. */
        Duration duration,
        /** Deep link into the console. Empty when no run exists to open. */
        String consoleUrl,
        /**
         * How bad this is, which for an alert is the ALERT's severity rather
         * than the event's. Every firing alert is a TRIGGERED event; whether it
         * is worth waking somebody for is the alert's own business, and
         * flattening that to one colour would make a disk-usage notice and a
         * region outage look identical in Slack.
         *
         * <p>Never null after construction — it falls back to the event's own
         * severity, which is what every run-shaped event relies on.
         */
        LifecycleEvent.Severity severity) {

    public NotificationMessage {
        if (severity == null) {
            severity = event.severity();
        }
    }

    /** "Job "Nightly backup" failed" — the one-line headline every channel uses. */
    public String title() {
        return "%s \"%s\" %s".formatted(targetLabel(), targetName, verb());
    }

    public String targetLabel() {
        return switch (targetType) {
            case JOB -> "Job";
            case WORKFLOW -> "Workflow";
            case AGENT -> "Agent";
            case ALERT -> "Alert";
        };
    }

    private String verb() {
        return switch (event) {
            case QUEUED -> "was queued";
            case STARTED -> "started";
            case SUCCEEDED -> "succeeded";
            case FAILED -> "failed";
            case CANCELED -> "was canceled";
            case MISSED -> "did not run";
            case STALLED -> "is still running";
            case RECOVERED -> "recovered";
            // Phrased as the blocked state it is, not as a past event. The
            // whole value of this notification is that somebody has to act.
            case AWAITING_APPROVAL -> "is waiting for approval";
            case TRIGGERED -> "is firing";
            case RESOLVED -> "resolved";
            case ACKNOWLEDGED -> "was acknowledged";
        };
    }

    /** Human duration for the channel body; empty when the run is still open. */
    public String durationText() {
        if (duration == null) {
            return "";
        }
        long seconds = duration.toSeconds();
        if (seconds < 60) {
            return seconds + "s";
        }
        if (seconds < 3600) {
            return "%dm %ds".formatted(seconds / 60, seconds % 60);
        }
        return "%dh %dm".formatted(seconds / 3600, (seconds % 3600) / 60);
    }

    public boolean hasConsoleUrl() {
        return consoleUrl != null && !consoleUrl.isBlank();
    }
}
