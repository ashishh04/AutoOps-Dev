package com.intertec.autoops.alerts.service;

import com.intertec.autoops.alerts.exception.AlertException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns a customer's grouping choices into the expression the engine matches on.
 *
 * <h2>Why a compiler and not a text box</h2>
 * The engine's rules are CEL, and CEL is evaluated against every alert that
 * arrives. Letting a customer type one would mean a tenant authoring the
 * expression that decides which alerts they see — they could write
 * {@code true}, or an expression naming another workspace's label, and the
 * boundary would be gone. So the customer picks from a fixed vocabulary and
 * <b>this class writes the CEL</b>.
 *
 * <p>That is the same rule the rest of the platform follows: a caller supplies
 * values, never an expression, and the values are checked against a declared
 * pattern before they are ever interpolated. {@link #VALUE} is that pattern
 * here, and it is what makes string concatenation into CEL safe — a value that
 * could close a quote is rejected, not escaped.
 *
 * <h2>The tenant predicate is not optional and not the caller's</h2>
 * Every expression this produces begins with the caller's own tenant, ANDed in
 * by {@link #compile}. There is no argument that omits it and no branch that
 * skips it, because a correlation rule that matched across tenants would group
 * two customers' alerts into one incident — and an incident is the one object
 * in the alert plane that carries no tenant label of its own.
 */
public final class CorrelationQuery {

    /**
     * What a value may contain before it is interpolated into CEL.
     *
     * <p>No quote, no backslash, no parenthesis, no whitespace-only. Service
     * names, source names and severities are all identifiers in practice; a
     * value outside this set is a request this platform does not understand
     * rather than one to sanitise into something it did not mean.
     */
    private static final Pattern VALUE = Pattern.compile("[A-Za-z0-9 ._:/-]{1,120}");

    /** The severities the alert plane recognises, lowercased on the way in. */
    private static final Set<String> SEVERITIES =
            Set.of("critical", "high", "warning", "info", "low");

    /**
     * The alert attributes a customer may match on or group by.
     *
     * <p>An allowlist rather than a shape check, because CEL will happily
     * evaluate {@code labels.autoops_tenant} and a customer naming that field
     * would be writing their own scope. The three here are the ones an operator
     * actually groups by, and none of them is a boundary.
     */
    private static final Set<String> FIELDS = Set.of("severity", "service", "source");

    private CorrelationQuery() {
    }

    /**
     * One condition: an attribute, and the values that satisfy it.
     *
     * @param field  one of {@link #FIELDS}
     * @param values any of these matches; an empty list is rejected rather than
     *               treated as "match everything", which is the difference
     *               between a narrow rule and one that groups the workspace
     */
    public record Condition(String field, List<String> values) {
    }

    /**
     * The CEL an engine rule should carry.
     *
     * @param tenantId   the caller's own tenant, from the JWT and never a
     *                   parameter the caller controls
     * @param conditions what the customer chose; may be empty, meaning "every
     *                   alert in this workspace"
     */
    public static String compile(String tenantId, List<Condition> conditions) {
        if (tenantId == null || tenantId.isBlank()) {
            // Not a validation nicety. Without a tenant there is no predicate
            // to AND in, and the rule would correlate the whole engine.
            throw AlertException.badRequest("missing_tenant",
                    "A correlation rule must belong to a workspace.");
        }
        StringBuilder cel = new StringBuilder();
        cel.append('(').append(TenantScope.TENANT_LABEL_PATH).append(" == ")
                .append(quote(tenantId)).append(')');

        for (Condition condition : conditions == null ? List.<Condition>of() : conditions) {
            cel.append(" && ").append(clause(condition));
        }
        return cel.toString();
    }

    /**
     * One condition as CEL, as an OR of equalities.
     *
     * <p>Written out rather than using CEL's {@code in} operator: {@code in}
     * against a list literal is valid CEL but its support has varied across the
     * engine's versions, and a rule that parses on one release and silently
     * matches nothing on the next is the worst failure available here — the
     * customer sees no incidents and nothing logs an error.
     */
    private static String clause(Condition condition) {
        String field = requireField(condition);
        Set<String> values = new LinkedHashSet<>();
        for (String raw : condition.values() == null ? List.<String>of() : condition.values()) {
            values.add(requireValue(field, raw));
        }
        if (values.isEmpty()) {
            throw AlertException.badRequest("empty_condition",
                    "Choose at least one value for " + field + ", or remove the condition.");
        }
        List<String> parts = new ArrayList<>();
        for (String value : values) {
            // `source` is a LIST on an alert — one alert can carry several —
            // so equality would never match. The engine exposes membership as
            // `in` against the field, which is the one place it is unavoidable.
            parts.add("source".equals(field)
                    ? quote(value) + " in source"
                    : field + " == " + quote(value));
        }
        return "(" + String.join(" || ", parts) + ")";
    }

    /** The attributes an incident may be grouped by, validated the same way. */
    public static List<String> groupingCriteria(List<String> raw) {
        List<String> out = new ArrayList<>();
        for (String field : raw == null ? List.<String>of() : raw) {
            String clean = field == null ? "" : field.trim().toLowerCase(Locale.ROOT);
            if (!FIELDS.contains(clean)) {
                throw AlertException.badRequest("invalid_grouping",
                        "Alerts can be grouped by severity, service or source.");
            }
            if (!out.contains(clean)) {
                out.add(clean);
            }
        }
        return out;
    }

    /**
     * The {@code sqlQuery} the engine stores beside the CEL.
     *
     * <p>The engine requires this field and does <b>not</b> match on it — that
     * was verified against a running engine by creating a rule whose SQL was
     * {@code 1 = 0} and watching it correlate anyway. It is recorded rather
     * than evaluated.
     *
     * <p>So this deliberately does not attempt to translate the conditions.
     * A SQL fragment that looked authoritative and was never executed would be
     * read by the next person as the thing that decides what matches, and they
     * would edit it expecting an effect. A single parameterised literal, with
     * the CEL alongside it, cannot be mistaken for the matcher.
     */
    public static Map<String, Object> storedDefinition(String cel) {
        return Map.of("sql", "(1 = %s)", "params", List.of("1"), "cel", cel);
    }

    private static String requireField(Condition condition) {
        String field = condition == null || condition.field() == null
                ? "" : condition.field().trim().toLowerCase(Locale.ROOT);
        if (!FIELDS.contains(field)) {
            throw AlertException.badRequest("invalid_field",
                    "Alerts can be matched on severity, service or source.");
        }
        return field;
    }

    private static String requireValue(String field, String raw) {
        String value = raw == null ? "" : raw.trim();
        if (!VALUE.matcher(value).matches()) {
            throw AlertException.badRequest("invalid_value",
                    "\"" + value + "\" is not a value this can match on. "
                            + "Use letters, numbers, spaces and . _ : / - only.");
        }
        if ("severity".equals(field)) {
            String lower = value.toLowerCase(Locale.ROOT);
            if (!SEVERITIES.contains(lower)) {
                throw AlertException.badRequest("invalid_severity",
                        "Severity must be one of: " + String.join(", ", SEVERITIES));
            }
            return lower;
        }
        return value;
    }

    /**
     * A CEL string literal.
     *
     * <p>{@link #VALUE} has already refused anything containing a quote or a
     * backslash, so this cannot be asked to escape one. It is written as a
     * belt-and-braces assertion rather than a transformation: if a value ever
     * reaches here that would need escaping, that is a hole upstream and the
     * right response is to stop, not to quietly make it safe.
     */
    private static String quote(String value) {
        if (value.indexOf('"') >= 0 || value.indexOf('\\') >= 0) {
            throw new IllegalStateException(
                    "unescaped value reached CEL generation — validation was bypassed");
        }
        return '"' + value + '"';
    }
}
