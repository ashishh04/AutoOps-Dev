package com.intertec.autoops.plugin.service;

import com.intertec.autoops.plugin.domain.LifecycleEvent;
import com.intertec.autoops.plugin.domain.TargetType;

import java.time.Duration;
import java.time.Instant;

/**
 * One lifecycle moment as the service that owns it reports it, before any rule
 * has been consulted.
 *
 * <p>Named for the case it was built for and kept for the case it grew into: a
 * job run, a workflow run, an agent run, and — since an alert is a thing the
 * platform receives rather than runs — an alert arriving. The shape holds
 * because the fields that do not apply are simply null, and a record with three
 * nulls reads better than three near-identical records.
 *
 * <p>{@code tenantId} arrives in the body rather than a header. The internal
 * token authenticates the caller as core-service; it does not choose a
 * workspace, so this field is what every rule lookup is filtered by and it
 * must always be the tenant that owns the run.
 */
public record RunEvent(
        String tenantId,
        TargetType targetType,
        Long targetId,
        String targetName,
        LifecycleEvent event,
        Long runId,
        Long projectId,
        String projectName,
        String triggeredBy,
        String detail,
        Instant occurredAt,
        Duration duration,

        /**
         * How bad this is, when the reporting service knows better than the
         * event does. Only alerts do: a firing alert is always TRIGGERED, and
         * whether it deserves a 3am Slack message is the alert's severity, not
         * the event's. Null everywhere else, and resolved against the event's
         * own severity downstream.
         */
        LifecycleEvent.Severity severity) {

    public RunEvent {
        if (occurredAt == null) {
            occurredAt = Instant.now();
        }
    }
}
