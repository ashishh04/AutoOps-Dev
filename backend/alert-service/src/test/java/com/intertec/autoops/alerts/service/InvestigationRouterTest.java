package com.intertec.autoops.alerts.service;

import com.intertec.autoops.alerts.service.InvestigationRouter.Engine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which engine sees this estate.
 *
 * <p>Getting this wrong is not a routing inconvenience — it is the difference
 * between an investigation that reads CloudTrail and one that runs kubectl
 * against a cluster that does not exist and then writes a confident page of
 * "possible causes" with nothing behind it.
 */
class InvestigationRouterTest {

    @Test
    @DisplayName("a CloudWatch incident goes to the AWS analyst")
    void awsGoesToAgent() {
        assertThat(InvestigationRouter.route(List.of("cloudwatch"), true))
                .isEqualTo(Engine.AWS_AGENT);
    }

    @Test
    @DisplayName("a Prometheus incident goes to the investigation engine")
    void clusterGoesToHolmes() {
        assertThat(InvestigationRouter.route(List.of("prometheus"), true))
                .isEqualTo(Engine.HOLMES);
    }

    @Test
    @DisplayName("AWS wins a mixed incident")
    void awsWinsTheTie() {
        // An incident carrying both is far more often an AWS estate scraped by
        // Prometheus than a cluster that happens to emit CloudWatch — and the
        // agent degrades better, reporting the window it searched rather than
        // failing to reach a cluster that was never there.
        assertThat(InvestigationRouter.route(List.of("prometheus", "cloudwatch"), true))
                .isEqualTo(Engine.AWS_AGENT);
    }

    @Test
    @DisplayName("vendor naming variations still resolve")
    void namingVariationsResolve() {
        // Providers name themselves inconsistently across versions. A list that
        // only matches exact strings silently reroutes when a vendor renames.
        assertThat(InvestigationRouter.route(List.of("aws-cloudwatch"), true))
                .isEqualTo(Engine.AWS_AGENT);
        assertThat(InvestigationRouter.route(List.of("cloudwatch-metrics"), true))
                .isEqualTo(Engine.AWS_AGENT);
        assertThat(InvestigationRouter.route(List.of("CloudWatch"), true))
                .isEqualTo(Engine.AWS_AGENT);
    }

    @Test
    @DisplayName("with no engine configured, AWS incidents still investigate")
    void agentWorksWithoutHolmes() {
        // The agent needs no investigation engine at all — it runs on
        // agent-service. Investigation being "off" must not disable the half
        // that was never dependent on it.
        assertThat(InvestigationRouter.route(List.of("cloudwatch"), false))
                .isEqualTo(Engine.AWS_AGENT);
        assertThat(InvestigationRouter.route(List.of("prometheus"), false))
                .isEqualTo(Engine.AWS_AGENT);
    }

    @Test
    @DisplayName("an unrecognised source falls to the engine when one exists")
    void unknownFallsToHolmes() {
        assertThat(InvestigationRouter.route(List.of("some-new-vendor"), true))
                .isEqualTo(Engine.HOLMES);
        assertThat(InvestigationRouter.route(List.of(), true)).isEqualTo(Engine.HOLMES);
        assertThat(InvestigationRouter.route(null, true)).isEqualTo(Engine.HOLMES);
    }

    @Test
    @DisplayName("sources are read from either shape the engine reports")
    void sourcesReadFromEitherShape() {
        assertThat(InvestigationRouter.sourcesOf(Map.of("alert_sources", List.of("cloudwatch"))))
                .containsExactly("cloudwatch");
        assertThat(InvestigationRouter.sourcesOf(Map.of("sources", List.of("prometheus"))))
                .containsExactly("prometheus");
        assertThat(InvestigationRouter.sourcesOf(Map.of())).isEmpty();
        assertThat(InvestigationRouter.sourcesOf(null)).isEmpty();
    }

    @Test
    @DisplayName("a null inside the source list is not a crash")
    void nullSourceTolerated() {
        java.util.List<String> withNull = new java.util.ArrayList<>();
        withNull.add(null);
        withNull.add("cloudwatch");
        assertThat(InvestigationRouter.route(withNull, true)).isEqualTo(Engine.AWS_AGENT);
    }
}
