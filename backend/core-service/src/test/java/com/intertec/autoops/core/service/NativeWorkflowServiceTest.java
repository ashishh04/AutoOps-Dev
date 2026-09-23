package com.intertec.autoops.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intertec.autoops.core.client.WorkflowRuntimeClient;
import com.intertec.autoops.core.config.CoreProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Which definitions go to the native runtime.
 *
 * <p>This decision is load-bearing in a way that is easy to miss. If a graph is
 * NOT recognised, {@code ExecutionEngine} falls through to the step walker,
 * which looks for {@code steps[]}, finds nothing, and finishes the run
 * <b>SUCCEEDED having executed nothing</b>. A misclassification here does not
 * produce an error — it produces a green run that did no work, which is the
 * worst failure this codebase has.
 */
class NativeWorkflowServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private NativeWorkflowService service;

    @BeforeEach
    void setUp() {
        service = new NativeWorkflowService(mock(WorkflowRuntimeClient.class),
                mock(ModelProviderService.class), new CoreProperties(), MAPPER);
    }

    @Test
    void recognisesANativeGraph() {
        assertThat(service.isNative("""
                {"nodes":[{"id":"start","type":"start"},
                          {"id":"w","type":"llm"},
                          {"id":"end","type":"end"}]}
                """)).isTrue();
    }

    @Test
    void recognisesAGraphThatRUNSAnAutomation() {
        // The `job` node is what lets a workflow do rather than only decide.
        // It was added to the runtime's NodeType and NOT to this list, so a
        // workflow containing one stopped being native, fell through to the
        // step walker, and failed with "No executor for step type 'start'".
        // These two lists are coupled by hand; this is the case that says so.
        assertThat(service.isNative("""
                {"nodes":[{"id":"start","type":"start"},
                          {"id":"patch","type":"job","target":"JOB","targetId":42},
                          {"id":"end","type":"end"}]}
                """)).isTrue();
    }

    @Test
    void recognisesAGraphThatREADSThePlatformsOwnRecord() {
        // The SECOND time this list went stale, and the reason it is no longer
        // only a comment. `platform` was added to the runtime's NodeType and
        // not here, so RD-220 and RD-221 — the two workflows that read
        // AutoOps's own activity and open incidents, and the evidence source
        // behind the escalation agent — stopped being recognised as native.
        //
        // Both are real shipped artefacts, which is why this went unnoticed:
        // nothing in this module's tests reads them, and only the service
        // directory is mounted for the build, so nothing here can. The guard
        // that operates on the real list is `unsupportedNodeTypes`.
        assertThat(service.isNative("""
                {"nodes":[{"id":"start","type":"start"},
                          {"id":"ctx","type":"platform","source":"incidents"},
                          {"id":"end","type":"end"}]}
                """)).isTrue();
    }

    @Test
    void namesTheNodeTypesTheRuntimeCanRunAndThisServiceWouldNotDispatch() {
        // The whole failure above, reduced to one question that can be asked of
        // a live runtime: is there anything it executes that we would send to
        // the step walker? Reported to the designer so nobody can author one.
        assertThat(service.unsupportedNodeTypes(
                java.util.List.of("start", "llm", "platform", "end", "webhook", "sleep")))
                .containsExactly("sleep", "webhook");
    }

    @Test
    void saysNothingIsUnsupportedWhenEverythingTheRuntimeRunsIsKnown() {
        assertThat(service.unsupportedNodeTypes(
                java.util.List.of("start", "llm", "platform", "job", "http",
                        "template", "condition", "end")))
                .isEmpty();
    }

    @Test
    void doesNotReportDriftWhenTheRuntimeSimplyDidNotAnswer() {
        // "The runtime supports nothing" and "the runtime is unreachable" must
        // not produce the same output. An empty list read as total divergence
        // would put a red warning on the designer every time the runtime
        // restarted, and a warning that cries wolf is how the real one gets
        // ignored — which is the failure this whole check exists to catch.
        assertThat(service.unsupportedNodeTypes(java.util.List.of())).isEmpty();
        assertThat(service.unsupportedNodeTypes(null)).isEmpty();
    }

    @Test
    void doesNotClaimAJobsSteps() {
        // A job carries steps[], not nodes[].
        assertThat(service.isNative("""
                {"steps":[{"type":"powershell","label":"Patch","value":"Get-Date"}]}
                """)).isFalse();
    }

    @Test
    void doesNotClaimALegacyCanvasThatUsesNodesForSteps() {
        // The pre-existing workflow shape ALSO uses `nodes[]`, and its entries
        // also carry `type` — but they are step types, not graph node types.
        // Claiming one of these would send a runnable automation to a runtime
        // that cannot execute it.
        assertThat(service.isNative("""
                {"nodes":[{"type":"powershell","label":"Report","value":"./x.ps1"},
                          {"type":"ssh","label":"Restart","value":"host systemctl restart app"}]}
                """)).isFalse();
    }

    @Test
    void requiresAStartNodeSpecifically() {
        // Every graph node type is recognised here, but there is no start — so
        // this is not a runnable graph and must not be claimed.
        assertThat(service.isNative("""
                {"nodes":[{"id":"a","type":"template"},{"id":"end","type":"end"}]}
                """)).isFalse();
    }

    @Test
    void refusesAMixtureRatherThanGuessing() {
        // One unrecognised type means this is not ours. Running the graph nodes
        // and silently dropping the powershell one would be the "green run that
        // did no work" failure in its most deceptive form.
        assertThat(service.isNative("""
                {"nodes":[{"id":"start","type":"start"},
                          {"id":"p","type":"powershell","value":"Get-Date"},
                          {"id":"end","type":"end"}]}
                """)).isFalse();
    }

    @Test
    void doesNotClaimADifyBackedWorkflow() {
        assertThat(service.isNative("{\"difyWorkflow\":\"business-research\"}")).isFalse();
    }

    @Test
    void treatsAbsentAndUnreadableDefinitionsAsNotOurs() {
        assertThat(service.isNative(null)).isFalse();
        assertThat(service.isNative("")).isFalse();
        assertThat(service.isNative("{\"nodes\":[]}")).isFalse();
        // Contains the word "start" but is not JSON — the cheap string gate
        // must not be mistaken for the real check.
        assertThat(service.isNative("this will not start parsing")).isFalse();
    }
}
