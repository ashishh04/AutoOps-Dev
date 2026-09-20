package com.intertec.autoops.core.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intertec.autoops.core.config.CoreProperties;
import com.intertec.autoops.core.domain.Run;
import com.intertec.autoops.core.domain.RunTargetType;
import com.intertec.autoops.core.repo.RunRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Turning a run's validated inputs into the text a step actually executes.
 *
 * <p>The values reaching {@code resolve} have already passed
 * {@code NativeInputValidator}, so these tests are about faithful
 * substitution — and about the one case that must never be faithful: a
 * placeholder nothing filled in has to stop the run rather than travel to a
 * remote shell as literal text.
 */
class ExecutionEngineSubstitutionTest {

    @SuppressWarnings("unchecked")
    private ExecutionEngine engine() {
        ObjectProvider<Object> empty = mock(ObjectProvider.class);
        return new ExecutionEngine(mock(RunRepository.class), mock(StepExecutor.class),
                new ObjectMapper(), new CoreProperties(),
                mock(com.intertec.autoops.core.service.NativeWorkflowService.class),
                (ObjectProvider) empty, (ObjectProvider) empty, (ObjectProvider) empty);
    }

    private Run run(String definition, String inputs) {
        Run run = new Run();
        run.setTargetType(RunTargetType.WORKFLOW);
        run.setDefinition(definition);
        run.setInputs(inputs);
        return run;
    }

    private static final String NODES = """
            {"nodes":[{"type":"ssh","label":"Collect",
                       "value":"{{SshUser}}@{{TargetHost}} df -P"}]}
            """;

    @Test
    void substitutesEveryPlaceholderFromTheRunsInputs() {
        List<StepExecutor.RunStep> steps = engine().parseSteps(
                run(NODES, "{\"SshUser\":\"svc-autoops\",\"TargetHost\":\"app-01.local\"}"));

        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).raw().path("value").asText())
                .isEqualTo("svc-autoops@app-01.local df -P");
    }

    @Test
    void refusesToRunWhenAPlaceholderHasNoValue() {
        // The failure this whole mechanism exists to prevent: "{{TargetHost}}"
        // handed to ssh as a literal hostname.
        assertThatThrownBy(() -> engine().parseSteps(
                run(NODES, "{\"SshUser\":\"svc-autoops\"}")))
                .isInstanceOf(ExecutionEngine.UnresolvedInputException.class)
                .hasMessageContaining("TargetHost")
                .hasMessageContaining("Nothing was executed");
    }

    @Test
    void namesEveryMissingVariableOnce() {
        assertThatThrownBy(() -> engine().parseSteps(run(NODES, "{}")))
                .isInstanceOf(ExecutionEngine.UnresolvedInputException.class)
                .hasMessageContaining("SshUser")
                .hasMessageContaining("TargetHost");
    }

    @Test
    void leavesADefinitionWithoutPlaceholdersAlone() {
        List<StepExecutor.RunStep> steps = engine().parseSteps(run(
                "{\"nodes\":[{\"type\":\"ssh\",\"label\":\"Fixed\","
                        + "\"value\":\"svc@host uptime\"}]}", null));
        assertThat(steps.get(0).raw().path("value").asText()).isEqualTo("svc@host uptime");
    }

    @Test
    void substitutesEveryTextualField_notOnlyValue() {
        // A connection name or working directory is as likely to be
        // parameterised as the command itself.
        List<StepExecutor.RunStep> steps = engine().parseSteps(run(
                "{\"nodes\":[{\"type\":\"ssh\",\"label\":\"Go\",\"value\":\"svc@host x\","
                        + "\"connection\":\"conn-{{Region}}\"}]}",
                "{\"Region\":\"me-central-1\"}"));
        assertThat(steps.get(0).raw().path("connection").asText()).isEqualTo("conn-me-central-1");
    }

    @Test
    void aValueContainingDollarOrBackslashSurvivesIntact() {
        // Regex replacement treats $1 and \ specially; quoting them wrongly
        // would silently corrupt a password or a Windows path.
        List<StepExecutor.RunStep> steps = engine().parseSteps(run(
                "{\"nodes\":[{\"type\":\"command\",\"label\":\"Echo\","
                        + "\"value\":\"echo {{Secret}}\"}]}",
                "{\"Secret\":\"a$1b\\\\c\"}"));
        assertThat(steps.get(0).raw().path("value").asText()).isEqualTo("echo a$1b\\c");
    }

    @Test
    void numbersAndBooleansRenderAsTheirLiteralForm() {
        // "-Execute:$false" only works if false renders as false, not False.
        List<StepExecutor.RunStep> steps = engine().parseSteps(run(
                "{\"nodes\":[{\"type\":\"command\",\"label\":\"Run\","
                        + "\"value\":\"go -Age {{Days}} -Execute:${{Execute}}\"}]}",
                "{\"Days\":30,\"Execute\":false}"));
        assertThat(steps.get(0).raw().path("value").asText())
                .isEqualTo("go -Age 30 -Execute:$false");
    }

    @Test
    void jobStepsAreSubstitutedToo() {
        Run run = run("{\"steps\":[{\"type\":\"command\",\"label\":\"Ping\","
                + "\"value\":\"ping {{Host}}\"}]}", "{\"Host\":\"10.0.0.5\"}");
        run.setTargetType(RunTargetType.JOB);
        assertThat(engine().parseSteps(run).get(0).raw().path("value").asText())
                .isEqualTo("ping 10.0.0.5");
    }
}
