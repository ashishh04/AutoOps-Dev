package com.intertec.autoops.alerts.service;

import com.intertec.autoops.alerts.client.KeepApiClient;
import com.intertec.autoops.alerts.exception.AlertException;
import com.intertec.autoops.alerts.web.dto.ConnectedProviderView;
import com.intertec.autoops.alerts.web.dto.ProviderFieldView;
import com.intertec.autoops.alerts.web.dto.ProviderScopeView;
import com.intertec.autoops.alerts.web.dto.ProviderSetupView;
import com.intertec.autoops.alerts.web.dto.ProviderTypeView;
import com.intertec.autoops.alerts.config.AlertProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The connect flow: what a customer can hook up, and doing it.
 *
 * <p>The WHOLE catalog is offered, not just the alert-tagged subset. Many of
 * these integrations are two-way — PagerDuty and Jira both raise and receive,
 * Grafana is tagged for topology and data as well as alerts — so filtering on
 * one tag silently hid sources a customer legitimately wants. The tags travel
 * with each entry instead, and the console filters on them, which keeps the
 * choice in the operator's hands rather than in this method.
 */
@Service
public class ProviderCatalogService {

    private final KeepApiClient engine;
    private final AlertProperties properties;

    public ProviderCatalogService(KeepApiClient engine, AlertProperties properties) {
        this.engine = engine;
        this.properties = properties;
    }

    /**
     * The setup guide for one source, scoped to this project.
     *
     * <p>The token is minted here rather than stored: it encodes the scope and
     * is signed, so the same project always gets the same value and nothing has
     * to remember it. Showing it is safe in the way showing a webhook URL is
     * safe — it authorises writing alerts INTO this project and nothing else.
     */
    public ProviderSetupView setup(String tenantId, String projectId, String type) {
        ProviderTypeView spec = catalog().stream()
                .filter(t -> t.type().equals(type))
                .findFirst()
                .orElseThrow(() -> AlertException.badRequest("unknown_provider",
                        "That kind of monitoring source is not available."));

        String token = IngestToken.mint(properties.getIngest().getSecret(),
                tenantId, projectId, type);
        String url = properties.getIngest().getPublicBaseUrl()
                .replaceAll("/+$", "") + "/api/alerts/ingest/" + type;

        String instructions = "";
        try {
            instructions = SetupInstructions.rewrite(engine.webhookMarkdown(type), url, token);
        } catch (RuntimeException ex) {
            // A source with no guide is normal; a broken one must not take the
            // whole dialog down, because the URL and token above are still
            // everything a determined operator needs.
            instructions = "";
        }
        if (SetupInstructions.leaks(instructions)) {
            // Belt and braces. The rewrite already drops offending lines, so
            // reaching here means a shape nobody anticipated - show nothing
            // rather than show the engine.
            instructions = "";
        }

        // Nothing to authenticate AND nothing to poll means the only way alerts
        // arrive is if the customer configures the push. The console uses this
        // to refuse to call it "connected" before they have.
        boolean pushOnly = spec.fields().stream().noneMatch(ProviderFieldView::required);
        return new ProviderSetupView(url, token, instructions, pushOnly);
    }

