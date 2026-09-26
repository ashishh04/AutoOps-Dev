package com.intertec.autoops.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intertec.autoops.core.client.WorkflowClient;
import com.intertec.autoops.core.domain.CloudConnection;
import com.intertec.autoops.core.domain.CloudPlatform;
import com.intertec.autoops.core.domain.ConnectionStatus;
import com.intertec.autoops.core.repo.CloudConnectionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Whether an agent's tools can reach anything, asked before the run starts.
 *
 * <p>The rule this has to match exactly is {@code resolveForStep}'s: a
 * connection with NO project is global and serves every project; one with a
 * project serves only that project. Too strict and this blocks runs that would
 * have worked; too loose and it waves through the failure it exists to catch.
 * Both directions are pinned below.
 */
class AgentReadinessServiceTest {

    private final WorkflowClient workflows = mock(WorkflowClient.class);
    private final CloudConnectionRepository connections = mock(CloudConnectionRepository.class);
    private final AgentReadinessService service =
            new AgentReadinessService(workflows, connections, new ObjectMapper());

    private static final String NEEDS_AWS = """
            {"nodes":[],"requires":[{"kind":"cloud_connection","platform":"AWS"}]}
            """;
    private static final String NEEDS_NOTHING = """
            {"nodes":[],"requires":[]}
            """;

    private static CloudConnection connection(CloudPlatform platform, Long projectId,
                                              boolean withCredentials) {
        CloudConnection c = new CloudConnection();
        c.setPlatform(platform);
        c.setProjectId(projectId);
        c.setStatus(ConnectionStatus.CONNECTED);
        c.setCredentialsEnc(withCredentials ? "enc" : null);
        return c;
    }

    private void workflow(String definition) {
        when(workflows.find(anyString(), anyLong())).thenReturn(Optional.of(
                new WorkflowClient.WorkflowView(1L, "acme", 7L, "Tool", definition, 1, true)));
    }

    private void connected(CloudConnection... rows) {
        when(connections.findByTenantIdAndStatus(anyString(), any())).thenReturn(List.of(rows));
    }

    @Test
    @DisplayName("a tool needing AWS in a project with no AWS is reported missing")
    void missingIsReported() {
        workflow(NEEDS_AWS);
        connected();

        var result = service.check("acme", 9004L, List.of(1L));

        assertThat(result.ready()).isFalse();
        assertThat(result.missing()).containsExactly("AWS");
    }

    @Test
    @DisplayName("a connection in THIS project satisfies it")
    void sameProjectSatisfies() {
        workflow(NEEDS_AWS);
        connected(connection(CloudPlatform.AWS, 7L, true));

        assertThat(service.check("acme", 7L, List.of(1L)).ready()).isTrue();
    }

    @Test
    @DisplayName("a GLOBAL connection satisfies every project")
    void globalConnectionSatisfies() {
        // The false-negative that a naive "project_id = X" check produces. A
        // connection with no project is reachable from everywhere, and blocking
        // a run it would have served is the same bug in the other direction.
        workflow(NEEDS_AWS);
        connected(connection(CloudPlatform.AWS, null, true));

        assertThat(service.check("acme", 9004L, List.of(1L)).ready()).isTrue();
    }

    @Test
    @DisplayName("ANOTHER project's connection does not satisfy it")
    void otherProjectDoesNotSatisfy() {
        workflow(NEEDS_AWS);
        connected(connection(CloudPlatform.AWS, 2L, true));

        assertThat(service.check("acme", 9004L, List.of(1L)).missing()).containsExactly("AWS");
    }

    @Test
    @DisplayName("connected but with no credentials does not count")
    void credentiallessDoesNotCount() {
        // resolveForStep filters these out too. Counting one here would promise
        // access that fails at the moment it is used.
        workflow(NEEDS_AWS);
        connected(connection(CloudPlatform.AWS, 7L, false));

        assertThat(service.check("acme", 7L, List.of(1L)).missing()).containsExactly("AWS");
    }

    @Test
    @DisplayName("a platform-plane tool needs nothing and is always ready")
    void platformPlaneNeedsNothing() {
        // The correlator reads AutoOps's own record. Demanding a cloud account
        // of it would block the one agent that works for every estate.
        workflow(NEEDS_NOTHING);
        connected();

        assertThat(service.check("acme", 9005L, List.of(1L)).ready()).isTrue();
    }

    @Test
    @DisplayName("an unreadable tool is named unverifiable, not treated as satisfied")
    void unreadableToolIsNamed() {
        // A readiness answer that is quietly partial is worse than none: the
        // run proceeds on the strength of it.
        when(workflows.find(anyString(), anyLong())).thenReturn(Optional.empty());
        connected();

        var result = service.check("acme", 7L, List.of(42L));

        assertThat(result.unverifiable()).containsExactly(42L);
        assertThat(result.missing()).isEmpty();
    }

    @Test
    @DisplayName("workflow-service being down does not block the run")
    void upstreamFailureFailsOpen() {
        // Turning another service's outage into "your agent is misconfigured"
        // sends whoever reads it somewhere useless.
        when(workflows.find(anyString(), anyLong())).thenThrow(new RuntimeException("down"));
        connected();

        var result = service.check("acme", 7L, List.of(42L));

        assertThat(result.ready()).isTrue();
        assertThat(result.unverifiable()).containsExactly(42L);
    }

    @Test
    @DisplayName("no tools means nothing to check")
    void noToolsIsReady() {
        connected();
        assertThat(service.check("acme", 7L, List.of()).ready()).isTrue();
        assertThat(service.check("acme", 7L, null).ready()).isTrue();
    }
}
