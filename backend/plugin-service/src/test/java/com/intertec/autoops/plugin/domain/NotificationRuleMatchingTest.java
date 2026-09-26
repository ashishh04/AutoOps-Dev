package com.intertec.autoops.plugin.domain;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wildcard semantics of a rule's scope. These are the decisions that
 * decide whether an alert fires at all, and every one of them is a place a
 * null check could silently widen a rule across projects.
 */
class NotificationRuleMatchingTest {

    private static NotificationRule rule(TargetType type, Long targetId, Long projectId,
                                         LifecycleEvent... events) {
        NotificationRule rule = new NotificationRule();
        rule.setTenantId("t1");
        rule.setInstallationId(1L);
        rule.setTargetType(type);
        rule.setTargetId(targetId);
        rule.setProjectId(projectId);
        rule.setEventSet(EnumSet.copyOf(java.util.List.of(events)));
        return rule;
    }

    @Test
    void targetScopedRuleMatchesOnlyThatTarget() {
        NotificationRule rule = rule(TargetType.JOB, 7L, null, LifecycleEvent.FAILED);

        assertThat(rule.matches(TargetType.JOB, 7L, 3L, LifecycleEvent.FAILED)).isTrue();
        assertThat(rule.matches(TargetType.JOB, 8L, 3L, LifecycleEvent.FAILED)).isFalse();
    }

    @Test
    void projectScopedRuleMatchesEveryTargetInThatProject() {
        NotificationRule rule = rule(TargetType.JOB, null, 3L, LifecycleEvent.FAILED);

        assertThat(rule.matches(TargetType.JOB, 7L, 3L, LifecycleEvent.FAILED)).isTrue();
        assertThat(rule.matches(TargetType.JOB, 99L, 3L, LifecycleEvent.FAILED)).isTrue();
        assertThat(rule.matches(TargetType.JOB, 7L, 4L, LifecycleEvent.FAILED)).isFalse();
    }

    /** A project rule must not fire for an event that carries no project. */
    @Test
    void projectScopedRuleDoesNotMatchAProjectlessEvent() {
        NotificationRule rule = rule(TargetType.JOB, null, 3L, LifecycleEvent.MISSED);

        assertThat(rule.matches(TargetType.JOB, 7L, null, LifecycleEvent.MISSED)).isFalse();
    }

    @Test
    void unscopedRuleMatchesTheWholeWorkspaceIncludingProjectlessEvents() {
        NotificationRule rule = rule(TargetType.JOB, null, null, LifecycleEvent.FAILED);

        assertThat(rule.matches(TargetType.JOB, 7L, 3L, LifecycleEvent.FAILED)).isTrue();
        assertThat(rule.matches(TargetType.JOB, 1L, null, LifecycleEvent.FAILED)).isTrue();
    }

    /** Jobs and workflows share a run engine but must never share alerts. */
    @Test
    void aJobRuleNeverMatchesAWorkflow() {
        NotificationRule rule = rule(TargetType.JOB, null, null, LifecycleEvent.FAILED);

        assertThat(rule.matches(TargetType.WORKFLOW, 7L, 3L, LifecycleEvent.FAILED)).isFalse();
    }

    @Test
    void anUnsubscribedEventDoesNotMatch() {
        NotificationRule rule = rule(TargetType.JOB, null, null, LifecycleEvent.FAILED);

        assertThat(rule.matches(TargetType.JOB, 7L, 3L, LifecycleEvent.SUCCEEDED)).isFalse();
    }

    @Test
    void aDisabledRuleMatchesNothing() {
        NotificationRule rule = rule(TargetType.JOB, null, null, LifecycleEvent.FAILED);
        rule.setEnabled(false);

        assertThat(rule.matches(TargetType.JOB, 7L, 3L, LifecycleEvent.FAILED)).isFalse();
    }

    /** Round-trips through the comma-separated column, in enum order. */
    @Test
    void eventSetSurvivesStorageAsAString() {
        NotificationRule rule = rule(TargetType.JOB, null, null,
                LifecycleEvent.FAILED, LifecycleEvent.STARTED, LifecycleEvent.MISSED);

        assertThat(rule.getEvents()).isEqualTo("STARTED,FAILED,MISSED");
        assertThat(rule.eventSet()).containsExactlyInAnyOrder(
                LifecycleEvent.STARTED, LifecycleEvent.FAILED, LifecycleEvent.MISSED);
    }

    // ---- agents and alerts ---------------------------------------------

    private static NotificationRule severityFloor(LifecycleEvent.Severity floor,
                                                  LifecycleEvent... events) {
        NotificationRule rule = rule(TargetType.ALERT, null, null, events);
        rule.setMinSeverity(floor);
        return rule;
    }