    public List<ProviderTypeView> catalog() {
        Map<String, Object> body = engine.providers();
        return list(body, "providers").stream()
                .map(ProviderCatalogService::toType)
                .sorted(Comparator.comparing(ProviderTypeView::displayName,
                        String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /** What THIS project has connected. Scoped by computed name, never by input. */
    public List<ConnectedProviderView> connected(String tenantId, String projectId) {
        return connectedRaw(tenantId, projectId).stream()
                .map(p -> new ConnectedProviderView(
                        str(p.get("id")),
                        str(p.get("type")),
                        ProviderNaming.label(str(p.get("details") instanceof Map<?, ?> d
                                ? d.get("name") : p.get("name")), tenantId, projectId),
                        projectId,
                        str(p.get("last_alert_received"))))
                .toList();
    }

    /**
     * The ids of every source this scope owns.
     *
     * <p>This is what lets an alert be recognised as a tenant's own even though
     * nothing stamped a label on it: it arrived through a provider only that
     * tenant connected. See {@code TenantScope}.
     */
    public Set<String> connectedIds(String tenantId, String projectId) {
        return connectedRaw(tenantId, projectId).stream()
                .map(p -> str(p.get("id")))
                .filter(id -> id != null && !id.isBlank())
                .collect(java.util.stream.Collectors.toSet());
    }

    private List<Map<String, Object>> connectedRaw(String tenantId, String projectId) {
        Map<String, Object> body = engine.providers();
        List<Map<String, Object>> installed = new ArrayList<>(list(body, "installed_providers"));
        installed.addAll(list(body, "linked_providers"));
        return installed.stream()
                .filter(p -> ProviderNaming.belongsTo(nameOf(p), tenantId, projectId))
                .toList();
    }

    /**
     * Connects a source.
     *
     * <p>The engine payload is FLAT — {@code provider_id}, {@code provider_name}
     * and the credentials all at the top level — and the credentials are passed
     * through only after being filtered against the type's declared fields. An
     * unfiltered spread would let a caller set {@code pulling_enabled}, or any
     * other engine-level switch, by naming it in the form body.
     */
    public ConnectedProviderView connect(String tenantId, String projectId,
                                         String type, String label,
                                         Map<String, Object> config) {
        ProviderTypeView spec = catalog().stream()
                .filter(t -> t.type().equals(type))
                .findFirst()
                .orElseThrow(() -> AlertException.badRequest("unknown_provider",
                        "That kind of monitoring source is not available."));
        if (spec.comingSoon()) {
            throw AlertException.badRequest("provider_coming_soon",
                    spec.displayName() + " cannot be connected yet.");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        for (ProviderFieldView f : spec.fields()) {
            Object value = config.get(f.name());
            String text = value == null ? "" : String.valueOf(value).trim();
            if (text.isEmpty()) {
                if (f.required()) {
                    throw AlertException.badRequest("missing_field",
                            f.label() + " is required.");
                }
                continue;
            }
            payload.put(f.name(), text);
        }
        payload.put("provider_id", type);
        payload.put("provider_type", type);
        payload.put("provider_name", ProviderNaming.qualify(tenantId, projectId, label));

        Map<String, Object> created = engine.installProvider(payload);
        return new ConnectedProviderView(
                str(created == null ? null : created.get("id")),
                type,
                ProviderNaming.sanitize(label, 64),
                projectId,
                null);
    }

    /** Disconnect. The id must already belong to the caller's scope. */
    public void disconnect(String tenantId, String projectId, String id) {
        Map<String, Object> owned = connectedRaw(tenantId, projectId).stream()
                .filter(p -> id.equals(str(p.get("id"))))
                .findFirst()
                // 404, not 403: confirming the id exists would leak that another
                // workspace has it connected.
                .orElseThrow(() -> AlertException.notFound("provider_not_found",
                        "No such connection."));
        engine.deleteProvider(str(owned.get("type")), id);
    }

    // ---- mapping -------------------------------------------------------

    static ProviderTypeView toType(Map<String, Object> raw) {
        return new ProviderTypeView(
                str(raw.get("type")),
                str(raw.get("display_name")),
                str(raw.get("docs")),
                strings(raw.get("categories")),
                strings(raw.get("tags")),
                bool(raw.get("can_setup_webhook")),
                bool(raw.get("webhook_required")),
                bool(raw.get("coming_soon")),
                fields(raw.get("config")),
                scopes(raw.get("scopes")));
    }

    /**
     * Hidden fields are dropped, not rendered disabled. They exist for the
     * engine's own OAuth handoff ("use the UI for this flow"), and a customer
     * who cannot fill one in should not be shown a box that makes them think
     * they must.
     */
    static List<ProviderFieldView> fields(Object config) {
        if (!(config instanceof Map<?, ?> map)) {
            return List.of();
        }
        List<ProviderFieldView> out = new ArrayList<>();
        map.forEach((k, v) -> {
            if (!(v instanceof Map<?, ?> f) || bool(f.get("hidden"))) {
                return;
            }
            String name = String.valueOf(k);
            out.add(new ProviderFieldView(
                    name,
                    str(f.get("description")) == null ? name : str(f.get("description")),
                    str(f.get("hint")),
                    bool(f.get("required")),
                    bool(f.get("sensitive")),
                    str(f.get("default")),
                    str(f.get("validation"))));
        });
        // Required first, so the shortest path to a working connection is the
        // top of the form.
        out.sort(Comparator.comparing(ProviderFieldView::required).reversed());
        return out;
    }

    static List<ProviderScopeView> scopes(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<ProviderScopeView> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> s) {
                out.add(new ProviderScopeView(
                        str(s.get("name")),
                        str(s.get("description")),
                        bool(s.get("mandatory"))));
            }
        }
        return out;
    }

    private static String nameOf(Map<String, Object> p) {
        Object details = p.get("details");
        if (details instanceof Map<?, ?> d && d.get("name") != null) {
            return String.valueOf(d.get("name"));
        }
        return str(p.get("name"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static boolean bool(Object v) {
        return v instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(v));
    }

    private static List<String> strings(Object v) {
        if (v instanceof List<?> l) {
            return l.stream().filter(java.util.Objects::nonNull).map(String::valueOf).toList();
        }
        return List.of();
    }
}
