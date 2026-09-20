package com.intertec.autoops.agent.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intertec.autoops.agent.client.AutomationClient;
import com.intertec.autoops.agent.client.ToolTargetClient;
import com.intertec.autoops.agent.domain.Agent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The allow-list is an agent's entire authority, and this class is where a
 * name the model returned becomes a thing that gets executed. So the cases
 * that matter are the ones where the answer must be "no": a name that was
 * never granted, a target that has since moved out of the project, a workflow
 * that cannot actually be run.
 */
class AgentToolboxTest {

    private static final String TENANT = "acme-corp-cafe0123";
    private static final long PROJECT = 7L;

    private ToolTargetClient toolTargets;
    private AutomationClient automations;
    private AgentToolbox toolbox;

    @BeforeEach
    void setUp() {
        toolTargets = Mockito.mock(ToolTargetClient.class);
        automations = Mockito.mock(AutomationClient.class);
        toolbox = new AgentToolbox(new ObjectMapper(), toolTargets, automations);

        // Default: nothing exists. Each test grants exactly what it needs, so
        // an accidental extra tool cannot slip in unnoticed.
        when(toolTargets.findJob(anyString(), anyLong())).thenReturn(Optional.empty());
        when(toolTargets.findWorkflow(anyString(), anyLong())).thenReturn(Optional.empty());
        when(automations.workflowInputs(anyString(), anyLong()))
                .thenReturn(new AutomationClient.WorkflowInputs(List.of(), null));
    }

    private Agent agent(String tools) {
        Agent agent = new Agent();
        agent.setTenantId(TENANT);
        agent.setProjectId(PROJECT);
        agent.setName("Patch Operator");
        agent.setTools(tools);
        return agent;
    }

    @Test
    void offersAGrantedJobUnderADerivedName() {
        when(toolTargets.findJob(TENANT, 14L))
                .thenReturn(Optional.of(new ToolTargetClient.Target(14L, PROJECT, "Nightly patch")));

        AgentToolbox.Toolbox built = toolbox.build(agent("[{\"type\":\"JOB\",\"id\":14}]"));

        assertEquals(1, built.specs().size());
        assertEquals("job_14", built.specs().getFirst().name());
        // The human name is what the model actually reads.
        assertTrue(built.specs().getFirst().description().contains("Nightly patch"));

        AgentToolbox.Tool tool = built.resolve("job_14");
        assertEquals("JOB", tool.type());
        assertEquals(14L, tool.targetId());
    }

    /** The hallucination case. Nothing outside the list may resolve, ever. */
    @Test
    void refusesANameThatWasNeverGranted() {
        when(toolTargets.findJob(TENANT, 14L))
                .thenReturn(Optional.of(new ToolTargetClient.Target(14L, PROJECT, "Nightly patch")));

        AgentToolbox.Toolbox built = toolbox.build(agent("[{\"type\":\"JOB\",\"id\":14}]"));

        assertNull(built.resolve("job_99"));
        assertNull(built.resolve("delete_everything"));
        assertNull(built.resolve("workflow_14"));
    }

    /**
     * The allow-list holds IDs, and a job can be moved to another project
     * after the agent was saved. Re-checking at run time is the difference
     * between a stale id and an agent operating something it was never granted.
     */
    @Test
    void dropsAJobThatHasMovedOutOfTheProject() {
        when(toolTargets.findJob(TENANT, 14L))
                .thenReturn(Optional.of(new ToolTargetClient.Target(14L, 999L, "Someone else's job")));

        AgentToolbox.Toolbox built = toolbox.build(agent("[{\"type\":\"JOB\",\"id\":14}]"));

        assertTrue(built.isEmpty());
        assertNull(built.resolve("job_14"));
        assertEquals(1, built.skipped().size());
    }

    @Test
    void dropsAJobThatNoLongerExists() {
        AgentToolbox.Toolbox built = toolbox.build(agent("[{\"type\":\"JOB\",\"id\":14}]"));

        assertTrue(built.isEmpty());
        assertFalse(built.skipped().isEmpty());
    }

