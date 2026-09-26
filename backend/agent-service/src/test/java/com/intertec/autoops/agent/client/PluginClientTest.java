package com.intertec.autoops.agent.client;

import com.intertec.autoops.agent.config.AgentProperties;
import com.intertec.autoops.agent.domain.AgentRun;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Reporting an agent run to plugin-service.
 *
 * <p>The cases worth pinning are the ones where being wrong is invisible. This
 * client swallows its own failures on purpose — a notification must never fail
 * the run that caused it — which means a body this service gets wrong produces
 * no error anywhere a person would look, just a tenant's channels quietly going
 * quiet.
 */
class PluginClientTest {

    private static AgentRun run() {
        AgentRun run = new AgentRun();
        run.setTenantId("acme");
        run.setAgentId(41L);
        run.setProjectId(9004L);
        run.setCreatedBy("ops@acme.example");
        run.setStartedAt(Instant.now().minusSeconds(95));
        return run;
    }

    private record Harness(PluginClient client, MockRestServiceServer server) {
    }

    private static Harness harness(boolean enabled) {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://plugin");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AgentProperties properties = new AgentProperties();
        properties.getPlugin().setEnabled(enabled);
        properties.getPlugin().setInternalToken("tok");
        return new Harness(new PluginClient(builder.build(), properties), server);
    }

    @Test
    @DisplayName("reports the agent, its project and the tenant that owns it")
    void sendsTheEventBody() {
        Harness h = harness(true);
        h.server().expect(requestTo("http://plugin/internal/events"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header("X-Internal-Token", "tok"))
                .andExpect(jsonPath("$.tenantId").value("acme"))
                // AGENT, not WORKFLOW. An agent's tool workflows are delivered
                // as sealed components a customer cannot see, so reporting one
                // as a workflow would name something they cannot open.
                .andExpect(jsonPath("$.targetType").value("AGENT"))
                .andExpect(jsonPath("$.targetId").value(41))
                .andExpect(jsonPath("$.targetName").value("AWS FinOps Analyst"))
                .andExpect(jsonPath("$.event").value("AWAITING_APPROVAL"))
                .andExpect(jsonPath("$.projectId").value(9004))
                .andExpect(jsonPath("$.triggeredBy").value("ops@acme.example"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        h.client().publish(run(), "AWS FinOps Analyst", "AWAITING_APPROVAL",
                "Waiting for approval to run restart_instance.");

        h.server().verify();
    }

    @Test
    @DisplayName("names the agent rather than leaving a number to decode")
    void fallsBackToAReadableName() {
        // "Agent 41 is waiting for approval" is a message the reader has to go
        // and look up before they can act on it.
        Harness h = harness(true);
        h.server().expect(requestTo("http://plugin/internal/events"))
                .andExpect(jsonPath("$.targetName").value("Agent 41"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        h.client().publish(run(), "  ", "FAILED", "boom");

        h.server().verify();
    }

    @Test
    @DisplayName("a plugin-service failure does not reach the caller")
    void neverThrows() {
        // The whole failure policy. This sits on the agent loop's own thread
        // between turns; a notification that cannot be delivered must not fail
        // the run that caused it.
        Harness h = harness(true);
        h.server().expect(requestTo("http://plugin/internal/events"))
                .andRespond(withServerError());

        assertThatCode(() -> h.client().publish(run(), "Analyst", "FAILED", "boom"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the kill switch sends nothing at all")
    void disabledSendsNothing() {
        Harness h = harness(false);
        // No expectation registered: verify() fails if anything is sent.
        h.client().publish(run(), "Analyst", "FAILED", null);
        h.server().verify();
    }

    @Test
    @DisplayName("a run that has not started yet still reports a duration of zero, not a negative")
    void durationIsNeverNegative() {
        AgentRun fresh = run();
        fresh.setStartedAt(Instant.now().plusSeconds(60));

        Harness h = harness(true);
        List<String> bodies = new ArrayList<>();
        h.server().expect(requestTo("http://plugin/internal/events"))
                .andExpect(request -> bodies.add(request.getBody().toString()))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        h.client().publish(fresh, "Analyst", "QUEUED", null);

        // Clocks moving is the only way here. A negative elapsed time renders
        // as "-60s" in Slack, which reads as a bug in the agent.
        assertThat(bodies).hasSize(1);
        assertThat(bodies.get(0)).contains("\"durationSeconds\":null");
    }

    @Test
    @DisplayName("a null run is ignored rather than posted as a blank event")
    void nullRunIsIgnored() {
        Harness h = harness(true);
        h.client().publish(null, "Analyst", "FAILED", null);
        h.server().verify();
        assertThat(HttpStatus.OK).isNotNull();
    }

    /**
     * The spelling mismatch, pinned.
     *
     * <p>agent-service says CANCELLED; plugin-service says CANCELED, because
     * it lines up with core-service's RunStatus, which is what every job and
     * workflow reports. Sending this one through unmapped produces a 400 that
     * is swallowed at DEBUG on purpose — so the symptom is a tenant's
     * cancellation notices silently never arriving, with no error anywhere.
     */
    @Test
    @DisplayName("CANCELLED is translated to the single-L name plugin-service uses")
    void cancelledTranslates() {
        assertThat(PluginClient.eventFor(AgentRun.Status.CANCELLED)).isEqualTo("CANCELED");
    }

    @Test
    @DisplayName("and every other status passes through unchanged")
    void otherStatusesPassThrough() {
        assertThat(PluginClient.eventFor(AgentRun.Status.SUCCEEDED)).isEqualTo("SUCCEEDED");
        assertThat(PluginClient.eventFor(AgentRun.Status.FAILED)).isEqualTo("FAILED");
        assertThat(PluginClient.eventFor(AgentRun.Status.RUNNING)).isEqualTo("RUNNING");
    }
}
