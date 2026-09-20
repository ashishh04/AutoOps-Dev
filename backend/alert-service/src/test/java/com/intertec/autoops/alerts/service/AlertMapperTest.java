package com.intertec.autoops.alerts.service;

import com.intertec.autoops.alerts.web.dto.AlertView;
import com.intertec.autoops.alerts.web.dto.IncidentView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AlertMapperTest {

    @Test
    @DisplayName("maps an engine alert onto the AutoOps view")
    void mapsAlert() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("fingerprint", "fp-1");
        raw.put("name", "High CPU");
        raw.put("description", "cpu > 90% for 10m");
        raw.put("severity", "critical");
        raw.put("status", "firing");
        raw.put("source", List.of("prometheus"));
        raw.put("service", "payments");
        raw.put("environment", "prod");
        raw.put("lastReceived", "2026-09-18T10:00:00Z");
        raw.put("url", "https://grafana.example.com/d/abc");
        raw.put("labels", Map.of(TenantScope.PROJECT_LABEL, "7"));

        AlertView view = AlertMapper.alert(raw);

        assertThat(view.fingerprint()).isEqualTo("fp-1");
        assertThat(view.name()).isEqualTo("High CPU");
        assertThat(view.severity()).isEqualTo("critical");
        assertThat(view.source()).containsExactly("prometheus");
        assertThat(view.receivedAt()).isEqualTo("2026-09-18T10:00:00Z");
        assertThat(view.projectId()).isEqualTo("7");
    }

    @Test
    @DisplayName("the engine's own vocabulary never reaches the view")
    void doesNotLeakEngineFields() {
        // The named fields are the engine's internals. If one of them ever
        // appears as a component on the view, this fails — which is the point:
        // a leak should break a test, not ship and get noticed in dev tools.
        List<String> forbidden = List.of(
                "providerId", "providerType", "apiKeyRef", "enrichedFields",
                "isNoisy", "duplicateReason", "pushed", "eventId", "incident");
        List<String> components = java.util.Arrays.stream(AlertView.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
        assertThat(components).doesNotContainAnyElementsOf(forbidden);
    }

    @Test
    @DisplayName("a single-string source is accepted as well as a list")
    void toleratesScalarSource() {
        // Documented as a list; real monitoring payloads send one value.
        Map<String, Object> raw = new HashMap<>();
        raw.put("source", "datadog");
        assertThat(AlertMapper.alert(raw).source()).containsExactly("datadog");
    }

    @Test
    @DisplayName("a missing source is an empty list, never null")
    void missingSourceIsEmpty() {
        assertThat(AlertMapper.alert(new HashMap<>()).source()).isEmpty();
    }

    @Test
    @DisplayName("a human's name for an incident beats the generated one")
    void humanNameWins() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("id", "inc-1");
        raw.put("user_generated_name", "Payments outage");
        raw.put("ai_generated_name", "Cluster of 4 alerts on payments");
        raw.put("alerts_count", 4);
        raw.put("services", List.of("payments"));

        IncidentView view = AlertMapper.incident(raw);

        assertThat(view.name()).isEqualTo("Payments outage");
        assertThat(view.alertCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("falls back to the generated name when nobody has named it")
    void generatedNameFallback() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("id", "inc-1");
        raw.put("user_generated_name", "");
        raw.put("ai_generated_name", "Cluster of 4 alerts on payments");
        assertThat(AlertMapper.incident(raw).name()).isEqualTo("Cluster of 4 alerts on payments");
    }

    @Test
    @DisplayName("a missing alert count is zero, not a crash")
    void missingCountIsZero() {
        assertThat(AlertMapper.incident(new HashMap<>()).alertCount()).isZero();
    }
}
