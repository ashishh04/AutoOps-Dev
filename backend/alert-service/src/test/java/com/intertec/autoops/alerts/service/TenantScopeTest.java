package com.intertec.autoops.alerts.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tenant boundary. One engine holds every customer's alerts, so these are
 * the tests that stand between tenant A and tenant B's outage map.
 */
class TenantScopeTest {

    private static Jwt jwt(String tenantId, String role) {
        Jwt.Builder builder = Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .subject("user-1")
                .claim("role", role);
        if (tenantId != null) {
            builder.claim("tenantId", tenantId);
        }
        return builder.build();
    }

    private static Map<String, Object> alert(Map<String, Object> labels) {
        Map<String, Object> alert = new HashMap<>();
        alert.put("fingerprint", "fp-1");
        if (labels != null) {
            alert.put("labels", labels);
        }
        return alert;
    }

    @Test
    @DisplayName("a tenant sees its own alerts")
    void tenantSeesOwn() {
        TenantScope scope = TenantScope.of(jwt("acme", "CLIENT"), null);
        assertThat(scope.admits(alert(Map.of(TenantScope.TENANT_LABEL, "acme")))).isTrue();
    }

    @Test
    @DisplayName("a tenant never sees another tenant's alerts")
    void tenantCannotSeeOthers() {
        TenantScope scope = TenantScope.of(jwt("acme", "CLIENT"), null);
        assertThat(scope.admits(alert(Map.of(TenantScope.TENANT_LABEL, "globex")))).isFalse();
    }

    @Test
    @DisplayName("an UNLABELLED alert belongs to nobody — fail closed")
    void unlabelledIsInvisibleToTenants() {
        TenantScope scope = TenantScope.of(jwt("acme", "CLIENT"), null);
        // This is the whole design decision. Until ingest stamps the label,
        // every alert in the engine looks like this — and the safe answer is
        // to show a customer nothing rather than show them everyone's.
        assertThat(scope.admits(alert(null))).isFalse();
        assertThat(scope.admits(alert(Map.of("env", "prod")))).isFalse();
    }

    @Test
    @DisplayName("a token with no tenantId claim sees nothing")
    void missingClaimSeesNothing() {
        TenantScope scope = TenantScope.of(jwt(null, "CLIENT"), null);
        assertThat(scope.admits(alert(Map.of(TenantScope.TENANT_LABEL, "acme")))).isFalse();
    }

    @Test
    @DisplayName("PROVIDER sees every tenant's alerts, labelled or not")
    void providerSeesAll() {
        TenantScope scope = TenantScope.of(jwt("provider", "PROVIDER"), null);
        assertThat(scope.admits(alert(Map.of(TenantScope.TENANT_LABEL, "globex")))).isTrue();
        assertThat(scope.admits(alert(null))).isTrue();
    }

    @Test
    @DisplayName("projectId narrows WITHIN a scope and never widens it")
    void projectNarrowsOnly() {
        // The tenant asks for project 7 but the alert is tenant globex's:
        // the project match must not rescue it.
        TenantScope scope = TenantScope.of(jwt("acme", "CLIENT"), 7L);
        assertThat(scope.admits(alert(Map.of(
                TenantScope.TENANT_LABEL, "globex",
                TenantScope.PROJECT_LABEL, "7")))).isFalse();
        assertThat(scope.admits(alert(Map.of(
                TenantScope.TENANT_LABEL, "acme",
                TenantScope.PROJECT_LABEL, "7")))).isTrue();
        assertThat(scope.admits(alert(Map.of(
                TenantScope.TENANT_LABEL, "acme",
                TenantScope.PROJECT_LABEL, "8")))).isFalse();
    }

    @Test
    @DisplayName("PROVIDER is still narrowed by projectId")
    void providerNarrowedByProject() {
        TenantScope scope = TenantScope.of(jwt("provider", "PROVIDER"), 7L);
        assertThat(scope.admits(alert(Map.of(TenantScope.PROJECT_LABEL, "7")))).isTrue();
        assertThat(scope.admits(alert(null))).isFalse();
    }

    @Test
    @DisplayName("a numeric project label matches a numeric project id")
    void numericLabelMatches() {
        // Labels come from whatever monitoring tool produced the alert. A
        // project id that arrived as JSON 7 must match one that arrived as "7",
        // or the boundary silently drops a tenant's own alerts.
        TenantScope scope = TenantScope.of(jwt("acme", "CLIENT"), 7L);
        Map<String, Object> labels = new HashMap<>();
        labels.put(TenantScope.TENANT_LABEL, "acme");
        labels.put(TenantScope.PROJECT_LABEL, 7);
        assertThat(scope.admits(alert(labels))).isTrue();
    }

    @Test
    @DisplayName("a labels field that is not a map is not a boundary bypass")
    void malformedLabelsAreRejected() {
        TenantScope scope = TenantScope.of(jwt("acme", "CLIENT"), null);
        Map<String, Object> alert = new HashMap<>();
        alert.put("labels", "autoops_tenant=acme");
        assertThat(scope.admits(alert)).isFalse();
    }
}
