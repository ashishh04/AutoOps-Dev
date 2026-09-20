package com.intertec.autoops.core.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intertec.autoops.core.client.WorkflowClient;
import com.intertec.autoops.core.service.ApprovalService;
import com.intertec.autoops.core.service.ApprovalSettingsService;
import com.intertec.autoops.core.service.RunService;
import com.intertec.autoops.core.repo.JobRepository;
import com.intertec.autoops.core.repo.RunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The input form a workflow declares for itself.
 *
 * <p>Before this existed the endpoint returned an empty field list for every
 * workflow, which handed the model a zero-argument tool
 * schema — a parameterised automation it had no way to parameterise. These
 * tests pin the translation from a workflow's {@code inputs[]} to the rows
 * agent-service and the console both read.
 *
 * <p>No Spring context: the collaborators are mocked because what is under
 * test is the reading of a definition, not the transport that fetched it.
 */
class InternalAgentDispatchControllerInputsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A native definition: nodes to execute, plus the form that drives them. */
    private static final String NATIVE_DEFINITION = """
            {"nodes":[{"type":"ssh","label":"Collect health"}],
             "inputs":[
               {"variable":"TargetHost","label":"Target host","type":"string","required":true,
                "pattern":"^[a-z0-9.-]+$","placeholder":"app-prod-01","help":"One host per run."},
               {"variable":"DiskWarnPercent","label":"Disk warning (%)","type":"number",
                "required":true,"min":50,"max":99,"default":85},
               {"variable":"IncludeSwap","label":"Include swap","type":"boolean",
                "required":false,"default":true},
               {"variable":"OutputFormat","label":"Output format","type":"select",
                "required":true,"options":["Console","JSON"],"default":"JSON"}
             ]}
            """;

    private WorkflowClient workflowClient;
    private InternalAgentDispatchController controller;

    @BeforeEach
    void setUp() {
        workflowClient = mock(WorkflowClient.class);
        controller = new InternalAgentDispatchController(
                mock(JobRepository.class), mock(RunRepository.class), mock(RunService.class),
                mock(ApprovalService.class), mock(ApprovalSettingsService.class),
                workflowClient, MAPPER);
    }

    private void givenDefinition(String definition) {
        when(workflowClient.require(anyString(), anyLong()))
                .thenReturn(new WorkflowClient.WorkflowView(
                        7L, "tenant-a", 3L, "Linux Server Health Check",
                        definition, 1, true));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fieldsFor(String definition) {
        givenDefinition(definition);
        return (List<Map<String, Object>>)
                controller.workflowInputs("tenant-a", 7L).get("fields");
    }

    @Test
    void readsEveryDeclaredFieldInOrder() {
        assertThat(fieldsFor(NATIVE_DEFINITION))
                .extracting(f -> f.get("variable"))
                .containsExactly("TargetHost", "DiskWarnPercent", "IncludeSwap", "OutputFormat");
    }

    @Test
    void carriesTheFiveKeysAgentServiceReads() {
        Map<String, Object> host = fieldsFor(NATIVE_DEFINITION).get(0);
        assertThat(host)
                .containsEntry("variable", "TargetHost")
                .containsEntry("label", "Target host")
                .containsEntry("type", "string")
                .containsEntry("required", true)
                .containsEntry("options", List.of());
    }

    @Test
    void carriesTheConstraintsOnlyTheConsoleNeeds() {
        // These drive the operator's form. agent-service ignores what it does
        // not recognise, so one declaration serves both sides.
        assertThat(fieldsFor(NATIVE_DEFINITION).get(1))
                .containsEntry("min", 50)
                .containsEntry("max", 99)
                .containsEntry("default", 85);
        assertThat(fieldsFor(NATIVE_DEFINITION).get(0))
                .containsEntry("pattern", "^[a-z0-9.-]+$")
                .containsEntry("help", "One host per run.");
    }

    @Test
    void selectOptionsSurviveAsAList() {
        // They become the enum in the model's tool schema, so losing them
        // would let the model invent a value the automation rejects.
        assertThat(fieldsFor(NATIVE_DEFINITION).get(3))
                .containsEntry("options", List.of("Console", "JSON"));
    }

    @Test
    void booleanTypeIsPreservedNotStringified() {
        assertThat(fieldsFor(NATIVE_DEFINITION).get(2))
                .containsEntry("type", "boolean")
                .containsEntry("default", true);
    }

    @Test
    void keysTheAuthorDidNotSetAreLeftOutEntirely() {
        // Absent is not the same as empty: a field with no pattern must not
        // arrive carrying one that matches nothing.
        assertThat(fieldsFor(NATIVE_DEFINITION).get(2))
                .doesNotContainKeys("pattern", "min", "max", "placeholder");
    }

    @Test
    void aWorkflowThatDeclaresNoInputsHasNoForm() {
        assertThat(fieldsFor("{\"nodes\":[{\"type\":\"ssh\"}]}")).isEmpty();
    }

    @Test
    void unnamedFieldsAreDropped() {
        // Nothing to pass it as; an unlabelled box is worse than no box.
        assertThat(fieldsFor("""
                {"nodes":[],"inputs":[{"label":"Orphan","type":"string","required":true},
                                      {"variable":"Kept","label":"Kept","type":"string","required":false}]}
                """))
                .extracting(f -> f.get("variable"))
                .containsExactly("Kept");
    }

    @Test
    void anUnparseableDefinitionYieldsNoFormRatherThanAnError() {
        // The workflow may still run perfectly well on its defaults.
        assertThat(fieldsFor("{not json at all")).isEmpty();
    }

    @Test
    void anInputsKeyThatIsNotAnArrayIsIgnored() {
        assertThat(fieldsFor("{\"nodes\":[],\"inputs\":\"tomorrow\"}")).isEmpty();
    }

    @Test
    void missingTypeAndLabelFallBackToSafeDefaults() {
        Map<String, Object> field = fieldsFor(
                "{\"nodes\":[],\"inputs\":[{\"variable\":\"Bare\"}]}").get(0);
        assertThat(field)
                .containsEntry("type", "string")
                .containsEntry("label", "Bare")
                .containsEntry("required", false);
    }

    @Test
    void reportsTheWorkflowItAnsweredFor() {
        givenDefinition(NATIVE_DEFINITION);
        assertThat(controller.workflowInputs("tenant-a", 7L))
                .containsEntry("workflowId", 7L)
                .containsEntry("name", "Linux Server Health Check")
                .doesNotContainKey("error");
    }
}
