package com.intertec.autoops.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intertec.autoops.core.client.WorkflowRuntimeClient;
import com.intertec.autoops.core.config.CoreProperties;
import com.intertec.autoops.core.domain.ModelProvider;
import com.intertec.autoops.core.exception.CoreException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WHICH model a workflow runs on, per workspace.
 *
 * <p>This is the SaaS half of the model story, and it was the half that was
 * missing. Credentials were always multi-tenant — {@code resolveForModel} takes
 * a tenantId and decrypts that workspace's own key. The MODEL CHOICE was not: a
 * workflow either named a literal id, which resolves only where a workspace
 * happens to hold a connection offering it, or fell through to
 * {@code RUNTIME_DEFAULT_MODEL} — one environment variable for the whole
 * deployment, the same string for every customer on the box.
 *
 * <p>For a provider-authored workflow rolled out to fifty workspaces that is
 * the difference between "each customer runs this on the vendor they pay for"
 * and "every customer is silently billed to the operator's account, sending
 * their data to a vendor they never chose".
 */
class WorkflowModelResolutionTest {

    private static final String TENANT = "acme-corp-cafe0123";
    /** The `platform` node reads a PROJECT's history, so a run now carries one. */
    private static final Long PROJECT = 9L;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WorkflowRuntimeClient runtime;
    private ModelProviderService models;
    private CoreProperties properties;
    private NativeWorkflowService service;

    @BeforeEach
    void setUp() {
        runtime = mock(WorkflowRuntimeClient.class);
        models = mock(ModelProviderService.class);
        properties = new CoreProperties();
        service = new NativeWorkflowService(runtime, models, properties, MAPPER);
    }

    /** A graph with one llm node, optionally naming a model. */
    private static String graph(String modelField) {
        return """
                {"nodes":[{"id":"start","type":"start"},
                          {"id":"w","type":"llm"%s,
                           "prompt":[{"role":"user","text":"hi"}]},
                          {"id":"end","type":"end"}]}
                """.formatted(modelField);
    }

    private void givenResolves(String model, ModelProvider.Kind kind) {
        when(models.resolveForModel(eq(TENANT), eq(model))).thenReturn(
                new ModelProviderService.ResolvedCredentials(
                        kind, 1L, kind.name(), model, Map.of("apiKey", "k")));
    }

    private String modelSentToRuntime() {
        ArgumentCaptor<WorkflowRuntimeClient.ModelBinding> binding =
                ArgumentCaptor.forClass(WorkflowRuntimeClient.ModelBinding.class);
        verify(runtime).run(any(), anyString(), any(), anyString(), any(), binding.capture(), any());
        return binding.getValue() == null ? null : binding.getValue().model();
    }

    // -------------------------------------------------------- resolution order --

    @Test
    void aModelNamedOnTheStepWins() {
        givenResolves("deepseek.v3.2", ModelProvider.Kind.BEDROCK);

        service.run(1L, TENANT, PROJECT, graph(",\"model\":\"deepseek.v3.2\""), Map.of(), null);

        assertThat(modelSentToRuntime()).isEqualTo("deepseek.v3.2");
    }

    @Test
    void naminigNothingUsesTHISWORKSPACESDefault() {
        // The case that did not exist. Before this, a workflow naming no model
        // fell straight through to one deployment-wide environment variable.
        when(models.defaultChatModel(TENANT)).thenReturn("gpt-4o-mini");
        givenResolves("gpt-4o-mini", ModelProvider.Kind.OPENAI);

        service.run(1L, TENANT, PROJECT, graph(""), Map.of(), null);

        assertThat(modelSentToRuntime()).isEqualTo("gpt-4o-mini");
    }

    @Test
    void theSentinelSaysPortableOutLoud() {
        // A provider-authored workflow rolled out to many workspaces cannot
        // name a literal id. Omitting `model` means the same thing, but a
        // designer that can SHOW the intent beats one where the absence of a
        // field is the intent.
        when(models.defaultChatModel(TENANT)).thenReturn("amazon.nova-pro-v1:0");
        givenResolves("amazon.nova-pro-v1:0", ModelProvider.Kind.BEDROCK);

        service.run(1L, TENANT, PROJECT, graph(",\"model\":\"@tenant-default\""), Map.of(), null);

        assertThat(modelSentToRuntime()).isEqualTo("amazon.nova-pro-v1:0");
    }

