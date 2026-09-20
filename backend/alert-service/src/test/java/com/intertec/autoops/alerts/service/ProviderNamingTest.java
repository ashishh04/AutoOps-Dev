package com.intertec.autoops.alerts.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
}
