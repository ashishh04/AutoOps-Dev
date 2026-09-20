package com.intertec.autoops.agent.scope;

import com.fasterxml.jackson.databind.JsonNode;
import com.intertec.autoops.agent.exception.AgentException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * One claim about what a run covered.
 *
 * <p><b>This is not a predicate language, and the difference is the whole
 * design.</b> The reaper evaluates coverage against every open finding for an
 * agent — thousands at steady state, and more the worse things are going. If
 * coverage were an arbitrary expression evaluated in Java, the reaper would be a
 * full scan plus a deserialisation per row, degrading exactly when it is most
 * needed. So every shape here compiles to a SQL fragment over indexed columns on
 * {@code findings}, and anything that cannot is excluded by construction: no
 * regex, no glob, no boolean nesting, no field that is not on the table.
 *
 * <p><b>Inclusion only. No negation, no wildcards.</b> An exclusion is correct
 * only if the excluded set at reap time matches the set at run time, and tags
 * move: an agent that skipped prod on Monday, plus a finding retagged to prod on
 * Tuesday, produces a scope claiming coverage it never had — and the finding is
 * silently resolved. Inclusion-only means the claim is always <i>narrower</i>
 * than reality when data drifts, which is the direction that fails safe.
 */
public sealed interface SubjectScope {

    /**
     * Dimensions a scope may constrain.
     *
     * <p>Two rules govern this set and both are hard.
     *
     * <ol>
     *   <li>A dimension must be a <b>column on {@code findings}</b>, or the
     *       predicate cannot be pushed down.</li>
     *   <li><b>A scope says what was LOOKED AT, so anything produced by looking
     *       is disqualified.</b> That is the general rule, not a list —
     *       {@code severity}, {@code risk_tier}, {@code confidence_band} and
     *       {@code priority_score} all fail it for the same reason, and so will
     *       the next derived column somebody adds. Selecting on one describes
     *       the <i>output</i> set rather than the input set, which is seductive
     *       precisely because it yields a scope that reads tighter and more
     *       precise while claiming to have scanned only the subjects that turned
     *       out to be bad.</li>
     * </ol>
     */
    Map<String, String> DIMENSION_COLUMNS = Map.of(
            "environment", "environment",
            "service_ref", "service_ref");

    /**
     * The most subjects one run may enumerate.
     *
     * <p>Each one becomes a row in {@code agent_run_subject}, so an uncapped
     * enumeration is unbounded write amplification on every run. An agent with
     * more subjects than this is describing a sweep, and a sweep is what
     * {@link Dimensional} and {@link All} are for.
     */
    int MAX_ENUMERATED = 100_000;

    /** {@code null} on a bare {@link All}, which covers every kind. */
    String subjectKind();

    /** A SQL fragment plus its parameters, ANDed onto the reaper's WHERE. */
    Predicate toPredicate();

    /**
     * {@code runId} is bound by the caller, which owns it — a scope parsed from
     * a request body has no run yet, so it cannot carry one.
     */
    record Predicate(String sql, List<Object> params) {
    }

    // ------------------------------------------------------------- shapes ---

    /**
     * Everything this agent owns, or everything of one kind.
     *
     * <p>{@code subjectKind} absent means every kind — the only shape that may
     * stand alone as a whole run's scope.
     */
    record All(String subjectKind) implements SubjectScope {
        @Override
        public Predicate toPredicate() {
            return subjectKind == null
                    ? new Predicate("1 = 1", List.of())
                    : new Predicate("subject_kind = ?", List.of(subjectKind));
        }
    }

    /**
     * A conjunction of disjunctions: each key an IN, absent keys unconstrained.
     *
     * <p>{@code {"environment":["prod"],"service_ref":["svc-checkout"]}} reads
     * as "prod, and only the checkout service". An empty map is legal and covers
     * the same rows as {@link All} of that kind; it is kept distinct so that an
     * agent which <i>intended</i> to filter and computed an empty filter does
     * not read as a deliberate sweep in the run record.
     */
    record Dimensional(String subjectKind, Map<String, List<String>> dimensions)
            implements SubjectScope {

        @Override
        public Predicate toPredicate() {
            StringBuilder sql = new StringBuilder("subject_kind = ?");
            List<Object> params = new ArrayList<>();
            params.add(subjectKind);
            dimensions.forEach((key, values) -> {
                sql.append(" AND ").append(DIMENSION_COLUMNS.get(key)).append(" IN (");
                sql.append("?, ".repeat(values.size() - 1)).append("?)");
                params.addAll(values);
            });
            return new Predicate(sql.toString(), params);
        }
    }

    /**
     * An explicit list, materialised into {@code agent_run_subject}.
     *
     * <p>The ids themselves are not carried here — they are rows, not JSON. The
     * count and digest are what the run record keeps, so a completion claim can
     * be compared against the start claim without loading either list.
     */
    record Enumerated(String subjectKind, int subjectIdCount, String subjectIdsDigest)
            implements SubjectScope {

        @Override
        public Predicate toPredicate() {
            return new Predicate(
                    "subject_kind = ? AND subject_id_hash IN "
                            + "(SELECT s.subject_id_hash FROM agent_run_subject s "
                            + "WHERE s.run_id = ? AND s.subject_kind = ?)",
                    List.of(subjectKind, RUN_ID, subjectKind));
        }
    }

