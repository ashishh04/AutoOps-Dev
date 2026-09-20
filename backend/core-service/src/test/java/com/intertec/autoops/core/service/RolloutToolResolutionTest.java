package com.intertec.autoops.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intertec.autoops.core.client.AgentClient;
import com.intertec.autoops.core.client.EntitlementClient;
import com.intertec.autoops.core.client.WorkflowClient;
import com.intertec.autoops.core.domain.LibraryItem;
import com.intertec.autoops.core.domain.Project;
import com.intertec.autoops.core.repo.LibraryItemRepository;
import com.intertec.autoops.core.repo.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Turning a catalog agent's stable {@code ref}s into the ids the receiving
 * customer's project actually uses.
 *
 * <p>This is what stands between "the provider published an agent" and "the
 * customer has a working one". Before it existed, every agent rollout failed:
 * the allow-list carried the provider's own ids, and the destination refused
 * them — correctly, since accepting them is how an agent would reach across a
 * tenant boundary.
 */
class RolloutToolResolutionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WorkflowClient workflowClient;
    private AgentClient agentClient;
    private LibraryItemRepository libraryRepository;
    private RolloutService service;

    @BeforeEach
    void setUp() {
        workflowClient = mock(WorkflowClient.class);
        agentClient = mock(AgentClient.class);
        libraryRepository = mock(LibraryItemRepository.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        EntitlementClient entitlements = mock(EntitlementClient.class);

        // Project has no id setter (JPA-assigned), so it is mocked rather
        // than constructed.
        Project project = mock(Project.class);
        when(project.getId()).thenReturn(9L);
        when(projects.findByIdAndTenantId(9L, "acme")).thenReturn(Optional.of(project));
        when(entitlements.checkTenant("acme"))
                .thenReturn(new EntitlementClient.Decision(true, null, null, null));
        when(agentClient.rollOut(anyString(), anyString(), any(), anyLong(), anyLong(),
                anyString(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AgentClient.RolledOutAgent(77L, "Linux Server Health Check Agent", 1));

        service = new RolloutService(libraryRepository, projects, workflowClient, agentClient,
                entitlements, mock(AuditService.class), MAPPER);
    }

    /** A catalog AGENT whose allow-list names workflows by ref. */
    private void givenCatalogAgent(String toolsJson) {
        givenCatalogItem("{\"description\":\"d\",\"instructions\":\"i\",\"tools\":"
                + toolsJson + "}");
    }

    /** A Python-authored agent: a reference, and deliberately no persona. */
    private void givenPythonCatalogAgent(String toolsJson) {
        givenCatalogItem("{\"kind\":\"PYTHON\",\"ref\":\"linux.server_health_check\","
                + "\"version\":\"1.0.0\",\"description\":\"d\",\"tools\":"
                + toolsJson + "}");
    }

    private void givenCatalogItem(String definition) {
        LibraryItem item = mock(LibraryItem.class);
        when(item.getId()).thenReturn(238L);
        when(item.getTitle()).thenReturn("Linux Server Health Check Agent");
        when(item.getType()).thenReturn(LibraryItem.Type.AGENT);
        when(item.getDefinition()).thenReturn(definition);
        when(libraryRepository.findByIdAndTenantIdIsNull(238L)).thenReturn(Optional.of(item));
    }

    /** What the customer's project already holds, each carrying its own ref. */
    private void givenDelivered(Object... idRefPairs) {
        List<WorkflowClient.WorkflowView> views = new java.util.ArrayList<>();
        for (int i = 0; i < idRefPairs.length; i += 2) {
            views.add(new WorkflowClient.WorkflowView((Long) idRefPairs[i], "acme", 9L, "wf",
                    "{\"ref\":\"" + idRefPairs[i + 1] + "\",\"nodes\":[]}", 0, true));
        }
        when(workflowClient.listByProject("acme", 9L)).thenReturn(views);
    }

    private RolloutService.RolloutResult rollOut() {
        return service.rollOut("provider", "token", 238L,
                List.of(new RolloutService.Target("acme", 9L)));
    }

    private String deliveredTools() {
        ArgumentCaptor<String> tools = ArgumentCaptor.forClass(String.class);
        verify(agentClient).rollOut(anyString(), anyString(), any(), anyLong(), anyLong(),
                anyString(), any(), any(), any(), any(), any(), tools.capture());
        return tools.getValue();
    }

    @Test
    void rewritesEachRefToTheIdThisProjectUses() {
        givenCatalogAgent("[{\"type\":\"WORKFLOW\",\"ref\":\"RD-079-linux-server-health-check\"}]");
        givenDelivered(501L, "RD-079-linux-server-health-check");

        assertThat(rollOut().delivered()).isEqualTo(1);
        // 501 is this customer's copy — nothing like the provider's own id.
        // The ref rides along beside it: the id is what the customer RUNS, the
        // ref is what the agent's author DECLARED, and the runtime needs the
        // second to match a tool result back to what that tool returns.
        assertThat(deliveredTools()).isEqualTo(
                "[{\"type\":\"WORKFLOW\",\"id\":501,"
                        + "\"ref\":\"RD-079-linux-server-health-check\",\"mutating\":true}]");
    }

    @Test
    void resolvesEveryRefIndependently() {
        givenCatalogAgent("[{\"type\":\"WORKFLOW\",\"ref\":\"RD-079-a\"},"
                + "{\"type\":\"WORKFLOW\",\"ref\":\"RD-142-b\"}]");
        givenDelivered(501L, "RD-079-a", 502L, "RD-142-b");

        assertThat(rollOut().delivered()).isEqualTo(1);
        assertThat(deliveredTools()).contains("\"id\":501").contains("\"id\":502");
    }

    @Test
    void differentCustomersGetDifferentIdsForTheSameAgent() {
        // The whole reason refs exist rather than ids.
        givenCatalogAgent("[{\"type\":\"WORKFLOW\",\"ref\":\"RD-079-a\"}]");
        givenDelivered(88L, "RD-079-a");

        rollOut();
        assertThat(deliveredTools()).isEqualTo(
                "[{\"type\":\"WORKFLOW\",\"id\":88,\"ref\":\"RD-079-a\",\"mutating\":true}]");
    }

    /**
     * The ref survives delivery, which is the only reason the runtime can tell
     * what a tool returns.
     *
     * <p>The delivered {@code id} is tenant-local and means nothing outside this
     * project; the tool name the model sees is derived from it. Without the ref
     * there is no stable handle joining a tool result back to what the agent's
     * author declared about it — and subject extraction, which is what a
     * coverage scope is built from, has nothing to key on.
     */
    @Test
    void theRefIsCarriedThroughDeliveryBesideTheId() {
        givenCatalogAgent("[{\"type\":\"WORKFLOW\",\"ref\":\"RD-136-idle-resource-inventory\","
                + "\"mutating\":false}]");
        givenDelivered(77L, "RD-136-idle-resource-inventory");

        rollOut();
        assertThat(deliveredTools())
                .contains("\"ref\":\"RD-136-idle-resource-inventory\"")
                .contains("\"id\":77")
                .contains("\"mutating\":false");
    }

    @Test
    void failsTheDeliveryWhenAReferencedWorkflowIsNotThereYet() {
        // Never deliver an agent with a tool silently dropped: the customer
        // would get something that looks complete and quietly cannot do part
        // of its job.
        givenCatalogAgent("[{\"type\":\"WORKFLOW\",\"ref\":\"RD-079-missing\"}]");
        givenDelivered(501L, "RD-079-something-else");

        RolloutService.RolloutResult result = rollOut();
        assertThat(result.delivered()).isZero();
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.deliveries().get(0).error()).contains("RD-079-missing");
        verify(agentClient, never()).rollOut(anyString(), anyString(), any(), anyLong(),
                anyLong(), anyString(), any(), any(), any(), any(), any(), any());
    }

    // ------------------------------------- components: delivered, not sold ---

    /** A catalog workflow the provider has published, carrying its stable ref. */
    private void givenPublishedWorkflow(Long id, String ref, String title) {
        LibraryItem workflow = mock(LibraryItem.class);
        when(workflow.getId()).thenReturn(id);
        when(workflow.getTitle()).thenReturn(title);
        when(workflow.getType()).thenReturn(LibraryItem.Type.WORKFLOW);
        when(workflow.getDefinition()).thenReturn("{\"ref\":\"" + ref + "\",\"nodes\":[]}");
        when(libraryRepository.findByTenantIdIsNullOrderByCreatedAtDesc())
                .thenReturn(List.of(workflow));
    }

    /**
     * Rolling out an agent delivers the automations it needs, sealed.
     *
     * <p>Workflows and agents are licensed separately, but an agent can only
     * act through automations that live in the customer's project — so they
     * have to arrive. They arrive as AGENT_COMPONENT, which is what keeps the
     * commercial line: the customer bought an agent, so they see an agent.
     */
    @Test
    void anAgentsMissingToolsAreDeliveredAsSealedComponents() {
        givenCatalogAgent("[{\"type\":\"WORKFLOW\",\"ref\":\"RD-210-alarms\"}]");
        givenPublishedWorkflow(267L, "RD-210-alarms", "CloudWatch Alarm State Audit");
        // Empty before delivery; present after — the service re-reads the
        // listing between delivering components and resolving the allow-list.
        when(workflowClient.listByProject("acme", 9L)).thenReturn(
                List.of(),
                List.of(new WorkflowClient.WorkflowView(601L, "acme", 9L, "CloudWatch Alarm State Audit",
                        "{\"ref\":\"RD-210-alarms\",\"nodes\":[]}", 0, true, "AGENT_COMPONENT")));

        RolloutService.RolloutResult result = rollOut();

        assertThat(result.delivered()).isEqualTo(1);
        verify(workflowClient).rollOut(eq("acme"), anyString(), any(), eq(9L), eq(267L),
                eq("CloudWatch Alarm State Audit"), anyString(), eq("AGENT_COMPONENT"));
        // And the agent's allow-list points at the copy that was just delivered.
        assertThat(deliveredTools()).contains("601");
    }

    /**
     * A workflow the customer already holds is left exactly as it is.
     *
     * <p>If they licensed it in its own right, rolling out an agent that
     * happens to use it must not quietly reseal it as a component and take it
     * out of their list.
     */
    @Test
    void aWorkflowTheProjectAlreadyHoldsIsNotRedelivered() {
        givenCatalogAgent("[{\"type\":\"WORKFLOW\",\"ref\":\"RD-210-alarms\"}]");
        givenPublishedWorkflow(267L, "RD-210-alarms", "CloudWatch Alarm State Audit");
        givenDelivered(601L, "RD-210-alarms");

        assertThat(rollOut().delivered()).isEqualTo(1);

        verify(workflowClient, never()).rollOut(anyString(), anyString(), any(), anyLong(),
                anyLong(), anyString(), anyString(), anyString());
    }

    // ------------------------------------------- mutability, and the shape ---

    /**
     * The phased runtime hides state-changing tools from the phase that is
     * still gathering evidence, and only the agent's author knows which is
     * which. If the flag did not survive delivery, every customer's copy would
     * fall back to the safe default and the read-only tools would go unused.
     */
    @Test
    void carriesAnExplicitReadOnlyFlagThroughDelivery() {
        givenCatalogAgent("[{\"type\":\"WORKFLOW\",\"ref\":\"RD-079-a\",\"mutating\":false}]");
        givenDelivered(501L, "RD-079-a");

        assertThat(rollOut().delivered()).isEqualTo(1);
        assertThat(deliveredTools()).contains("\"mutating\":false");
    }

    /**
     * The default must fall this way. An unlabelled destructive automation
     * treated as read-only would be handed to exactly the phase the narrowing
     * exists to protect; treated as mutating it simply goes unused, which is
     * visible and harmless.
     */
    @Test
    void anUnlabelledToolIsDeliveredAsMutating() {
        givenCatalogAgent("[{\"type\":\"WORKFLOW\",\"ref\":\"RD-079-a\"}]");
        givenDelivered(501L, "RD-079-a");

        rollOut();
        assertThat(deliveredTools()).contains("\"mutating\":true");
    }

    /**
     * The sealing guarantee at the delivery boundary: a Python-authored agent
     * hands the customer a REFERENCE. Its persona, prompts and phase graph stay
     * in agent-runtime's image and never touch this database.
     */
    @Test
    void aPythonAgentIsDeliveredAsAReferenceAndCarriesNoPersona() {
        givenPythonCatalogAgent("[{\"type\":\"WORKFLOW\",\"ref\":\"RD-079-a\","
                + "\"mutating\":false}]");
        givenDelivered(501L, "RD-079-a");

        assertThat(rollOut().delivered()).isEqualTo(1);

        ArgumentCaptor<String> instructions = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> graphRef = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> graphVersion = ArgumentCaptor.forClass(String.class);
        verify(agentClient).rollOut(anyString(), anyString(), any(), anyLong(), anyLong(),
                anyString(), any(), any(), instructions.capture(), graphRef.capture(),
                graphVersion.capture(), any());

        assertThat(instructions.getValue()).isNull();
        assertThat(graphRef.getValue()).isEqualTo("linux.server_health_check");
        assertThat(graphVersion.getValue()).isEqualTo("1.0.0");
    }

    /** And the converse: a JSON agent must not acquire a graph ref. */
    @Test
    void aJsonAgentStillDeliversItsPersonaAndNoGraphRef() {
        givenCatalogAgent("[{\"type\":\"WORKFLOW\",\"ref\":\"RD-079-a\"}]");
        givenDelivered(501L, "RD-079-a");

        rollOut();

        ArgumentCaptor<String> instructions = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> graphRef = ArgumentCaptor.forClass(String.class);
        verify(agentClient).rollOut(anyString(), anyString(), any(), anyLong(), anyLong(),
                anyString(), any(), any(), instructions.capture(), graphRef.capture(),
                any(), any());

        assertThat(instructions.getValue()).isEqualTo("i");
        assertThat(graphRef.getValue()).isNull();
    }

    @Test
    void refusesAJobReferenceBecauseJobsAreNeverRolledOut() {
        givenCatalogAgent("[{\"type\":\"JOB\",\"ref\":\"RD-079-a\"}]");
        givenDelivered(501L, "RD-079-a");

        assertThat(rollOut().deliveries().get(0).error()).contains("only use workflows");
    }

    @Test
    void aDeliveredWorkflowWithoutARefIsSimplyNotAMatch() {
        givenCatalogAgent("[{\"type\":\"WORKFLOW\",\"ref\":\"RD-079-a\"}]");
        when(workflowClient.listByProject("acme", 9L)).thenReturn(List.of(
                new WorkflowClient.WorkflowView(501L, "acme", 9L, "legacy",
                        "{\"nodes\":[]}", 0, true),
                new WorkflowClient.WorkflowView(502L, "acme", 9L, "broken",
                        "{not json", 0, true)));

        assertThat(rollOut().deliveries().get(0).error()).contains("RD-079-a");
    }

    @Test
    void anAgentWithNoToolsStillDelivers() {
        // Legitimate: a persona-only agent that a customer will wire up later.
        givenCatalogAgent("[]");
        givenDelivered();

        assertThat(rollOut().delivered()).isEqualTo(1);
        assertThat(deliveredTools()).isNull();
    }
}
