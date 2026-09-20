package com.intertec.autoops.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intertec.autoops.core.domain.Job;
import com.intertec.autoops.core.domain.ModelProvider;
import com.intertec.autoops.core.exception.CoreException;
import com.intertec.autoops.core.repo.JobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Telling a customer what a rolled-out workflow still needs — BEFORE they press
 * Run.
 *
 * <p>A rolled-out workflow arrives complete and looks identical to a ready one.
 * The provider built it against their workspace; this one has different AI
 * connections and different jobs. Without this the only way to find out was to
 * press Run and read a red error, which reads as "this automation is broken"
 * rather than "it is not set up yet" — a bad first impression of something
 * someone has just been given.
 */
class WorkflowReadinessTest {

    private static final String TENANT = "acme-corp-cafe0123";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ModelProviderService models;
    private JobRepository jobs;
    private WorkflowReadiness readiness;

    @BeforeEach
    void setUp() {
        models = mock(ModelProviderService.class);
        jobs = mock(JobRepository.class);
        readiness = new WorkflowReadiness(models, jobs, MAPPER);
    }

    private static final String NEEDS_MODEL = """
            {"nodes":[{"id":"start","type":"start"},
                      {"id":"w","type":"llm","prompt":[{"role":"user","text":"hi"}]},
                      {"id":"end","type":"end"}]}
            """;

    private void givenTenantDefault(String model) {
        when(models.defaultChatModel(TENANT)).thenReturn(model);
        when(models.resolveForModel(eq(TENANT), eq(model))).thenReturn(
                new ModelProviderService.ResolvedCredentials(
                        ModelProvider.Kind.BEDROCK, 1L, "Bedrock", model, Map.of()));
    }

    // ------------------------------------------------------------------- ready --

    @Test
    void aWorkflowWithEverythingItNeedsIsReady() {
        givenTenantDefault("deepseek.v3.2");

        WorkflowReadiness.Readiness result = readiness.check(TENANT, NEEDS_MODEL);

        assertThat(result.ready()).isTrue();
        assertThat(result.blockers()).isEmpty();
    }

    @Test
    void aWorkflowWithNoModelStepNeedsNoAiConnectionAtAll() {
        // A graph of job/http/template nodes does real work and needs no vendor.
        // Reporting a blocker here would stop a perfectly runnable automation.
        when(jobs.findByIdAndTenantId(eq(42L), eq(TENANT))).thenReturn(Optional.of(new Job()));

        WorkflowReadiness.Readiness result = readiness.check(TENANT, """
                {"nodes":[{"id":"start","type":"start"},
                          {"id":"patch","type":"job","target":"JOB","targetId":42},
                          {"id":"end","type":"end"}]}
                """);

        assertThat(result.ready()).isTrue();
    }

    // ---------------------------------------------------------------- blockers --

    @Test
    void anUnsetModelIsReportedWithTheScreenThatFixesIt() {
        // The whole point: the customer is told what to do, not that something
        // failed. A blocker with no action is a dead end.
        when(models.defaultChatModel(TENANT)).thenThrow(CoreException.badRequest(
                "no_default_model",
                "This workspace has not chosen a default AI model… Open Settings > AI "
                        + "Providers, pick a connection and set its default model."));

        WorkflowReadiness.Readiness result = readiness.check(TENANT, NEEDS_MODEL);

        assertThat(result.ready()).isFalse();
        WorkflowReadiness.Blocker blocker = result.blockers().getFirst();
        assertThat(blocker.kind()).isEqualTo("model_not_set");
        assertThat(blocker.title()).isEqualTo("Choose an AI model");
        assertThat(blocker.action()).isEqualTo("Open AI Providers");
        assertThat(blocker.href()).isNotBlank();
    }

