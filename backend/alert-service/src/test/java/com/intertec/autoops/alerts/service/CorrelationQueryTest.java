package com.intertec.autoops.alerts.service;

import com.intertec.autoops.alerts.exception.AlertException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The expression a correlation rule matches on.
 *
 * <p>This class IS the tenant boundary for correlation. The engine evaluates
 * whatever CEL a rule carries against every alert that arrives, and it has no
 * tenant concept of its own — so an expression that omitted the tenant, or that
 * a caller could influence the shape of, would group two customers' alerts into
 * one incident. An incident is the one object in the alert plane carrying no
 * tenant label, so that mistake is not recoverable downstream.
 *
 * <p>Every test here is therefore about one of two things: the predicate is
 * always present, and nothing a caller sends can escape a string literal.
 */
class CorrelationQueryTest {

    private static final String ACME = "acme";

    // ---- the predicate is not optional -----------------------------------

    @Test
    @DisplayName("every expression begins with the caller's own tenant")
    void tenantIsAlwaysAnded() {
        String cel = CorrelationQuery.compile(ACME, List.of());

        assertThat(cel).isEqualTo("(labels.autoops_tenant == \"acme\")");
    }

    @Test
    @DisplayName("and survives every condition added on top of it")
    void tenantSurvivesConditions() {
        String cel = CorrelationQuery.compile(ACME, List.of(
                new CorrelationQuery.Condition("severity", List.of("critical", "high")),
                new CorrelationQuery.Condition("service", List.of("checkout-api"))));

        assertThat(cel).startsWith("(labels.autoops_tenant == \"acme\") && ");
        assertThat(cel).contains("(severity == \"critical\" || severity == \"high\")");
        assertThat(cel).contains("(service == \"checkout-api\")");
    }

    @Test
    @DisplayName("a rule with no tenant is refused, not written unscoped")
    void noTenantIsRefused() {
        // Without a predicate the rule correlates the whole engine. Defaulting
        // to "match nothing" would be safe and silent; refusing is safe and
        // loud, and this is a bug in a caller rather than a user mistake.
        assertThatThrownBy(() -> CorrelationQuery.compile(null, List.of()))
                .isInstanceOf(AlertException.class);
        assertThatThrownBy(() -> CorrelationQuery.compile("   ", List.of()))
                .isInstanceOf(AlertException.class);
    }

    @Test
    @DisplayName("the label path is derived from the label, so the two cannot drift")
    void pathMatchesTheLabelItAddresses() {
        // Drift here is silent: a rule addressing a path the alerts do not have
        // matches nothing, no incident is ever created, and nothing errors.
        assertThat(CorrelationQuery.compile(ACME, List.of()))
                .contains(TenantScope.TENANT_LABEL);
        assertThat(TenantScope.TENANT_LABEL_PATH)
                .isEqualTo("labels." + TenantScope.TENANT_LABEL);
    }

    // ---- nothing a caller sends becomes logic ----------------------------

    @Test
    @DisplayName("a value that would close the string literal is REFUSED, not escaped")
    void quotesCannotEscapeTheLiteral() {
        // The attack: a service value that ends the literal and continues the
        // expression — `x" || labels.autoops_tenant == "globex` would widen the
        // rule to another workspace. Refused by the declared pattern, which is
        // the injection control; escaping would be a second thing to get right.
        assertThatThrownBy(() -> CorrelationQuery.compile(ACME, List.of(
                new CorrelationQuery.Condition("service",
                        List.of("x\" || labels.autoops_tenant == \"globex")))))
                .isInstanceOf(AlertException.class);
    }

    @Test
    @DisplayName("a backslash is refused too")
    void backslashesAreRefused() {
        assertThatThrownBy(() -> CorrelationQuery.compile(ACME, List.of(
                new CorrelationQuery.Condition("service", List.of("bad\\\\value")))))
                .isInstanceOf(AlertException.class);
    }

