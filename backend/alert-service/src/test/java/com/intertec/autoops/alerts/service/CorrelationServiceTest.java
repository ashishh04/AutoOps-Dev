package com.intertec.autoops.alerts.service;

import com.intertec.autoops.alerts.client.IncidentEngineClient;
import com.intertec.autoops.alerts.exception.AlertException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Correlation rules a tenant owns, inside an engine that has no tenants.
 *
 * <p>The same problem connected sources have, solved the same way: the rule's
 * NAME is computed and carries the scope, so ownership is a prefix comparison
 * rather than a row anybody could read. What is tested here is that a caller
 * can neither see nor delete a rule outside their own scope, and that what
 * reaches the engine always carries the tenant predicate.
 */
class CorrelationServiceTest {

    private final IncidentEngineClient engine = mock(IncidentEngineClient.class);
    private final CorrelationService service = new CorrelationService(engine);

    private static Map<String, Object> rule(String id, String name) {
        Map<String, Object> r = new HashMap<>();
        r.put("id", id);
        r.put("name", name);
        r.put("grouping_criteria", List.of("service"));
        r.put("timeframe", 600);
        r.put("definition_cel", "(labels.autoops_tenant == \"acme\")");
        r.put("creation_time", "2026-09-24T10:00:00");
        return r;
    }

    private static CorrelationService.Draft draft() {
        return new CorrelationService.Draft("Payments outage",
                List.of(new CorrelationQuery.Condition("severity", List.of("critical"))),
                List.of("service"), 600);
    }

    // ---- ownership -------------------------------------------------------

    @Test
    @DisplayName("a tenant sees only the rules its own scope created")
    void listIsScopedByName() {
        when(engine.rules()).thenReturn(List.of(
                rule("r1", ProviderNaming.qualify("acme", "7", "Payments outage")),
                rule("r2", ProviderNaming.qualify("globex", "7", "Their rule")),
                // Written by an operator directly in the engine. Not ours to
                // list, and definitely not ours to delete.
                rule("r3", "hand-written-operator-rule")));

        var mine = service.list("acme", null);

        assertThat(mine).extracting("id").containsExactly("r1");
    }

    @Test
    @DisplayName("the label shown is the customer's, without the scoping prefix")
    void labelIsTheCustomersOwn() {
        when(engine.rules()).thenReturn(List.of(
                rule("r1", ProviderNaming.qualify("acme", "9004", "Payments outage"))));

        var mine = service.list("acme", null);

        assertThat(mine.get(0).label()).isEqualTo("payments-outage");
        // Workspace-level, so each row says which project owns it.
        assertThat(mine.get(0).projectId()).isEqualTo("9004");
    }

    @Test
    @DisplayName("naming a project narrows the list")
    void projectNarrows() {
        when(engine.rules()).thenReturn(List.of(
                rule("r1", ProviderNaming.qualify("acme", "7", "In seven")),
                rule("r2", ProviderNaming.qualify("acme", "9004", "In nine"))));

        assertThat(service.list("acme", "7")).extracting("id").containsExactly("r1");
    }

    @Test
    @DisplayName("deleting another tenant's rule is a 404, and never reaches the engine")
    void cannotDeleteAnotherTenantsRule() {
        // 404 rather than 403: a 403 confirms the id exists. And the engine
        // call must not happen at all — proving ownership by attempting the
        // delete and seeing if it works is not proving ownership.
        when(engine.rules()).thenReturn(List.of(
                rule("r2", ProviderNaming.qualify("globex", "7", "Their rule"))));

        assertThatThrownBy(() -> service.delete("acme", null, "r2"))
                .isInstanceOf(AlertException.class)
                .hasMessageContaining("No such correlation rule");

        verify(engine, never()).deleteRule(any());
    }

    @Test
    @DisplayName("deleting an operator's hand-written rule is refused too")
    void cannotDeleteAnUnscopedRule() {
        // An operator's own rule carries no AutoOps prefix. A tenant deleting
        // one would silently stop correlation for the whole platform.
        when(engine.rules()).thenReturn(List.of(rule("r3", "hand-written-operator-rule")));

        assertThatThrownBy(() -> service.delete("acme", null, "r3"))
                .isInstanceOf(AlertException.class);
        verify(engine, never()).deleteRule(any());
    }