    /**
     * The workflow's own description reaches the model.
     *
     * <p>This is the highest-leverage field on a tool — it is how the model
     * decides whether to reach for it at all. Shown only the title
     * "S3 Public Access Audit", a live agent refused a request to inventory
     * buckets, never learning that the audit returns every bucket and its
     * region. The title is not a description.
     */
    @Test
    void offersTheWorkflowsOwnDescriptionToTheModel() {
        when(toolTargets.findWorkflow(TENANT, 3L))
                .thenReturn(Optional.of(new ToolTargetClient.Target(3L, PROJECT,
                        "S3 Public Access Audit")));
        when(automations.workflowInputs(TENANT, 3L)).thenReturn(
                new AutomationClient.WorkflowInputs(List.of(), null,
                        "Lists every S3 bucket in the account and reports which are "
                                + "reachable by the public."));

        AgentToolbox.Toolbox built = toolbox.build(agent("[{\"type\":\"WORKFLOW\",\"id\":3}]"));

        String description = built.specs().getFirst().description();
        assertTrue(description.contains("Lists every S3 bucket"),
                "the model must be told what the automation returns");
        assertTrue(description.contains("S3 Public Access Audit"),
                "and which automation it is");
    }

    /** Pre-description workflows still get a usable tool, just a thinner one. */
    @Test
    void fallsBackToTheTitleWhenNoDescriptionWasPublished() {
        when(toolTargets.findWorkflow(TENANT, 3L))
                .thenReturn(Optional.of(new ToolTargetClient.Target(3L, PROJECT, "Reset password")));
        when(automations.workflowInputs(TENANT, 3L))
                .thenReturn(new AutomationClient.WorkflowInputs(List.of(), null, null));

        String description = toolbox.build(agent("[{\"type\":\"WORKFLOW\",\"id\":3}]"))
                .specs().getFirst().description();

        assertTrue(description.contains("Reset password"));
        assertTrue(description.contains("takes no arguments"));
    }

    /** A workflow's published form becomes the tool's arguments. */
    @Test
    void exposesAWorkflowsInputFormAsTheToolSchema() {
        when(toolTargets.findWorkflow(TENANT, 3L))
                .thenReturn(Optional.of(new ToolTargetClient.Target(3L, PROJECT, "Reset password")));
        when(automations.workflowInputs(TENANT, 3L)).thenReturn(
                new AutomationClient.WorkflowInputs(List.of(
                        new AutomationClient.InputField("samAccountName", "User", "text-input",
                                true, List.of()),
                        new AutomationClient.InputField("region", "Region", "select", false,
                                List.of("emea", "apac"))),
                        null));

        AgentToolbox.Toolbox built = toolbox.build(agent("[{\"type\":\"WORKFLOW\",\"id\":3}]"));

        Map<String, Object> schema = built.specs().getFirst().inputSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");

        assertTrue(properties.containsKey("samAccountName"));
        assertEquals(List.of("samAccountName"), schema.get("required"));

        @SuppressWarnings("unchecked")
        Map<String, Object> region = (Map<String, Object>) properties.get("region");
        // A select's options ARE the contract; stating them beats a failure
        // inside the engine that the model cannot act on.
        assertEquals(List.of("emea", "apac"), region.get("enum"));
    }

    /**
     * A workflow whose form cannot be read is left OUT rather than
     * offered. A tool the model can see is one it will eventually call, and
     * one that fails on every call burns steps and teaches it nothing.
     */
    @Test
    void dropsAWorkflowWhoseInputFormCannotBeRead() {
        when(toolTargets.findWorkflow(TENANT, 3L))
                .thenReturn(Optional.of(new ToolTargetClient.Target(3L, PROJECT, "Reset password")));
        when(automations.workflowInputs(TENANT, 3L)).thenReturn(
                new AutomationClient.WorkflowInputs(List.of(), "the form could not be read"));

        AgentToolbox.Toolbox built = toolbox.build(agent("[{\"type\":\"WORKFLOW\",\"id\":3}]"));

        assertTrue(built.isEmpty());
        assertTrue(built.skipped().getFirst().contains("the form could not be read"));
    }

    /** Corrupt JSON must leave the agent powerless, not unbounded. */
    @Test
    void aCorruptAllowListGrantsNothing() {
        assertTrue(toolbox.build(agent("{not json")).isEmpty());
        assertTrue(toolbox.build(agent(null)).isEmpty());
        assertTrue(toolbox.build(agent("\"JOB\"")).isEmpty());
    }

    // ----------------------------------------------- mutability, for phases ---

    /**
     * The default has to fall this way.
     *
     * <p>The Python runtime hides mutating tools from the phase that gathers
     * evidence. If an unmarked entry were assumed read-only, a destructive
     * automation nobody remembered to label would be handed to exactly the
     * phase the narrowing exists to protect. Assuming it mutates instead makes
     * a read-only tool invisible until someone labels it — the agent then says
     * it could not collect something, which is visible, harmless and a
     * one-line fix.
     */
    @Test
    void anUnmarkedToolIsTreatedAsMutating() {
        when(toolTargets.findJob(TENANT, 14L))
                .thenReturn(Optional.of(new ToolTargetClient.Target(14L, PROJECT, "Nightly patch")));

        AgentToolbox.Toolbox built = toolbox.build(agent("[{\"type\":\"JOB\",\"id\":14}]"));

        assertTrue(built.resolve("job_14").mutating());
        assertTrue(built.offered().getFirst().mutating());
    }