    @Test
    @DisplayName("a caller cannot match on the tenant label itself")
    void tenantLabelIsNotAMatchableField() {
        // Allowing it would let a caller write their own scope — the one thing
        // this class exists to compute rather than accept.
        assertThatThrownBy(() -> CorrelationQuery.compile(ACME, List.of(
                new CorrelationQuery.Condition("labels.autoops_tenant", List.of("globex")))))
                .isInstanceOf(AlertException.class);
    }

    @Test
    @DisplayName("an unknown field is refused rather than passed through")
    void unknownFieldsAreRefused() {
        assertThatThrownBy(() -> CorrelationQuery.compile(ACME, List.of(
                new CorrelationQuery.Condition("fingerprint", List.of("x")))))
                .isInstanceOf(AlertException.class);
    }

    @Test
    @DisplayName("an empty condition is refused rather than read as 'match everything'")
    void emptyConditionIsRefused() {
        // The dangerous default. A condition the customer started and did not
        // finish must not silently widen the rule to the whole workspace.
        assertThatThrownBy(() -> CorrelationQuery.compile(ACME, List.of(
                new CorrelationQuery.Condition("service", List.of()))))
                .isInstanceOf(AlertException.class);
    }

    // ---- the field semantics the engine actually has ---------------------

    @Test
    @DisplayName("source is matched by membership, because an alert carries several")
    void sourceUsesMembership() {
        // `source == "prometheus"` would never match: the attribute is a LIST.
        // A rule that parses and matches nothing is the worst outcome here —
        // the customer sees no incidents and nothing logs an error.
        String cel = CorrelationQuery.compile(ACME, List.of(
                new CorrelationQuery.Condition("source", List.of("prometheus", "datadog"))));

        assertThat(cel).contains("(\"prometheus\" in source || \"datadog\" in source)");
    }

    @Test
    @DisplayName("severity is normalised and bounded to the ones the plane knows")
    void severityIsBounded() {
        String cel = CorrelationQuery.compile(ACME, List.of(
                new CorrelationQuery.Condition("severity", List.of("CRITICAL"))));
        assertThat(cel).contains("severity == \"critical\"");

        assertThatThrownBy(() -> CorrelationQuery.compile(ACME, List.of(
                new CorrelationQuery.Condition("severity", List.of("sev1")))))
                .isInstanceOf(AlertException.class);
    }

    @Test
    @DisplayName("duplicate values collapse instead of producing a repeated clause")
    void duplicatesCollapse() {
        String cel = CorrelationQuery.compile(ACME, List.of(
                new CorrelationQuery.Condition("severity", List.of("critical", "critical"))));

        assertThat(cel).contains("(severity == \"critical\")");
    }

    // ---- grouping --------------------------------------------------------

    @Test
    @DisplayName("grouping is allowlisted and de-duplicated")
    void groupingIsAllowlisted() {
        assertThat(CorrelationQuery.groupingCriteria(List.of("service", "SERVICE", "source")))
                .containsExactly("service", "source");

        assertThatThrownBy(() -> CorrelationQuery.groupingCriteria(List.of("labels")))
                .isInstanceOf(AlertException.class);
    }

    // ---- the stored definition -------------------------------------------

    @Test
    @DisplayName("the stored SQL does not pretend to be the matcher")
    void storedSqlIsInert() {
        // Verified against a running engine: a rule whose SQL was `1 = 0` still
        // correlated, because the CEL is what is evaluated. A SQL fragment that
        // LOOKED authoritative would be edited by the next person expecting an
        // effect, so it is deliberately a constant with the CEL beside it.
        String cel = CorrelationQuery.compile(ACME, List.of());
        var stored = CorrelationQuery.storedDefinition(cel);

        assertThat(stored.get("cel")).isEqualTo(cel);
        assertThat(String.valueOf(stored.get("sql"))).doesNotContain("autoops_tenant");
    }
}
