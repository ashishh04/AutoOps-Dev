package com.intertec.autoops.alerts.service;

import com.intertec.autoops.alerts.client.KeepApiClient;
import com.intertec.autoops.alerts.exception.AlertException;
import com.intertec.autoops.alerts.web.dto.ProviderFieldView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProviderCatalogServiceTest {

    private final KeepApiClient engine = mock(KeepApiClient.class);
    private final com.intertec.autoops.alerts.config.AlertProperties props =
            new com.intertec.autoops.alerts.config.AlertProperties();
    private final ProviderCatalogService service = new ProviderCatalogService(engine, props);

    private static Map<String, Object> datadog() {
        return Map.of(
                "type", "datadog",
                "display_name", "Datadog",
                "docs", "Pull/push alerts from Datadog.",
                "tags", List.of("alert", "topology", "data"),
                "categories", List.of("Monitoring"),
                "can_setup_webhook", true,
                "config", Map.of(
                        "api_key", Map.of("required", true, "description", "Datadog Api Key",
                                "sensitive", true, "default", ""),
                        "domain", Map.of("required", false, "description", "Datadog API domain",
                                "sensitive", false, "default", "https://api.datadoghq.com"),
                        "oauth_token", Map.of("required", false, "description", "OAuth",
                                "sensitive", true, "hidden", true, "default", "")),
                "scopes", List.of(Map.of("name", "events_read", "description",
                        "Read events data.", "mandatory", true)));
    }

    private static Map<String, Object> slack() {
        return Map.of("type", "slack", "display_name", "Slack",
                "tags", List.of("messaging"), "config", Map.of());
    }

    @Test
    @DisplayName("the WHOLE catalog is offered, with tags intact for the console to filter on")
    void wholeCatalogIsOffered() {
        // Filtering to the alert tag here silently hid two-way integrations a
        // customer legitimately wants - PagerDuty and Jira both raise and
        // receive. The tags travel instead, and the console decides.
        when(engine.providers()).thenReturn(Map.of("providers", List.of(datadog(), slack())));

        var catalog = service.catalog();

        assertThat(catalog).extracting("type").containsExactlyInAnyOrder("datadog", "slack");
        assertThat(catalog).filteredOn(t -> t.type().equals("datadog"))
                .allSatisfy(t -> assertThat(t.tags()).contains("alert"));
        assertThat(catalog).filteredOn(t -> t.type().equals("slack"))
                .allSatisfy(t -> assertThat(t.tags()).containsExactly("messaging"));
    }

    @Test
    @DisplayName("hidden fields are dropped, and required ones come first")
    void fieldsAreFilteredAndOrdered() {
        when(engine.providers()).thenReturn(Map.of("providers", List.of(datadog())));
        List<ProviderFieldView> fields = service.catalog().get(0).fields();

        assertThat(fields).extracting("name").containsExactly("api_key", "domain");
        assertThat(fields.get(0).required()).isTrue();
        assertThat(fields.get(0).sensitive()).isTrue();
        assertThat(fields.get(1).defaultValue()).isEqualTo("https://api.datadoghq.com");
    }

    @Test
    @DisplayName("connect sends only DECLARED fields, never arbitrary keys from the body")
    void connectFiltersUnknownKeys() {
        // Without the filter a caller could set pulling_enabled — or any other
        // engine-level switch — just by naming it in the form body.
        when(engine.providers()).thenReturn(Map.of("providers", List.of(datadog())));
        when(engine.installProvider(any())).thenReturn(Map.of("id", "p-1"));

        service.connect("acme", "7", "datadog", "Prod",
                Map.of("api_key", "k", "pulling_enabled", false, "provider_name", "pwned"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> sent = ArgumentCaptor.forClass(Map.class);
        verify(engine).installProvider(sent.capture());

        assertThat(sent.getValue()).doesNotContainKey("pulling_enabled");
        assertThat(sent.getValue()).containsEntry("api_key", "k");
        // provider_name is SET by us, from the computed scope — not taken from input.
        assertThat(sent.getValue().get("provider_name")).isEqualTo("autoops--acme--7--prod");
    }

    @Test
    @DisplayName("a missing required field is refused before anything reaches the engine")
    void requiredFieldEnforced() {
        when(engine.providers()).thenReturn(Map.of("providers", List.of(datadog())));
        assertThatThrownBy(() -> service.connect("acme", "7", "datadog", "Prod", Map.of()))
                .isInstanceOf(AlertException.class)
                .hasMessageContaining("Datadog Api Key");
    }

    @Test
    @DisplayName("an unknown provider type is refused")
    void unknownTypeRefused() {
        when(engine.providers()).thenReturn(Map.of("providers", List.of(datadog())));
        assertThatThrownBy(() -> service.connect("acme", "7", "nope", "X", Map.of()))
                .isInstanceOf(AlertException.class)
                .extracting(e -> ((AlertException) e).getError())
                .isEqualTo("unknown_provider");
    }

    @Test
    @DisplayName("only this project's connections are listed")
    void connectedIsScoped() {
        when(engine.providers()).thenReturn(Map.of(
                "providers", List.of(),
                "installed_providers", List.of(
                        Map.of("id", "mine", "type", "datadog",
                                "details", Map.of("name", "autoops--acme--7--prod")),
                        Map.of("id", "theirs", "type", "datadog",
                                "details", Map.of("name", "autoops--globex--7--prod")))));

        assertThat(service.connected("acme", "7")).extracting("id").containsExactly("mine");
        assertThat(service.connectedIds("acme", "7")).containsExactly("mine");
    }

    @Test
    @DisplayName("disconnecting someone else's connection is 404, never 403")
    void disconnectForeignIsNotFound() {
        when(engine.providers()).thenReturn(Map.of(
                "providers", List.of(),
                "installed_providers", List.of(Map.of("id", "theirs", "type", "datadog",
                        "details", Map.of("name", "autoops--globex--7--prod")))));

        assertThatThrownBy(() -> service.disconnect("acme", "7", "theirs"))
                .isInstanceOf(AlertException.class)
                .extracting(e -> ((AlertException) e).getError())
                .isEqualTo("provider_not_found");
    }

    // ---- ingest attribution ---------------------------------------------

    /**
     * Naming the connection an alert arrived through is what makes it
     * recognisable after the engine's own parser has discarded the labels
     * AutoOps stamped. Alertmanager nests its labels under alerts[].labels, so
     * the prometheus parser rebuilds the alert and the autoops_tenant stamp
     * does not survive — verified against a running engine. Without this the
     * alert belongs to nobody and the customer who sent it cannot see it.
     */
    @Test
    @DisplayName("an arriving alert is attributed to the connection this scope owns")
    void connectionIsResolvedForIngest() {
        when(engine.providers()).thenReturn(Map.of("installed_providers", List.of(
                Map.of("id", "prov-1", "type", "prometheus",
                        "name", ProviderNaming.qualify("acme", "7", "Prod Prometheus")))));

        assertThat(service.connectedIdFor("acme", "7", "prometheus")).isEqualTo("prov-1");
    }

    @Test
    @DisplayName("and never to another tenant's connection of the same type")
    void connectionIsNotBorrowedFromAnotherTenant() {
        // The failure that would matter: attributing acme's alert to globex's
        // Prometheus would put it in globex's feed.
        when(engine.providers()).thenReturn(Map.of("installed_providers", List.of(
                Map.of("id", "prov-globex", "type", "prometheus",
                        "name", ProviderNaming.qualify("globex", "7", "Their Prometheus")))));

        assertThat(service.connectedIdFor("acme", "7", "prometheus")).isNull();
    }

    @Test
    @DisplayName("nothing connected of that type resolves to null rather than a guess")
    void noConnectionResolvesToNull() {
        // A customer who took an ingest token from the setup panel without
        // connecting. The alert still arrives; it is simply attributed by
        // whatever labels survived instead.
        when(engine.providers()).thenReturn(Map.of("installed_providers", List.of(
                Map.of("id", "prov-1", "type", "datadog",
                        "name", ProviderNaming.qualify("acme", "7", "Prod Datadog")))));

        assertThat(service.connectedIdFor("acme", "7", "prometheus")).isNull();
        assertThat(service.connectedIdFor("acme", "7", null)).isNull();
    }
}