    @Test
    void twoWorkspacesRunTheSameWorkflowOnTheirOwnModels() {
        // The whole point. One definition, two customers, two vendors, two
        // bills — and neither one's data goes anywhere they did not choose.
        String other = "globex-inc-beef4567";
        when(models.defaultChatModel(TENANT)).thenReturn("gpt-4o-mini");
        when(models.defaultChatModel(other)).thenReturn("deepseek.v3.2");
        givenResolves("gpt-4o-mini", ModelProvider.Kind.OPENAI);
        when(models.resolveForModel(eq(other), eq("deepseek.v3.2"))).thenReturn(
                new ModelProviderService.ResolvedCredentials(
                        ModelProvider.Kind.BEDROCK, 2L, "Bedrock", "deepseek.v3.2",
                        Map.of("accessKeyId", "k")));

        String definition = graph("");
        service.run(1L, TENANT, PROJECT, definition, Map.of(), null);
        service.run(2L, other, PROJECT, definition, Map.of(), null);

        ArgumentCaptor<WorkflowRuntimeClient.ModelBinding> bindings =
                ArgumentCaptor.forClass(WorkflowRuntimeClient.ModelBinding.class);
        verify(runtime, org.mockito.Mockito.times(2))
                .run(any(), anyString(), any(), anyString(), any(), bindings.capture(), any());

        assertThat(bindings.getAllValues()).extracting(WorkflowRuntimeClient.ModelBinding::model)
                .containsExactly("gpt-4o-mini", "deepseek.v3.2");
        assertThat(bindings.getAllValues()).extracting(WorkflowRuntimeClient.ModelBinding::vendor)
                .containsExactly("OPENAI", "BEDROCK");
    }

    // --------------------------------------------------------------- fallbacks --

    @Test
    void theDeploymentDefaultAppliesOnlyWhenTheWorkspaceHasSaidNothing() {
        // A platform-wide fallback is a deployment convenience, never a
        // per-customer answer — so it is last, not first.
        when(models.defaultChatModel(TENANT)).thenThrow(
                CoreException.badRequest("no_default_model", "nothing chosen"));
        properties.getRuntime().setDefaultModel("gpt-4o-mini");
        givenResolves("gpt-4o-mini", ModelProvider.Kind.OPENAI);

        service.run(1L, TENANT, PROJECT, graph(""), Map.of(), null);

        assertThat(modelSentToRuntime()).isEqualTo("gpt-4o-mini");
    }

    @Test
    void withNoDefaultAnywhereTheWORKSPACESErrorIsTheOneShown() {
        // "Open Settings > AI Providers and pick a model" is actionable by the
        // person reading it. "Set RUNTIME_DEFAULT_MODEL" is an instruction for
        // someone with shell access to the deployment, which a customer is not.
        when(models.defaultChatModel(TENANT)).thenThrow(CoreException.badRequest(
                "no_default_model",
                "This workspace has not chosen a default AI model, so an automation that "
                        + "does not name one cannot run. Open Settings > AI Providers, pick "
                        + "a connection and set its default model."));

        assertThatThrownBy(() -> service.run(1L, TENANT, PROJECT, graph(""), Map.of(), null))
                .isInstanceOf(CoreException.class)
                .hasMessageContaining("Settings > AI Providers");
    }

    // ------------------------------------------------------------ no llm node --

    @Test
    void aGraphWithNoModelStepNeedsNoVendorAtAll() {
        // A graph of job/http/template/condition nodes does real work and needs
        // no AI connection. Demanding one would block a perfectly good workflow
        // behind a setup step it never uses.
        String noLlm = """
                {"nodes":[{"id":"start","type":"start"},
                          {"id":"patch","type":"job","target":"JOB","targetId":42},
                          {"id":"end","type":"end"}]}
                """;

        service.run(1L, TENANT, PROJECT, noLlm, Map.of(), null);

        assertThat(modelSentToRuntime()).isNull();
        org.mockito.Mockito.verify(models, org.mockito.Mockito.never())
                .defaultChatModel(anyString());
        org.mockito.Mockito.verify(models, org.mockito.Mockito.never())
                .resolveForModel(anyString(), anyString());
    }

    @Test
    void anUnusableWorkspaceModelFailsWithTheVendorsOwnWords() {
        when(models.defaultChatModel(TENANT)).thenReturn("claude-fable-5");
        when(models.resolveForModel(eq(TENANT), eq("claude-fable-5"))).thenThrow(
                CoreException.badRequest("model_not_available",
                        "No enabled AI connection in this workspace offers \"claude-fable-5\"."));

        assertThatThrownBy(() -> service.run(1L, TENANT, PROJECT, graph(""), Map.of(), null))
                .isInstanceOf(CoreException.class)
                .hasMessageContaining("claude-fable-5");
    }

    /** Unused here, but keeps the mock's signature honest if `run` gains a param. */
    @SuppressWarnings("unused")
    private void unusedSignatureGuard() {
        anyLong();
    }
}