    @Test
    @DisplayName("deleting its own rule works")
    void deletesItsOwn() {
        when(engine.rules()).thenReturn(List.of(
                rule("r1", ProviderNaming.qualify("acme", "7", "Payments outage"))));

        service.delete("acme", null, "r1");

        verify(engine).deleteRule("r1");
    }

    // ---- what reaches the engine -----------------------------------------

    @Test
    @DisplayName("the rule sent to the engine is named and scoped by AutoOps")
    void createdRuleIsScoped() {
        when(engine.rules()).thenReturn(List.of());
        when(engine.createRule(any())).thenAnswer(inv ->
                rule("new", ProviderNaming.qualify("acme", "7", "Payments outage")));

        service.create("acme", "7", draft());

        ArgumentCaptor<Map<String, Object>> sent = ArgumentCaptor.forClass(Map.class);
        verify(engine).createRule(sent.capture());

        assertThat(sent.getValue().get("ruleName"))
                .isEqualTo("autoops--acme--7--payments-outage");
        assertThat(String.valueOf(sent.getValue().get("celQuery")))
                .startsWith("(labels.autoops_tenant == \"acme\")");
        assertThat(sent.getValue().get("groupingCriteria")).isEqualTo(List.of("service"));
        // "any", so one alert opens the incident and later ones join it. "all"
        // would hold an outage invisible until every grouping value was seen.
        assertThat(sent.getValue().get("createOn")).isEqualTo("any");
    }

    @Test
    @DisplayName("a rule with nothing to group by is refused")
    void groupingIsRequired() {
        // Without it the engine has no notion of "the same incident", and every
        // matching alert becomes its own — a feed with extra steps.
        when(engine.rules()).thenReturn(List.of());

        assertThatThrownBy(() -> service.create("acme", "7",
                new CorrelationService.Draft("x", List.of(), List.of(), 600)))
                .isInstanceOf(AlertException.class)
                .hasMessageContaining("same incident");
        verify(engine, never()).createRule(any());
    }

    @Test
    @DisplayName("a rule must name the project it belongs to")
    void projectIsRequiredToCreate() {
        // The name is the only ownership record, and there is nowhere to put a
        // workspace-wide rule that would still be recognisable as one.
        assertThatThrownBy(() -> service.create("acme", null, draft()))
                .isInstanceOf(AlertException.class);
        assertThatThrownBy(() -> service.create("acme", "  ", draft()))
                .isInstanceOf(AlertException.class);
        verify(engine, never()).createRule(any());
    }

    @Test
    @DisplayName("the window is clamped rather than rejected")
    void windowIsClamped() {
        when(engine.rules()).thenReturn(List.of());
        when(engine.createRule(any())).thenAnswer(inv ->
                rule("new", ProviderNaming.qualify("acme", "7", "x")));

        service.create("acme", "7", new CorrelationService.Draft("x", List.of(),
                List.of("service"), 1));

        ArgumentCaptor<Map<String, Object>> sent = ArgumentCaptor.forClass(Map.class);
        verify(engine).createRule(sent.capture());
        // A one-second window would declare an incident before its siblings
        // arrived, which is a grouping of one wearing an incident's name.
        assertThat(sent.getValue().get("timeframeInSeconds")).isEqualTo(60);
    }

    @Test
    @DisplayName("a workspace is capped, because every rule runs against every alert")
    void ruleCountIsCapped() {
        List<Map<String, Object>> many = new java.util.ArrayList<>();
        for (int i = 0; i < 25; i++) {
            many.add(rule("r" + i, ProviderNaming.qualify("acme", "7", "rule-" + i)));
        }
        when(engine.rules()).thenReturn(many);

        assertThatThrownBy(() -> service.create("acme", "7", draft()))
                .isInstanceOf(AlertException.class)
                .hasMessageContaining("at most");
        verify(engine, never()).createRule(any());
    }

    @Test
    @DisplayName("the cap counts the WORKSPACE, not the project")
    void capIsPerTenantNotPerProject() {
        // Per-project would let one workspace with twenty projects put five
        // hundred expressions in front of every alert the engine receives.
        List<Map<String, Object>> many = new java.util.ArrayList<>();
        for (int i = 0; i < 25; i++) {
            many.add(rule("r" + i, ProviderNaming.qualify("acme", "9004", "rule-" + i)));
        }
        when(engine.rules()).thenReturn(many);

        assertThatThrownBy(() -> service.create("acme", "7", draft()))
                .isInstanceOf(AlertException.class);
    }
}
