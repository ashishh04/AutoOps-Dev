package com.intertec.autoops.alerts.service;

import com.intertec.autoops.alerts.exception.AlertException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The tenant boundary for connected monitoring sources. One engine holds every
 * customer's providers, so a name that can be forged is a boundary that can be
 * crossed.
 */
class ProviderNamingTest {

    private static final String ACME = "acme";
    private static final String GLOBEX = "globex";

    @Test
    @DisplayName("a provider belongs to the scope that created it")
    void belongsToOwnScope() {
        String name = ProviderNaming.qualify(ACME, "7", "Production Datadog");
        assertThat(ProviderNaming.belongsTo(name, ACME, "7")).isTrue();
    }

    @Test
    @DisplayName("and to no other tenant or project")
    void belongsToNobodyElse() {
        String name = ProviderNaming.qualify(ACME, "7", "Production Datadog");
        assertThat(ProviderNaming.belongsTo(name, GLOBEX, "7")).isFalse();
        assertThat(ProviderNaming.belongsTo(name, ACME, "8")).isFalse();
    }

    @Test
    @DisplayName("a hostile LABEL cannot forge another scope's prefix")
    void labelCannotForgeAScope() {
        // The attack: name your own connection so that its full engine name
        // starts with someone else's prefix. Collapsing repeated separators is
        // what defeats it — the "--" never survives.
        String hostile = ProviderNaming.qualify(GLOBEX, "1", "--acme--7--mine");
        assertThat(ProviderNaming.belongsTo(hostile, ACME, "7")).isFalse();
        assertThat(ProviderNaming.belongsTo(hostile, GLOBEX, "1")).isTrue();
    }

    @Test
    @DisplayName("a hostile TENANT id cannot forge another scope's prefix")
    void tenantIdCannotForgeAScope() {
        String hostile = ProviderNaming.qualify("acme--7--x", "1", "mine");
        assertThat(ProviderNaming.belongsTo(hostile, ACME, "7")).isFalse();
    }

    @Test
    @DisplayName("single-hyphen tenant ids do not collide across project boundaries")
    void noCollisionWithHyphenatedTenants() {
        // Real tenant ids look like intertec-systems-1542f8a3. With a ONE
        // character separator, tenant "a" project "1-b" and tenant "a-1"
        // project "b" produce the same prefix and share an alert stream.
        String a = ProviderNaming.prefix("a", "1-b");
        String b = ProviderNaming.prefix("a-1", "b");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("the label round-trips back to something the customer recognises")
    void labelRoundTrips() {
        String name = ProviderNaming.qualify(ACME, "7", "Production Datadog");
        assertThat(ProviderNaming.label(name, ACME, "7")).isEqualTo("production-datadog");
    }

    @Test
    @DisplayName("an empty or punctuation-only name is refused, not silently accepted")
    void emptyNameRefused() {
        assertThat(ProviderNaming.sanitize("!!!", 64)).isEmpty();
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> ProviderNaming.qualify(ACME, "7", "!!!"))
                .hasMessageContaining("name");
    }

    @Test
    @DisplayName("a null name is not a crash")
    void nullNameIsHandled() {
        assertThat(ProviderNaming.belongsTo(null, ACME, "7")).isFalse();
    }

    // ---- workspace scope -----------------------------------------------
    //
    // A customer connects Datadog once and reads its alerts from the workspace,
    // rather than repeating the connection in every project. That means a
    // prefix with no project segment — which is only safe because the tenant
    // segment is still compared in full.

    @Test
    @DisplayName("a null project matches every source this tenant connected")
    void tenantWideMatchesOwnProjects() {
        assertThat(ProviderNaming.belongsTo(
                ProviderNaming.qualify(ACME, "7", "Production Datadog"), ACME, null)).isTrue();
        assertThat(ProviderNaming.belongsTo(
                ProviderNaming.qualify(ACME, "9004", "Staging Grafana"), ACME, null)).isTrue();
    }

    @Test
    @DisplayName("and still matches no other tenant's")
    void tenantWideStopsAtTheTenant() {
        String theirs = ProviderNaming.qualify(GLOBEX, "7", "Production Datadog");
        assertThat(ProviderNaming.belongsTo(theirs, ACME, null)).isFalse();
    }

    @Test
    @DisplayName("a tenant whose id PREFIXES another's does not absorb it")
    void tenantWideIsNotAStringPrefix() {
        // The case a bare startsWith would get wrong. Without the trailing
        // separator, "acme" would match every source belonging to "acme-corp".
        String longer = ProviderNaming.qualify("acme-corp", "7", "Their Datadog");
        assertThat(ProviderNaming.belongsTo(longer, ACME, null)).isFalse();
        assertThat(ProviderNaming.belongsTo(longer, "acme-corp", null)).isTrue();
    }

    @Test
    @DisplayName("a BLANK project is rejected rather than read as the whole workspace")
    void blankProjectIsNotTenantWide() {
        // Blank is what an empty form field or a stringified absent value looks
        // like. Reading it as "everything" would widen a scope by accident.
        String name = ProviderNaming.qualify(ACME, "7", "Production Datadog");
        assertThatThrownBy(() -> ProviderNaming.belongsTo(name, ACME, ""))
                .isInstanceOf(AlertException.class);
    }

    @Test
    @DisplayName("a workspace-level label drops the project segment too")
    void tenantWideLabelIsWhatTheCustomerTyped() {
        String name = ProviderNaming.qualify(ACME, "9004", "Production Datadog");
        assertThat(ProviderNaming.label(name, ACME, null)).isEqualTo("production-datadog");
        assertThat(ProviderNaming.label(name, ACME, "9004")).isEqualTo("production-datadog");
    }

    @Test
    @DisplayName("the owning project is readable back out of the name")
    void projectIsRecoverable() {
        // The workspace list shows sources from several projects together, so
        // each row has to say where it lives — and there is no row in any
        // AutoOps table for a connected source, only this name.
        String name = ProviderNaming.qualify(ACME, "9004", "Production Datadog");
        assertThat(ProviderNaming.projectOf(name, ACME)).isEqualTo("9004");
    }

    @Test
    @DisplayName("and is null for a name belonging to someone else")
    void projectOfRefusesForeignNames() {
        String theirs = ProviderNaming.qualify(GLOBEX, "7", "Production Datadog");
        assertThat(ProviderNaming.projectOf(theirs, ACME)).isNull();
        assertThat(ProviderNaming.projectOf("some-unmanaged-provider", ACME)).isNull();
    }
}