    @Test
    void anExplicitlyReadOnlyToolIsCarriedThroughAsSuch() {
        when(toolTargets.findWorkflow(TENANT, 3L))
                .thenReturn(Optional.of(new ToolTargetClient.Target(3L, PROJECT, "Health check")));

        AgentToolbox.Toolbox built = toolbox.build(
                agent("[{\"type\":\"WORKFLOW\",\"id\":3,\"mutating\":false}]"));

        assertFalse(built.resolve("workflow_3").mutating());
        assertFalse(built.offered().getFirst().mutating());
    }

    /**
     * The catalog ref survives delivery and reaches the runtime.
     *
     * <p>The name the model sees is {@code workflow_3} — a tenant-local number.
     * The runtime needs the ref to match a tool result back to what the agent's
     * author said that tool returns, and nothing else on the wire carries it.
     */
    @Test
    void theCatalogRefIsCarriedThroughToTheRuntime() {
        when(toolTargets.findWorkflow(TENANT, 3L))
                .thenReturn(Optional.of(new ToolTargetClient.Target(3L, PROJECT, "Health check")));

        AgentToolbox.Toolbox built = toolbox.build(agent(
                "[{\"type\":\"WORKFLOW\",\"id\":3,\"ref\":\"RD-136-idle-resource-inventory\"}]"));

        assertEquals("RD-136-idle-resource-inventory",
                built.offered().getFirst().spec().ref());
    }

    /**
     * An agent rolled out before refs were carried still runs.
     *
     * <p>It loses subject extraction, which is the safe way for this to be
     * wrong: a scope that enumerates nothing reaps nothing. Refusing the tool
     * instead would break every agent delivered before the field existed.
     */
    @Test
    void aToolWithNoRefIsStillOfferedWithoutOne() {
        when(toolTargets.findWorkflow(TENANT, 3L))
                .thenReturn(Optional.of(new ToolTargetClient.Target(3L, PROJECT, "Health check")));

        AgentToolbox.Toolbox built =
                toolbox.build(agent("[{\"type\":\"WORKFLOW\",\"id\":3}]"));

        assertEquals(1, built.offered().size());
        assertNull(built.offered().getFirst().spec().ref());
    }

    /**
     * A non-boolean {@code mutating} is not a value to coerce — it is a
     * corrupt row, and a corrupt row must not read as "safe to show while
     * diagnosing".
     */
    @Test
    void aMalformedMutatingFlagFallsBackToMutating() {
        when(toolTargets.findJob(TENANT, 14L))
                .thenReturn(Optional.of(new ToolTargetClient.Target(14L, PROJECT, "Nightly patch")));

        assertTrue(toolbox.build(agent("[{\"type\":\"JOB\",\"id\":14,\"mutating\":\"false\"}]"))
                .resolve("job_14").mutating());
        assertTrue(toolbox.build(agent("[{\"type\":\"JOB\",\"id\":14,\"mutating\":null}]"))
                .resolve("job_14").mutating());
    }

    /**
     * {@code offered()} must pair each spec with ITS OWN entry. A mismatch
     * here would be the worst kind of bug in this class: the runtime would
     * narrow correctly against the wrong answer.
     */
    @Test
    void offeredPairsEverySpecWithItsOwnMutability() {
        when(toolTargets.findJob(TENANT, 14L))
                .thenReturn(Optional.of(new ToolTargetClient.Target(14L, PROJECT, "Purge archives")));
        when(toolTargets.findWorkflow(TENANT, 3L))
                .thenReturn(Optional.of(new ToolTargetClient.Target(3L, PROJECT, "Health check")));

        AgentToolbox.Toolbox built = toolbox.build(agent(
                "[{\"type\":\"WORKFLOW\",\"id\":3,\"mutating\":false},"
                        + "{\"type\":\"JOB\",\"id\":14,\"mutating\":true}]"));

        Map<String, Boolean> byName = built.offered().stream().collect(
                java.util.stream.Collectors.toMap(tool -> tool.spec().name(),
                        com.intertec.autoops.agent.client.RuntimeClient.OfferedTool::mutating));

        assertEquals(Map.of("workflow_3", false, "job_14", true), byName);
    }
}
