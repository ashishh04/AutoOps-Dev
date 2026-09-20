package com.intertec.autoops.agent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Fails runs that never said how they ended.
 *
 * <p><b>Written now because a sweep with no caller is not a sweep.</b>
 * {@link RunScopeService#sweepAbandoned} has existed since V7 and nothing
 * invoked it, which meant a run whose agent died between declaring and
 * completing would sit in RUNNING permanently. That is safe for the reaper —
 * RUNNING and FAILED both ground nothing — and it is not safe for the gauges:
 * zombie runs are indistinguishable from work in flight, so the coverage gap
 * for an agent that has quietly stopped reports as healthy activity.
 *
 * <p>Hourly rather than continuously, against a 24-hour threshold. Nothing here
 * is urgent by construction: the sweep changes no reaping decision, it only
 * relabels. Running it often would be cost without benefit, and running it on a
 * tighter threshold would fail runs legitimately parked on a human approval —
 * the one case where a run is meant to sit untouched for a day.
 *
 * <p><b>Every instance runs this.</b> There is no leader election here, and the
 * statement is a guarded {@code UPDATE ... WHERE scope_status = 'RUNNING'} — two
 * instances sweeping at once both write the same value to the same rows, and
 * the second one updates nothing. A lock would be machinery protecting an
 * idempotent write.
 */
@Component
public class RunScopeSweeper {

    private static final Logger log = LoggerFactory.getLogger(RunScopeSweeper.class);

    private final RunScopeService scopes;

    public RunScopeSweeper(RunScopeService scopes) {
        this.scopes = scopes;
    }

    @Scheduled(fixedDelayString = "${autoops.agent.scope.sweep-interval:1h}",
            initialDelayString = "${autoops.agent.scope.sweep-initial-delay:5m}")
    public void sweep() {
        try {
            scopes.sweepAbandoned(Instant.now());
        } catch (RuntimeException e) {
            // Spring already wraps a scheduled task so that a throw is logged
            // and suppressed rather than cancelling the schedule — this is not
            // guarding against that. It is here so the failure says what failed
            // and what happens next, instead of arriving as a bare stack trace
            // from a framework class that reads like the sweep is now dead.
            log.error("scope sweep failed; retrying on the next interval", e);
        }
    }
}
