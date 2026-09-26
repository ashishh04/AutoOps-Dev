package com.intertec.autoops.plugin.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The wire names three other services send, pinned here.
 *
 * <h2>Why this test exists at all</h2>
 * core-service, agent-service and alert-service each report lifecycle events by
 * POSTing a JSON body whose {@code event} field is a plain string. None of them
 * depends on this module, so none of them can reference {@link LifecycleEvent}
 * — which means renaming a constant here compiles cleanly everywhere and breaks
 * nothing that any build would notice.
 *
 * <p>What it breaks is delivery. Jackson fails to bind the unknown name, the
 * request comes back 400, and every one of those clients swallows that at DEBUG
 * on purpose — they must never fail a run because a notification could not be
 * queued. So the symptom is a tenant's channels silently going quiet, with no
 * error anywhere, which is precisely the failure a notification system must not
 * have.
 *
 * <p>This is the enforceable half: a rename here fails this test and the
 * message names the callers to update. It cannot catch a caller inventing a
 * name that was never valid, which is what {@link #unknownNamesAreRejected}
 * documents the consequence of.
 */
class EventNameContractTest {

    /**
     * Emitted by core-service's {@code LifecycleNotifier} and its schedule
     * watchdog, for JOB and WORKFLOW.
     */
    private static final List<String> CORE_SERVICE = List.of(
            "QUEUED", "STARTED", "SUCCEEDED", "FAILED", "CANCELED",
            "MISSED", "STALLED", "RECOVERED");

    /** Emitted by agent-service's {@code AgentRunService}, for AGENT. */
    private static final List<String> AGENT_SERVICE = List.of(
            "QUEUED", "STARTED", "SUCCEEDED", "FAILED", "CANCELED",
            "RECOVERED", "AWAITING_APPROVAL");

    /** Emitted by alert-service's {@code PluginClient}, for ALERT. */
    private static final List<String> ALERT_SERVICE = List.of(
            "TRIGGERED", "RESOLVED", "ACKNOWLEDGED");

    @Test
    @DisplayName("every name core-service sends still parses")
    void coreServiceNamesResolve() {
        CORE_SERVICE.forEach(name -> assertThatCode(() -> LifecycleEvent.valueOf(name))
                .as("core-service sends %s; renaming it here silences every job "
                        + "notification in the platform", name)
                .doesNotThrowAnyException());
    }

    @Test
    @DisplayName("every name agent-service sends still parses")
    void agentServiceNamesResolve() {
        AGENT_SERVICE.forEach(name -> assertThatCode(() -> LifecycleEvent.valueOf(name))
                .as("agent-service sends %s from AgentRunService", name)
                .doesNotThrowAnyException());
    }

    @Test
    @DisplayName("every name alert-service sends still parses")
    void alertServiceNamesResolve() {
        ALERT_SERVICE.forEach(name -> assertThatCode(() -> LifecycleEvent.valueOf(name))
                .as("alert-service sends %s from its PluginClient", name)
                .doesNotThrowAnyException());
    }

    @Test
    @DisplayName("and each is valid for the target type that service reports")
    void namesFitTheirTargets() {
        // A name that parses is not enough. An event that does not apply to the
        // target type is refused when the RULE is written, so the rule can
        // never exist — and an event nobody can subscribe to is one that is
        // emitted into nothing.
        CORE_SERVICE.forEach(name -> {
            assertThat(LifecycleEvent.valueOf(name).appliesTo(TargetType.JOB)).isTrue();
            assertThat(LifecycleEvent.valueOf(name).appliesTo(TargetType.WORKFLOW)).isTrue();
        });
        AGENT_SERVICE.forEach(name ->
                assertThat(LifecycleEvent.valueOf(name).appliesTo(TargetType.AGENT)).isTrue());
        ALERT_SERVICE.forEach(name ->
                assertThat(LifecycleEvent.valueOf(name).appliesTo(TargetType.ALERT)).isTrue());
    }

    @Test
    @DisplayName("an unknown name is rejected rather than ignored")
    void unknownNamesAreRejected() {
        // The consequence this test's existence is justified by. There is no
        // lenient fallback, and there should not be: an event nobody recognises
        // matching every rule would be worse than one matching none.
        assertThatThrownBy(() -> LifecycleEvent.valueOf("CANCELLED"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the single-L CANCELED spelling survives")
    void canceledKeepsItsSpelling() {
        // core-service's RunStatus spells it with one L and this must line up
        // with it. "Fixing" the spelling here drops every cancellation notice.
        assertThat(LifecycleEvent.CANCELED.name()).isEqualTo("CANCELED");
    }
}
