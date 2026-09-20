package com.intertec.autoops.alerts.service;

import com.intertec.autoops.alerts.web.dto.AlertView;
import com.intertec.autoops.alerts.web.dto.IncidentView;

import java.util.List;
import java.util.Map;

/**
 * Engine JSON in, AutoOps types out.
 *
 * <p>An allow-list by construction: every field on the view is named here, so
 * a field the engine adds in a future version cannot arrive in a browser
 * without someone deciding it should. The inverse — copying the map and
 * deleting what we do not want — fails open on exactly that upgrade.
 */
public final class AlertMapper {

    private AlertMapper() {
    }

    public static AlertView alert(Map<String, Object> raw) {
        return new AlertView(
                str(raw.get("fingerprint")),
                str(raw.get("name")),
                str(raw.get("description")),
                str(raw.get("severity")),
                str(raw.get("status")),
                strings(raw.get("source")),
                str(raw.get("service")),
                str(raw.get("environment")),
                str(raw.get("lastReceived")),
                str(raw.get("startedAt")),
                str(raw.get("url")),
                TenantScope.label(raw, TenantScope.PROJECT_LABEL));
    }

    public static IncidentView incident(Map<String, Object> raw) {
        // The engine may name an incident itself when correlation produces one
        // with no human involved. A person's name for it always wins.
        String name = str(raw.get("user_generated_name"));
        if (name == null || name.isBlank()) {
            name = str(raw.get("ai_generated_name"));
        }
        String summary = str(raw.get("user_summary"));
        if (summary == null || summary.isBlank()) {
            summary = str(raw.get("generated_summary"));
        }
        return new IncidentView(
                str(raw.get("id")),
                name,
                summary,
                str(raw.get("severity")),
                str(raw.get("status")),
                count(raw.get("alerts_count")),
                strings(raw.get("services")),
                str(raw.get("start_time")),
                str(raw.get("last_seen_time")));
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static int count(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    /**
     * {@code source} and {@code services} are lists on the engine, but a single
     * string is what arrives when a monitoring tool sends one value. Both are
     * accepted rather than only the documented one — the alternative is a
     * ClassCastException on a real customer's payload.
     */
    private static List<String> strings(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().filter(java.util.Objects::nonNull).map(String::valueOf).toList();
        }
        if (value instanceof String s && !s.isBlank()) {
            return List.of(s);
        }
        return List.of();
    }
}