    @Test
    void anAgentRuleDoesNotMatchAJobOfTheSameId() {
        // The ids are from different tables and will collide constantly. Job 7
        // failing must not fire a rule watching agent 7.
        NotificationRule rule = rule(TargetType.AGENT, 7L, null, LifecycleEvent.FAILED);

        assertThat(rule.matches(TargetType.AGENT, 7L, 3L, LifecycleEvent.FAILED)).isTrue();
        assertThat(rule.matches(TargetType.JOB, 7L, 3L, LifecycleEvent.FAILED)).isFalse();
    }

    @Test
    void anAgentRuleCanWatchForAnApprovalItIsBlockedOn() {
        // The event that justified adding AGENT at all: work that has STOPPED
        // and stays stopped until a human acts.
        NotificationRule rule =
                rule(TargetType.AGENT, null, null, LifecycleEvent.AWAITING_APPROVAL);

        assertThat(rule.matches(TargetType.AGENT, 41L, 9L, LifecycleEvent.AWAITING_APPROVAL))
                .isTrue();
        assertThat(rule.matches(TargetType.AGENT, 41L, 9L, LifecycleEvent.SUCCEEDED)).isFalse();
    }

    /**
     * The reason min_severity exists. A workspace takes hundreds of alerts a
     * day, most of them informational, and a rule that cannot say "critical
     * only" is a rule nobody can afford to leave on.
     */
    @Test
    void anAlertRuleCanDemandACriticalSeverity() {
        NotificationRule rule =
                severityFloor(LifecycleEvent.Severity.CRITICAL, LifecycleEvent.TRIGGERED);

        assertThat(rule.matches(TargetType.ALERT, null, 3L, LifecycleEvent.TRIGGERED,
                LifecycleEvent.Severity.CRITICAL)).isTrue();
        assertThat(rule.matches(TargetType.ALERT, null, 3L, LifecycleEvent.TRIGGERED,
                LifecycleEvent.Severity.WARNING)).isFalse();
        assertThat(rule.matches(TargetType.ALERT, null, 3L, LifecycleEvent.TRIGGERED,
                LifecycleEvent.Severity.INFO)).isFalse();
    }

    @Test
    void aWarningFloorStillAdmitsCriticals() {
        // Ordering, not equality. A floor of WARNING that filtered out
        // CRITICAL would suppress exactly the alerts it was set to catch.
        NotificationRule rule =
                severityFloor(LifecycleEvent.Severity.WARNING, LifecycleEvent.TRIGGERED);

        assertThat(rule.matches(TargetType.ALERT, null, 3L, LifecycleEvent.TRIGGERED,
                LifecycleEvent.Severity.CRITICAL)).isTrue();
        assertThat(rule.matches(TargetType.ALERT, null, 3L, LifecycleEvent.TRIGGERED,
                LifecycleEvent.Severity.WARNING)).isTrue();
        assertThat(rule.matches(TargetType.ALERT, null, 3L, LifecycleEvent.TRIGGERED,
                LifecycleEvent.Severity.INFO)).isFalse();
    }

    @Test
    void noFloorTakesEverything() {
        // Every rule that existed before min_severity did. Null must keep
        // meaning "no opinion" rather than quietly becoming a floor of INFO.
        NotificationRule rule = rule(TargetType.ALERT, null, null, LifecycleEvent.TRIGGERED);

        assertThat(rule.matches(TargetType.ALERT, null, 3L, LifecycleEvent.TRIGGERED,
                LifecycleEvent.Severity.INFO)).isTrue();
    }

    @Test
    void aRunEventFallsBackToItsOwnSeverity() {
        // Runs report no severity of their own. A FAILED job is CRITICAL by
        // the event's definition, so a CRITICAL floor must not silence it.
        NotificationRule rule = rule(TargetType.JOB, null, null, LifecycleEvent.FAILED,
                LifecycleEvent.SUCCEEDED);
        rule.setMinSeverity(LifecycleEvent.Severity.CRITICAL);

        assertThat(rule.matches(TargetType.JOB, 7L, 3L, LifecycleEvent.FAILED)).isTrue();
        assertThat(rule.matches(TargetType.JOB, 7L, 3L, LifecycleEvent.SUCCEEDED)).isFalse();
    }

    @Test
    void anAlertRuleIsProjectWideOrWorkspaceWide() {
        // An alert has no numeric id, so target scoping cannot apply to one.
        NotificationRule rule = rule(TargetType.ALERT, null, 3L, LifecycleEvent.TRIGGERED);

        assertThat(rule.matches(TargetType.ALERT, null, 3L, LifecycleEvent.TRIGGERED)).isTrue();
        assertThat(rule.matches(TargetType.ALERT, null, 4L, LifecycleEvent.TRIGGERED)).isFalse();
    }
}
