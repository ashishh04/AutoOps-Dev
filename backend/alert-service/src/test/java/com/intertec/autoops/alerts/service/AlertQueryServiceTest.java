package com.intertec.autoops.alerts.service;

import com.intertec.autoops.alerts.client.KeepApiClient;
import com.intertec.autoops.alerts.exception.AlertException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AlertQueryServiceTest {

    private final KeepApiClient engine = mock(KeepApiClient.class);
    private final ProviderCatalogService providers = mock(ProviderCatalogService.class);
    private final AlertQueryService service = new AlertQueryService(engine, providers);

    private static Jwt jwt(String tenantId, String role) {
        return Jwt.withTokenValue("t").header("alg", "RS256").subject("u")
                .claim("tenantId", tenantId).claim("role", role).build();
    }

    private static Map<String, Object> alert(String fingerprint, String tenant, String status) {
        Map<String, Object> a = new HashMap<>();
        a.put("fingerprint", fingerprint);
        a.put("status", status);
        a.put("severity", "critical");
        if (tenant != null) {
            a.put("labels", Map.of(TenantScope.TENANT_LABEL, tenant));
        }
        return a;
    }

    @Test
    @DisplayName("a list is scoped to the caller's own tenant")
    void listIsScoped() {
        when(engine.alerts()).thenReturn(List.of(
                alert("mine", "acme", "firing"),
                alert("theirs", "globex", "firing"),
                alert("nobodys", null, "firing")));

        var result = service.list(TenantScope.of(jwt("acme", "CLIENT"), null), null, null, 100);

        assertThat(result).extracting("fingerprint").containsExactly("mine");
    }

    @Test
    @DisplayName("the limit applies AFTER scoping, so a tenant never loses its own alerts to another's")
    void limitAppliesAfterScoping() {
        // 50 of someone else's alerts arrive first. With limit=1 applied before
        // the boundary the caller would get an empty page and conclude nothing
        // is wrong, which during an outage is the worst possible answer.
        List<Map<String, Object>> raw = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) {
            raw.add(alert("theirs-" + i, "globex", "firing"));
        }
        raw.add(alert("mine", "acme", "firing"));
        when(engine.alerts()).thenReturn(raw);

        var result = service.list(TenantScope.of(jwt("acme", "CLIENT"), null), null, null, 1);

        assertThat(result).extracting("fingerprint").containsExactly("mine");
    }

    @Test
    @DisplayName("status and severity filters are case-insensitive and optional")
    void filtersAreOptional() {
        when(engine.alerts()).thenReturn(List.of(
                alert("a", "acme", "firing"),
                alert("b", "acme", "resolved")));
        TenantScope scope = TenantScope.of(jwt("acme", "CLIENT"), null);

        assertThat(service.list(scope, "FIRING", null, 100)).hasSize(1);
        assertThat(service.list(scope, null, "Critical", 100)).hasSize(2);
        assertThat(service.list(scope, "", "", 100)).hasSize(2);
    }

    @Test
    @DisplayName("fetching another tenant's alert is 404, never 403")
    void foreignAlertIsNotFound() {
        // A 403 would confirm the fingerprint exists. That is the same probe
        // /api/hooks/{token} refuses to answer, and the same answer is given.
        when(engine.alert(anyString())).thenReturn(alert("theirs", "globex", "firing"));

        assertThatThrownBy(() -> service.get(TenantScope.of(jwt("acme", "CLIENT"), null), "theirs"))
                .isInstanceOf(AlertException.class)
                .extracting(e -> ((AlertException) e).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("incidents are PROVIDER-only")
    void incidentsAreProviderOnly() {
        assertThatThrownBy(() -> service.incidents(TenantScope.of(jwt("acme", "CLIENT"), null), 100))
                .isInstanceOf(AlertException.class)
                .extracting(e -> ((AlertException) e).getError())
                .isEqualTo("provider_only");
    }

    @Test
    @DisplayName("PROVIDER may read incidents")
    void providerReadsIncidents() {
        Map<String, Object> incident = new HashMap<>();
        incident.put("id", "inc-1");
        incident.put("user_generated_name", "Payments outage");
        when(engine.incidents()).thenReturn(List.of(incident));

        var result = service.incidents(TenantScope.of(jwt("provider", "PROVIDER"), null), 100);

        assertThat(result).extracting("name").containsExactly("Payments outage");
    }

    @Test
    @DisplayName("an empty engine is an empty list, not an error")
    void emptyIsEmpty() {
        when(engine.alerts()).thenReturn(List.of());
        assertThat(service.list(TenantScope.of(jwt("acme", "CLIENT"), null), null, null, 100)).isEmpty();
    }

    @Test
    @DisplayName("an alert with NO label is admitted if it arrived through a source this project connected")
    void ownedSourceAdmitsUnlabelledAlert() {
        // This is what makes the connect flow useful. Datadog has never heard
        // of an AutoOps project, so nothing stamps a label - but the source was
        // connected by exactly one tenant, and owning it is owning its alerts.
        Map<String, Object> a = new HashMap<>();
        a.put("fingerprint", "from-datadog");
        a.put("status", "firing");
        a.put("providerId", "prov-1");
        when(engine.alerts()).thenReturn(List.of(a));
        when(providers.connectedIds("acme", "7")).thenReturn(java.util.Set.of("prov-1"));

        var result = service.list(TenantScope.of(jwt("acme", "CLIENT"), 7L), null, null, 100);

        assertThat(result).extracting("fingerprint").containsExactly("from-datadog");
    }

    @Test
    @DisplayName("an alert from a source ANOTHER tenant connected stays invisible")
    void foreignSourceStaysInvisible() {
        Map<String, Object> a = new HashMap<>();
        a.put("fingerprint", "theirs");
        a.put("status", "firing");
        a.put("providerId", "prov-globex");
        when(engine.alerts()).thenReturn(List.of(a));
        when(providers.connectedIds("acme", "7")).thenReturn(java.util.Set.of("prov-1"));

        assertThat(service.list(TenantScope.of(jwt("acme", "CLIENT"), 7L), null, null, 100))
                .isEmpty();
    }

    @Test
    @DisplayName("if the source list cannot be read, the scope is NOT widened")
    void lookupFailureNeverWidens() {
        // Failing open here would show one tenant another's outage map the
        // moment the engine hiccuped.
        Map<String, Object> a = new HashMap<>();
        a.put("fingerprint", "unlabelled");
        a.put("status", "firing");
        a.put("providerId", "prov-1");
        when(engine.alerts()).thenReturn(List.of(a));
        when(providers.connectedIds("acme", "7"))
                .thenThrow(new IllegalStateException("engine down"));

        assertThat(service.list(TenantScope.of(jwt("acme", "CLIENT"), 7L), null, null, 100))
                .isEmpty();
    }

    @Test
    @DisplayName("at WORKSPACE level an unlabelled alert is still admitted, across every project")
    void workspaceScopeResolvesEveryOwnedSource() {
        // The bug this pins. enrich() used to give up when no project was
        // named, so the workspace view fell back to the label rule alone — and
        // most real alerts carry no label, because the monitoring tool that
        // raised them has never heard of AutoOps. They did not appear as
        // another tenant's; they appeared as not existing.
        Map<String, Object> datadog = new HashMap<>();
        datadog.put("fingerprint", "from-datadog");
        datadog.put("status", "firing");
        datadog.put("providerId", "prov-in-project-7");

        Map<String, Object> grafana = new HashMap<>();
        grafana.put("fingerprint", "from-grafana");
        grafana.put("status", "firing");
        grafana.put("providerId", "prov-in-project-9004");

        when(engine.alerts()).thenReturn(List.of(datadog, grafana));
        when(providers.connectedIds("acme", null))
                .thenReturn(java.util.Set.of("prov-in-project-7", "prov-in-project-9004"));

        var result = service.list(TenantScope.of(jwt("acme", "CLIENT"), null), null, null, 100);

        assertThat(result).extracting("fingerprint")
                .containsExactly("from-datadog", "from-grafana");
    }

    @Test
    @DisplayName("workspace level widens across the tenant's projects and no further")
    void workspaceScopeStopsAtTheTenant() {
        Map<String, Object> theirs = new HashMap<>();
        theirs.put("fingerprint", "globex-datadog");
        theirs.put("status", "firing");
        theirs.put("providerId", "prov-globex");
        when(engine.alerts()).thenReturn(List.of(theirs));
        when(providers.connectedIds("acme", null)).thenReturn(java.util.Set.of("prov-acme"));

        assertThat(service.list(TenantScope.of(jwt("acme", "CLIENT"), null), null, null, 100))
                .isEmpty();
    }

    @Test
    @DisplayName("a workspace-level alert opens rather than 404ing")
    void workspaceScopeOpensWhatItListed() {
        // The symptom a customer would actually hit: the feed shows the alert,
        // clicking it says it does not exist. Listing and opening must resolve
        // ownership the same way.
        Map<String, Object> a = new HashMap<>();
        a.put("fingerprint", "from-datadog");
        a.put("status", "firing");
        a.put("providerId", "prov-1");
        when(engine.alert("from-datadog")).thenReturn(a);
        when(providers.connectedIds("acme", null)).thenReturn(java.util.Set.of("prov-1"));

        assertThat(service.get(TenantScope.of(jwt("acme", "CLIENT"), null), "from-datadog")
                .fingerprint()).isEqualTo("from-datadog");
    }

    @Test
    @DisplayName("naming a project still NARROWS the workspace answer")
    void projectStillNarrows() {
        Map<String, Object> elsewhere = new HashMap<>();
        elsewhere.put("fingerprint", "other-project");
        elsewhere.put("status", "firing");
        elsewhere.put("providerId", "prov-in-project-9004");
        when(engine.alerts()).thenReturn(List.of(elsewhere));
        when(providers.connectedIds("acme", "7")).thenReturn(java.util.Set.of("prov-in-project-7"));

        assertThat(service.list(TenantScope.of(jwt("acme", "CLIENT"), 7L), null, null, 100))
                .isEmpty();
    }
}