    /**
     * Stands in for the run id the reaper binds, so that a scope parsed from a
     * request body — which has no run yet — is still a complete value.
     */
    Object RUN_ID = new Object() {
        @Override
        public String toString() {
            return "<run_id>";
        }
    };

    // -------------------------------------------------------------- parse ---

    /**
     * Reads one scope object, refusing anything it cannot compile.
     *
     * <p>Every rejection here costs the run its reap, and that is the intended
     * failure. An unparseable claim about coverage is indistinguishable from no
     * claim, and treating it as "covered everything" is how a backlog gets
     * cleared by a typo.
     */
    static SubjectScope parse(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw AgentException.badRequest("scope_not_an_object",
                    "Each element of subject_scope has to be an object.");
        }
        String kind = text(node, "kind");
        if (kind == null) {
            throw AgentException.badRequest("scope_kind_required",
                    "A scope needs a kind: all, dimensional or enumerated.");
        }
        String subjectKind = text(node, "subject_kind");

        return switch (kind) {
            case "all" -> new All(blankToNull(subjectKind));
            case "dimensional" -> new Dimensional(
                    requireSubjectKind(subjectKind, "dimensional"), dimensions(node));
            case "enumerated" -> enumerated(node, requireSubjectKind(subjectKind, "enumerated"));
            default -> throw AgentException.badRequest("scope_kind_unsupported",
                    "Unsupported scope kind '" + kind + "'. Coverage has to compile to a SQL "
                            + "predicate over indexed columns, so the shapes are fixed: all, "
                            + "dimensional, enumerated.");
        };
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String requireSubjectKind(String subjectKind, String kind) {
        if (blankToNull(subjectKind) == null) {
            throw AgentException.badRequest("scope_subject_kind_required",
                    "A " + kind + " scope has to say which subject_kind it narrows. Only a bare "
                            + "all scope may leave it out.");
        }
        return subjectKind;
    }

    private static Map<String, List<String>> dimensions(JsonNode node) {
        JsonNode dims = node.get("dimensions");
        if (dims == null || dims.isNull()) {
            return Map.of();
        }
        if (!dims.isObject()) {
            throw AgentException.badRequest("scope_dimensions_not_an_object",
                    "dimensions has to be an object of column to list of values.");
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        dims.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            if (!DIMENSION_COLUMNS.containsKey(key)) {
                // Loudly, not silently. A dimension quietly dropped is a scope
                // claiming more coverage than the run had.
                throw AgentException.badRequest("scope_dimension_unsupported",
                        "'" + key + "' is not a dimension a scope can filter on. Supported: "
                                + new TreeSet<>(DIMENSION_COLUMNS.keySet()) + ". A scope says "
                                + "what was LOOKED AT, so anything produced by looking — "
                                + "severity, risk tier, confidence, priority — describes the "
                                + "output set rather than the input set and cannot narrow "
                                + "coverage.");
            }
            if (!entry.getValue().isArray() || entry.getValue().isEmpty()) {
                // An empty IN () matches nothing, so this is a scope claiming to
                // have covered the empty set — almost always a filter that
                // computed to nothing rather than an intent.
                throw AgentException.badRequest("scope_dimension_empty",
                        "Dimension '" + key + "' needs at least one value. Omit the key entirely "
                                + "to leave it unconstrained.");
            }
            Set<String> values = new LinkedHashSet<>();
            entry.getValue().forEach(value -> values.add(value.asText()));
            out.put(key, List.copyOf(values));
        });
        return Map.copyOf(out);
    }

    private static SubjectScope enumerated(JsonNode node, String subjectKind) {
        JsonNode count = node.get("subject_id_count");
        if (count == null || !count.isIntegralNumber() || count.asInt() < 0) {
            throw AgentException.badRequest("scope_count_required",
                    "An enumerated scope has to carry subject_id_count, so a completion claim "
                            + "can be compared to the start claim without loading either list.");
        }
        if (count.asInt() > MAX_ENUMERATED) {
            throw AgentException.badRequest("scope_too_many_subjects",
                    "An enumerated scope may list at most " + MAX_ENUMERATED + " subjects; this "
                            + "one lists " + count.asInt() + ". A run over more subjects than "
                            + "that is describing a sweep — use a dimensional or all scope.");
        }
        String digest = blankToNull(text(node, "subject_ids_digest"));
        if (digest == null || !digest.startsWith("sha256:")) {
            throw AgentException.badRequest("scope_digest_required",
                    "An enumerated scope needs subject_ids_digest as sha256:<hex>.");
        }
        return new Enumerated(subjectKind, count.asInt(), digest);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
