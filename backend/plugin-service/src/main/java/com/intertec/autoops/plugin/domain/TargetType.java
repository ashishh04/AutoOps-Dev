package com.intertec.autoops.plugin.domain;

/**
 * What a notification rule watches.
 *
 * <p>{@link #JOB} and {@link #WORKFLOW} mirror core-service's
 * {@code RunTargetType} — they share one run engine, so they share one event
 * vocabulary. {@link #AGENT} is a third thing that runs, in a different
 * service, and {@link #ALERT} is the one thing here that does not run at all:
 * it is what the platform RECEIVES.
 *
 * <p>That last distinction is not cosmetic, and {@link #identifiesTargets}
 * exists because of it. An alert has no numeric id — it is identified by a
 * fingerprint the monitoring tool chose, which is not stable across a
 * deduplication window and is not something a customer could pick from a
 * dropdown. So an ALERT rule is always project-wide or workspace-wide, and a
 * request naming a {@code targetId} for one is a request that misunderstands
 * what it is asking for rather than one to quietly reinterpret.
 */
public enum TargetType {

    JOB,

    WORKFLOW,

    /**
     * An agent run. Queues, starts, succeeds and fails like any other run, and
     * does one thing no job does — parks on an approval and waits for a human.
     * See {@link LifecycleEvent#AWAITING_APPROVAL}.
     */
    AGENT,

    /**
     * An alert arriving from a customer's own monitoring. Not a run: there is
     * nothing to queue, nothing to cancel and no duration.
     */
    ALERT;

    /** Whether a rule of this type can name one specific target. */
    public boolean identifiesTargets() {
        return this != ALERT;
    }
}
