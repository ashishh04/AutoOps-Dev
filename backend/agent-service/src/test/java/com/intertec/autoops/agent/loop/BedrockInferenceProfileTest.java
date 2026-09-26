package com.intertec.autoops.agent.loop;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which Bedrock model ids need rewriting before they can be invoked.
 *
 * <p>The newer Anthropic models cannot be called by their plain id at all.
 * Bedrock answers {@code Invocation of model ID anthropic.claude-opus-5 with
 * on-demand throughput isn't supported} — an instruction, not a fault: the same
 * model is reachable through a cross-region inference profile whose id is the
 * bare one with a geography prefix.
 *
 * <p>The two forms are NOT interchangeable, which is why this is a retry rather
 * than a prefix applied up front. The older models this platform also runs
 * have no profile and fail if handed one.
 */
class BedrockInferenceProfileTest {

    @Test
    @DisplayName("a bare model id gains its region's profile prefix")
    void bareIdGainsPrefix() {
        assertThat(BedrockChatModel.inferenceProfileId("anthropic.claude-opus-5", "us-east-1"))
                .isEqualTo("us.anthropic.claude-opus-5");
        assertThat(BedrockChatModel.inferenceProfileId("anthropic.claude-sonnet-5", "eu-west-1"))
                .isEqualTo("eu.anthropic.claude-sonnet-5");
    }

    @Test
    @DisplayName("Asia Pacific is apac, not ap")
    void apacIsNotAp() {
        // The one a reasonable person gets wrong from the region name alone.
        assertThat(BedrockChatModel.inferenceProfileId("anthropic.claude-opus-5", "ap-southeast-2"))
                .isEqualTo("apac.anthropic.claude-opus-5");
    }

    @Test
    @DisplayName("GovCloud keeps its own prefix rather than collapsing to us")
    void govCloudIsDistinct() {
        assertThat(BedrockChatModel.inferenceProfileId("anthropic.claude-opus-5", "us-gov-west-1"))
                .isEqualTo("us-gov.anthropic.claude-opus-5");
    }

    @Test
    @DisplayName("an id that already carries the prefix is not retried")
    void alreadyPrefixedIsNotDoubled() {
        // Retrying would produce us.us.anthropic..., and the second failure
        // would be about a profile that never existed rather than the real
        // problem.
        assertThat(BedrockChatModel.inferenceProfileId("us.anthropic.claude-opus-5", "us-east-1"))
                .isNull();
    }

    @Test
    @DisplayName("an unrecognised region does NOT get a guessed prefix")
    void unknownRegionIsNotGuessed() {
        // me-south-1 would become "me." from its first segment, which is not a
        // thing. The resulting error would send whoever reads it hunting for a
        // profile that was never going to exist — further from the truth, not
        // closer.
        assertThat(BedrockChatModel.inferenceProfileId("anthropic.claude-opus-5", "me-south-1"))
                .isNull();
        assertThat(BedrockChatModel.inferenceProfileId("anthropic.claude-opus-5", "sa-east-1"))
                .isNull();
    }

    @Test
    @DisplayName("no region means no retry")
    void missingRegionMeansNoRetry() {
        assertThat(BedrockChatModel.inferenceProfileId("anthropic.claude-opus-5", ""))
                .isNull();
        assertThat(BedrockChatModel.inferenceProfileId("anthropic.claude-opus-5", null))
                .isNull();
    }

    @Test
    @DisplayName("no model means no retry")
    void missingModelMeansNoRetry() {
        assertThat(BedrockChatModel.inferenceProfileId(null, "us-east-1")).isNull();
        assertThat(BedrockChatModel.inferenceProfileId("  ", "us-east-1")).isNull();
    }

    @Test
    @DisplayName("region matching is case- and whitespace-tolerant")
    void regionIsNormalised() {
        // It arrives from a credential field a human typed into a form.
        assertThat(BedrockChatModel.inferenceProfileId("anthropic.claude-opus-5", " US-East-1 "))
                .isEqualTo("us.anthropic.claude-opus-5");
    }
}
