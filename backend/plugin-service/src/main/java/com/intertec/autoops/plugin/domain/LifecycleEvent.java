package com.intertec.autoops.plugin.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * The moments a tenant can be notified about.
 *
 * <p>The first five mirror core-service's {@code RunStatus} transitions
 * exactly — including the single-L {@code CANCELED} spelling, which is what
 * that enum uses. Do not "fix" it here; the two must line up or the mapping
 * in {@code RunEventRequest} silently drops events.
 *
 * <p>{@link #MISSED} and {@link #STALLED} have no equivalent status because
 * they describe a run that did <em>not</em> happen or has not ended. They are
 * emitted by core-service's schedule watchdog rather than by the run engine.
 *
 * <h2>Not every event fits every target</h2>
 * An alert does not queue and a job does not park on an approval, so
 * {@link #appliesTo} declares which target types each event is meaningful for.
 * That is not decoration: without it the console offers "Stalled" for an alert,
 * a customer selects it, and the rule sits there forever never firing — which
 * looks exactly like a broken notification channel.
 *
 * <p>It is a display and validation rule, deliberately NOT a dispatch filter.
 * Dispatch compares what a rule stored against what actually happened; adding a
 * second opinion there would mean a rule that was valid when written could
 * silently stop matching after this enum changed.
 */
public enum LifecycleEvent {

    /** Accepted by the scheduler or an API call; no step has run yet. */
    QUEUED(Severity.INFO, Applies.RUNS),

    /** The run engine picked it up — status moved to RUNNING. */
    STARTED(Severity.INFO, Applies.RUNS),

    /** Every step finished cleanly. */
    SUCCEEDED(Severity.INFO, Applies.RUNS),

    /** A step failed, or the engine crashed mid-run. */
    FAILED(Severity.CRITICAL, Applies.RUNS),

    /** Stopped by a user or by shutdown. Core spells it with one L. */
    CANCELED(Severity.WARNING, Applies.RUNS),

    /**
     * A scheduled window elapsed and nothing ran — the "not running" case.
     * Distinct from FAILED: nothing was attempted, so there is no run to open.
     *
     * <p>Jobs and workflows only. Agents are not scheduled; they are started by
     * a person, an incident or another automation, so there is no window for
     * one to miss.
     */
    MISSED(Severity.CRITICAL, Applies.SCHEDULED),

    /** Still RUNNING well past its expected duration. */
    STALLED(Severity.WARNING, Applies.SCHEDULED),

    /** First success after one or more consecutive failures. */
    RECOVERED(Severity.INFO, Applies.RUNS),

    /**
     * An agent has stopped and is waiting for a human to approve an action.
     *
     * <p>The most valuable event in this enum, and the reason AGENT was worth
     * adding as a target type at all. Every other event here reports something
     * that already finished; this one reports work that is BLOCKED and will
     * stay blocked until somebody looks. Nobody watches a console for that.
     */
    AWAITING_APPROVAL(Severity.WARNING, Applies.AGENTS),

    /**
     * An alert arrived in a firing state.
     *
     * <p>Not modelled as FAILED. An alert firing is not a failure of anything
     * AutoOps ran, and a channel that says "Failed: DiskSpaceLow" invites the
     * reader to go looking for a broken automation.
     */
    TRIGGERED(Severity.CRITICAL, Applies.ALERTS),

    /** An alert arrived saying it had cleared. */
    RESOLVED(Severity.INFO, Applies.ALERTS),

    /** Someone took ownership of an alert in the tool that raised it. */
    ACKNOWLEDGED(Severity.INFO, Applies.ALERTS);

    /**
     * Grouped rather than listed per constant so the sets are named once and
     * cannot drift apart — "the events a run has" is a real category, and
     * spelling it out eleven times is how one of them ends up missing AGENT.
     */
    private static final class Applies {
        private static final Set<TargetType> RUNS =
                EnumSet.of(TargetType.JOB, TargetType.WORKFLOW, TargetType.AGENT);
        private static final Set<TargetType> SCHEDULED =
                EnumSet.of(TargetType.JOB, TargetType.WORKFLOW);
        private static final Set<TargetType> AGENTS = EnumSet.of(TargetType.AGENT);
        private static final Set<TargetType> ALERTS = EnumSet.of(TargetType.ALERT);
    }

    private final Severity severity;
    private final Set<TargetType> targets;

    LifecycleEvent(Severity severity, Set<TargetType> targets) {
        this.severity = severity;
        this.targets = targets;
    }

    public Severity severity() {
        return severity;
    }

    /** Whether this event can ever happen to that kind of target. */
    public boolean appliesTo(TargetType target) {
        return target != null && targets.contains(target);
    }

    /** Every event a rule for this target type may sensibly select. */
    public static Set<LifecycleEvent> forTarget(TargetType target) {
        EnumSet<LifecycleEvent> selectable = EnumSet.noneOf(LifecycleEvent.class);
        for (LifecycleEvent event : values()) {
            if (event.appliesTo(target)) {
                selectable.add(event);
            }
        }
        return selectable;
    }

    /** True for the events that end a run, whatever the outcome. */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELED || this == RECOVERED
                || this == RESOLVED;
    }

    /** Drives colour and icon in every channel that renders one. */
    public enum Severity {
        INFO,
        WARNING,
        CRITICAL;

        /** Whether this clears a rule's {@code minSeverity} floor. */
        public boolean atLeast(Severity floor) {
            return floor == null || compareTo(floor) >= 0;
        }
    }
}
