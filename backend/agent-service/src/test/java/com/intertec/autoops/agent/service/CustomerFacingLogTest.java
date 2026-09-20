package com.intertec.autoops.agent.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a customer is allowed to read when an automation fails.
 *
 * <p>The input here is verbatim from agent run 28 on 2026-09-20, which put
 * Rundeck node ids, an execution id, the internal project slug carrying the
 * tenant id, and three Java data-context class names into a customer-facing
 * timeline. The one line worth reading was the fourth of eleven.
 */
class CustomerFacingLogTest {

    private static final String RAW = """
            [1/1] CloudWatch Alarm State Audit — FAILED: Step failed on 1 node: 16c360fd08d2 (4919ms)
              | No AWS credentials reached this step. Connect an AWS account to this project.
              | Result: 1
              | Failed: NonZeroResultCode: Result code was 1
              | Execution failed: 104 in project autoops-intertec-systems-1542f8a3-2: \
            [Workflow result: , step failures: {1=Dispatch failed on 1 nodes: \
            [16c360fd08d2: NonZeroResultCode: Result code was 1 + \
            {dataContext=MultiDataContextImpl(map={ContextView(node:16c360fd08d2)=\
            BaseDataContext{{exec={exitCode=1}}}, base=null)} ]}, status: failed]
            """;

    @Test
    void keepsWhatTheScriptItselfSaid() {
        assertThat(CustomerFacingLog.clean(RAW))
                .contains("No AWS credentials reached this step.")
                .contains("CloudWatch Alarm State Audit");
    }

    @Test
    void dropsEveryInternalIdentifier() {
        String clean = CustomerFacingLog.clean(RAW);
        assertThat(clean)
                .as("node id, execution id and the slug carrying the tenant id")
                .doesNotContain("16c360fd08d2")
                .doesNotContain("Execution failed: 104")
                .doesNotContain("autoops-intertec-systems-1542f8a3-2");
    }

    @Test
    void dropsTheEnginesOwnVocabulary() {
        String clean = CustomerFacingLog.clean(RAW);
        assertThat(clean)
                .doesNotContain("NonZeroResultCode")
                .doesNotContain("MultiDataContextImpl")
                .doesNotContain("BaseDataContext")
                .doesNotContain("ContextView")
                .doesNotContain("dataContext=");
    }

    @Test
    void theStepHeaderSurvivesWithoutTheNodeItRanOn() {
        // The customer wants to know WHICH step failed. Which runner executed
        // it is an internal address and means nothing to them.
        assertThat(CustomerFacingLog.clean(RAW))
                .contains("[1/1] CloudWatch Alarm State Audit")
                .doesNotContain("Step failed on 1 node");
    }

    @Test
    void aSuccessfulLogIsLeftAlone() {
        String output = """
                CLOUDWATCH ALARM STATE AUDIT
                region=us-east-1 lookback_hours=24 alarms_examined=13
                app-prod-cpu  OK       last changed 2026-09-19T22:04:11
                """;
        assertThat(CustomerFacingLog.clean(output).strip()).isEqualTo(output.strip());
    }

    @Test
    void nothingLeftIsEmptyRatherThanWhitespace() {
        // The caller prints no log section at all in this case; returning a
        // blank string rather than a newline is what lets it tell.
        assertThat(CustomerFacingLog.clean("  | Result: 1\n  | Failed: NonZeroResultCode: x"))
                .isEmpty();
        assertThat(CustomerFacingLog.clean(null)).isEmpty();
        assertThat(CustomerFacingLog.clean("   ")).isEmpty();
    }
}