    @Test
    void aModelThisWorkspaceCannotReachIsReportedByName() {
        // A customer's own workflow may legitimately name a model; a typo in it,
        // or a connection since removed, is exactly what this catches.
        when(models.defaultChatModel(anyString())).thenReturn("claude-fable-5");
        when(models.resolveForModel(anyString(), anyString())).thenThrow(
                CoreException.badRequest("model_not_available",
                        "No enabled AI connection in this workspace offers \"claude-fable-5\"."));

        WorkflowReadiness.Readiness result = readiness.check(TENANT, NEEDS_MODEL);

        assertThat(result.ready()).isFalse();
        assertThat(result.blockers().getFirst().kind()).isEqualTo("model_unavailable");
        assertThat(result.blockers().getFirst().title()).contains("claude-fable-5");
    }

    @Test
    void aJobTheWorkspaceDoesNotHaveIsReported() {
        // A rolled-out workflow carries the PROVIDER'S job ids, and those mean
        // nothing here. Catching it now turns "the automation failed halfway
        // through" into "this needs a job you do not have".
        givenTenantDefault("deepseek.v3.2");
        when(jobs.findByIdAndTenantId(anyLong(), anyString())).thenReturn(Optional.empty());

        WorkflowReadiness.Readiness result = readiness.check(TENANT, """
                {"nodes":[{"id":"start","type":"start"},
                          {"id":"patch","type":"job","title":"Patch the fleet",
                           "target":"JOB","targetId":900},
                          {"id":"end","type":"end"}]}
                """);

        assertThat(result.ready()).isFalse();
        WorkflowReadiness.Blocker blocker = result.blockers().getFirst();
        assertThat(blocker.kind()).isEqualTo("job_missing");
        assertThat(blocker.title()).contains("Patch the fleet");
        assertThat(blocker.detail()).contains("#900");
    }

    @Test
    void everyBlockerIsReportedAtOnce() {
        // One per save beats one per press. A customer fixing setup should see
        // the whole list, not discover the next one each time they retry.
        when(models.defaultChatModel(TENANT)).thenThrow(
                CoreException.badRequest("no_default_model", "nothing chosen"));
        when(jobs.findByIdAndTenantId(anyLong(), anyString())).thenReturn(Optional.empty());

        WorkflowReadiness.Readiness result = readiness.check(TENANT, """
                {"nodes":[{"id":"start","type":"start"},
                          {"id":"w","type":"llm","prompt":[{"role":"user","text":"hi"}]},
                          {"id":"patch","type":"job","target":"JOB","targetId":900},
                          {"id":"end","type":"end"}]}
                """);

        assertThat(result.blockers()).extracting(WorkflowReadiness.Blocker::kind)
                .containsExactlyInAnyOrder("model_not_set", "job_missing");
    }

    // ------------------------------------------------------------ not guessing --

    @Test
    void aWorkflowTargetIsNotClaimedToBeMissing() {
        // Workflow targets live in workflow-service, which this class does not
        // call: readiness must not fail because a peer is briefly down, and a
        // FALSE blocker is worse than none — the customer cannot overrule it.
        givenTenantDefault("deepseek.v3.2");

        WorkflowReadiness.Readiness result = readiness.check(TENANT, """
                {"nodes":[{"id":"start","type":"start"},
                          {"id":"sub","type":"job","target":"WORKFLOW","targetId":9225},
                          {"id":"end","type":"end"}]}
                """);

        assertThat(result.ready()).isTrue();
    }

    @Test
    void anUnreadableDefinitionBlamesTheProviderNotTheCustomer() {
        // There is no screen a customer can open to fix this, so offering them
        // a setup step would be a lie.
        WorkflowReadiness.Readiness result = readiness.check(TENANT, "not json");

        assertThat(result.ready()).isFalse();
        WorkflowReadiness.Blocker blocker = result.blockers().getFirst();
        assertThat(blocker.kind()).isEqualTo("definition_unreadable");
        assertThat(blocker.detail()).contains("Ask your provider");
        assertThat(blocker.action()).isNull();
    }
}
