package com.intertec.autoops.agent.service;

import com.intertec.autoops.agent.domain.FindingSuppression.ReasonCode;
import com.intertec.autoops.agent.domain.FindingSuppression.Scope;
import com.intertec.autoops.agent.exception.AgentException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The caps on dismissal, pinned before the endpoint that will use them.
 *
 * <p>A service-wide dismissal is one click and can hide hundreds of findings
 * nobody ever sees again. Retrofitting a role check after fifty of them exist
 * means either breaking them or grandfathering the hole — so the rules are
 * fixed now, while the only cost of changing them is editing a constant.
 */
class SuppressionPolicyTest {

    private static final Instant NOW = Instant.parse("2026-09-19T12:00:00Z");

    private void check(Scope scope, String role, Instant expiresAt, ReasonCode reason) {
        SuppressionPolicy.check(scope, role, expiresAt, reason, NOW);
    }

    private Instant inDays(long days) {
        return NOW.plus(Duration.ofDays(days));
    }

    // ------------------------------------------------------------ narrow ---

    @Test
    void anyoneMayDismissASingleFinding() {
        assertThatCode(() -> check(Scope.FINDING, "VIEWER", inDays(30), ReasonCode.ACCEPTED_RISK))
                .doesNotThrowAnyException();
    }

    @Test
    void anyoneMayDismissOneCategoryOnOneResource() {
        assertThatCode(() ->
                check(Scope.SUBJECT_CATEGORY, "OPERATOR", inDays(90), ReasonCode.BY_DESIGN))
                .doesNotThrowAnyException();
    }

    /**
     * Permanent is allowed when it is narrow AND the reason means it — an
     * accepted risk on one resource is a real, durable decision.
     */
    @Test
    void aNarrowDismissalMayBePermanentForAReasonThatMeansIt() {
        assertThatCode(() -> check(Scope.FINDING, "VIEWER", null, ReasonCode.BY_DESIGN))
                .doesNotThrowAnyException();
    }

    /** "I will come back to it" is by definition not permanent. */
    @Test
    void deferredAlwaysNeedsADateToComeBackOn() {
        assertThatThrownBy(() -> check(Scope.FINDING, "ADMIN", null, ReasonCode.DEFERRED))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("come back on");
    }

    // -------------------------------------------------------------- wide ---

    @Test
    void silencingAWholeCategoryNeedsAnElevatedRole() {
        assertThatThrownBy(() ->
                check(Scope.SERVICE_CATEGORY, "OPERATOR", inDays(7), ReasonCode.ACCEPTED_RISK))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("administrative act");

        assertThatCode(() ->
                check(Scope.SERVICE_CATEGORY, "ADMIN", inDays(7), ReasonCode.ACCEPTED_RISK))
                .doesNotThrowAnyException();
    }

    /**
     * A never-expiring tenant-wide dismissal is the same as deleting the check,
     * except that nobody remembers it exists.
     */
    @Test
    void aWideDismissalCanNeverBePermanentEvenForAnAdmin() {
        assertThatThrownBy(() ->
                check(Scope.CATEGORY_GLOBAL, "ADMIN", null, ReasonCode.BY_DESIGN))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("deleting the check");
    }

    /**
     * The ceiling is SHORTER the wider the dismissal reaches, which is the
     * opposite of what convenience suggests and the entire point.
     */
    @Test
    void theWiderTheDismissalTheSoonerSomebodyHasToLookAgain() {
        assertThatThrownBy(() ->
                check(Scope.CATEGORY_GLOBAL, "ADMIN", inDays(30), ReasonCode.ACCEPTED_RISK))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("at most 14 days");

        // The same 30 days is unremarkable on a single finding.
        assertThatCode(() -> check(Scope.FINDING, "VIEWER", inDays(30), ReasonCode.ACCEPTED_RISK))
                .doesNotThrowAnyException();
    }

    // --------------------------------------------------------- always on ---

    @Test
    void everyDismissalNeedsAReasonFromTheSupportedSet() {
        assertThatThrownBy(() -> check(Scope.FINDING, "ADMIN", inDays(1), null))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("not only free text");
    }

    @Test
    void aDismissalThatHasAlreadyLapsedIsRefused() {
        assertThatThrownBy(() ->
                check(Scope.FINDING, "ADMIN", NOW.minus(Duration.ofHours(1)),
                        ReasonCode.ACCEPTED_RISK))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("already lapsed");
    }

    @Test
    void scopeIsRequired() {
        assertThatThrownBy(() -> check(null, "ADMIN", inDays(1), ReasonCode.ACCEPTED_RISK))
                .isInstanceOf(AgentException.class);
    }
}
