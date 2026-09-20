package com.intertec.autoops.agent.scope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.intertec.autoops.agent.exception.AgentException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything one run claims to have covered — a {@link ScopeClaim} per subject
 * kind.
 *
 * <p><b>Why this is a list and not a single scope.</b> A run legitimately covers
 * several kinds at once. {@code aws.public_exposure_auditor} correlates S3
 * buckets and security groups ({@code cloud_resource}) with IAM users ({@code
 * principal}), and the chain <i>between</i> those kinds is the entire product —
 * a public bucket reachable through an open port in an account where somebody
 * still holds a stale key. A single scope object could not describe what that
 * run covered, and the reaper would have to either skip its findings forever or
 * resolve them against a scope that never included them.
 *
 * <p>The scope is written twice. <b>At start</b> it is what the run intends to
 * cover. <b>At completion</b> it is what the run actually reached, every element
 * carrying an explicit coverage verdict, and that second write may only ever
 * <i>narrow</i> the first — see {@link #checkNarrows}.
 */
public record RunScope(List<ScopeClaim> claims) {

    /**
     * The key standing for a bare {@code all} scope, which names no kind.
     *
     * <p>An empty string rather than a null key, because {@code Map.of} cannot
     * hold one and a caller building the map with it would get a
     * {@code NullPointerException} nowhere near the cause.
     */
    public static final String EVERY_KIND = "";

    public RunScope {
        claims = List.copyOf(claims);
    }

    /** The scopes alone, for predicate compilation. */
    public List<SubjectScope> scopes() {
        return claims.stream().map(ScopeClaim::scope).toList();
    }

    /**
     * True when this is the bare {@code {"kind":"all"}} — every subject of every
     * kind. The only scope that may stand alone without naming a kind.
     */
    public boolean isEverything() {
        return claims.size() == 1
                && claims.getFirst().scope() instanceof SubjectScope.All all
                && all.subjectKind() == null;
    }

    public Map<String, ScopeClaim> byKind() {
        Map<String, ScopeClaim> out = new LinkedHashMap<>();
        claims.forEach(claim -> out.put(claim.subjectKind(), claim));
        return out;
    }

    /**
     * Claims resting on an enumeration nobody could check against a source total.
     *
     * <p>Not a reason to skip them — see {@link ScopeClaim#restsOnAnUncheckedEnumeration}
     * — but the answer to "what would we lose if the extraction had been quietly
     * truncating", which is otherwise unanswerable after the fact.
     */
    public List<ScopeClaim> uncheckedEnumerations() {
        return claims.stream().filter(ScopeClaim::restsOnAnUncheckedEnumeration).toList();
    }

    /** The claims a reaper may act on: those whose own coverage says COMPLETE. */
    public List<ScopeClaim> reapable() {
        return claims.stream()
                .filter(claim -> claim.coverage() != null && claim.coverage().mayReap())
                .toList();
    }

    // -------------------------------------------------------------- parse ---

    /**
     * Reads {@code agent_runs.subject_scope}.
     *
     * <p>A single object is accepted as a one-element list. Not leniency for its
     * own sake — it keeps every scope written before the array shape readable,
     * and a scope that cannot be read is a run that cannot reap.
     */
    public static RunScope parse(JsonNode node) {
        if (node == null || node.isNull()) {
            throw AgentException.badRequest("scope_required",
                    "A run has to declare what it set out to cover. Without it nothing it "
                            + "produces can ever be reaped, because no later run can prove it "
                            + "looked at the same subjects.");
        }
        List<ScopeClaim> parsed = new ArrayList<>();
        if (node.isObject()) {
            parsed.add(ScopeClaim.parse(node));
        } else if (node.isArray()) {
            node.forEach(element -> parsed.add(ScopeClaim.parse(element)));
        } else {
            throw AgentException.badRequest("scope_not_an_array",
                    "subject_scope has to be a scope object or an array of them.");
        }
        return of(parsed);
    }

    /** Applies the array-level invariants that a single claim cannot see. */
    public static RunScope of(List<ScopeClaim> claims) {
        if (claims.isEmpty()) {
            // Not the same as "covered nothing" — a run that covered nothing
            // records an empty enumerated scope and says so. An empty array is
            // a scope somebody forgot to fill in.
            throw AgentException.badRequest("scope_empty",
                    "subject_scope cannot be empty. A run that genuinely covered nothing "
                            + "records an enumerated scope with subject_id_count 0.");
        }
        Set<String> kinds = new LinkedHashSet<>();
        for (ScopeClaim claim : claims) {
            if (claim.subjectKind() == null && claims.size() > 1) {
                // A bare `all` already covers every kind, so anything beside it
                // is either redundant or contradicts it — and there would be no
                // principled answer as to which predicate the reaper should use.
                throw AgentException.badRequest("scope_all_must_stand_alone",
                        "A bare all scope already covers every subject kind, so it cannot "
                                + "appear alongside others. Name the kinds, or use it alone.");
            }
            if (!kinds.add(claim.subjectKind())) {
                // Two scopes for one kind would have to be unioned, and a union
                // of inclusions is a wider claim than either — the direction
                // this design refuses everywhere else.
                throw AgentException.badRequest("scope_duplicate_subject_kind",
                        "subject_scope has two entries for '" + claim.subjectKind()
                                + "'. One scope per subject kind.");
            }
        }
        return new RunScope(claims);
    }

    // ---------------------------------------------------------- narrowing ---

    /**
     * Refuses a completion scope that claims more than the start scope did, or
     * that leaves a declared kind unaccounted for.
     *
     * <p><b>A run may discover it covered less than it meant to, never more.</b>
     * Paging stops early, an API throttles, a subscription is unreachable — all
     * of those shrink coverage, and recording the smaller truth is what keeps
     * the reaper honest. A completion scope that grew is not a discovery; it
     * means the two claims were computed from different inputs, and there is no
     * way to tell which one describes the findings that were actually emitted.
     * The run is failed rather than guessed at.
     *
     * <p><b>Silence is not narrowing.</b> Every kind declared at start must
     * reappear at completion with an explicit verdict. Letting an omitted kind
     * mean "covered none of it" reads reasonably and fails in exactly the case
     * that matters: the agent whose IAM call threw omits {@code principal},
     * reports success, and — if omission were benign — leaves a scope nobody can
     * distinguish from one that deliberately skipped it. Making omission a
     * rejection costs one line in each agent and removes the whole class.
     *
     * <table>
     *   <caption>What narrowing means per pair</caption>
     *   <tr><th>start</th><th>completion</th><th></th></tr>
     *   <tr><td>all</td><td>anything</td><td>valid</td></tr>
     *   <tr><td>dimensional</td><td>dimensional</td>
     *       <td>valid if every constrained key stays constrained, to a subset</td></tr>
     *   <tr><td>dimensional</td><td>enumerated</td>
     *       <td>valid — an explicit list of what was visited is the strongest
     *           claim there is</td></tr>
     *   <tr><td>enumerated</td><td>enumerated</td><td>valid if no longer</td></tr>
     *   <tr><td>enumerated</td><td>dimensional</td>
     *       <td><b>reject</b> — a dimensional set can contain subjects the run
     *           never held a list for, so narrowing cannot be proved</td></tr>
     *   <tr><td>not all</td><td>all</td><td><b>reject</b> — plainly wider</td></tr>
     *   <tr><td>(kind absent)</td><td>kind present</td>
     *       <td><b>reject</b> — a kind the run never set out to cover</td></tr>
     *   <tr><td>kind present</td><td>(kind absent)</td>
     *       <td><b>reject</b> — say SKIPPED, do not go quiet</td></tr>
     * </table>
     */
    public void checkNarrows(RunScope start) {
        for (ScopeClaim claim : claims) {
            if (claim.coverage() == null) {
                throw AgentException.badRequest("scope_coverage_required",
                        "The completion scope for '" + describe(claim) + "' does not say how "
                                + "that kind ended. Every element needs COMPLETE, PARTIAL or "
                                + "SKIPPED — a kind with no verdict is one the reaper cannot "
                                + "tell apart from one that was covered.");
            }
        }

        if (start.isEverything()) {
            return;
        }
        if (isEverything()) {
            throw reject("A run that set out to cover part of the estate cannot finish claiming "
                    + "it covered all of it.");
        }

        Map<String, ScopeClaim> before = start.byKind();
        for (ScopeClaim after : claims) {
            ScopeClaim original = before.get(after.subjectKind());
            if (original == null) {
                throw reject("The run finished claiming subject kind '" + after.subjectKind()
                        + "', which it never set out to cover.");
            }
            checkPairNarrows(original.scope(), after.scope());
        }

        Set<String> accounted = byKind().keySet();
        for (String declared : before.keySet()) {
            if (!accounted.contains(declared)) {
                throw AgentException.badRequest("scope_kind_unaccounted",
                        "The run declared subject kind '" + declared + "' and finished without "
                                + "mentioning it. Silence is not the same as skipping: a kind "
                                + "left out is indistinguishable from one that was covered, so "
                                + "say SKIPPED or PARTIAL explicitly.");
            }
        }
    }

    private static String describe(ScopeClaim claim) {
        return claim.subjectKind() == null ? "every subject kind" : claim.subjectKind();
    }

    private static void checkPairNarrows(SubjectScope start, SubjectScope end) {
        if (start instanceof SubjectScope.All) {
            return;
        }
        if (end instanceof SubjectScope.All) {
            throw reject("Subject kind '" + end.subjectKind() + "' was scoped at start and "
                    + "finished claiming every subject of that kind.");
        }
        if (start instanceof SubjectScope.Dimensional from) {
            if (end instanceof SubjectScope.Enumerated) {
                // An explicit list of what was visited is a stronger claim than
                // any filter, so this narrows by construction.
                return;
            }
            SubjectScope.Dimensional to = (SubjectScope.Dimensional) end;
            from.dimensions().forEach((key, allowed) -> {
                List<String> reached = to.dimensions().get(key);
                if (reached == null) {
                    throw reject("The run started constrained to " + key + " " + allowed
                            + " and finished unconstrained on " + key + ".");
                }
                if (!Set.copyOf(allowed).containsAll(reached)) {
                    throw reject("The run finished claiming " + key + " values it never set out "
                            + "to cover: " + reached + " is not within " + allowed + ".");
                }
            });
            return;
        }
        SubjectScope.Enumerated from = (SubjectScope.Enumerated) start;
        if (!(end instanceof SubjectScope.Enumerated to)) {
            throw reject("The run started from an explicit list of " + from.subjectIdCount()
                    + " subjects and finished with a filter. A filter can match subjects the "
                    + "run never held a list for, so there is no way to prove it narrowed.");
        }
        if (to.subjectIdCount() > from.subjectIdCount()) {
            throw reject("The run finished claiming " + to.subjectIdCount() + " subjects, "
                    + "having set out to cover " + from.subjectIdCount() + ".");
        }
        if (to.subjectIdsDigest().equals(from.subjectIdsDigest())
                && to.subjectIdCount() != from.subjectIdCount()) {
            // Same list, different length: one of the two numbers is wrong, and
            // the reaper would trust whichever it read.
            throw reject("The completion scope carries the start scope's digest but a different "
                    + "subject count, so one of the two claims is not describing its own list.");
        }
    }

    /**
     * Refuses a coverage verdict the run's own numbers contradict.
     *
     * <p>Narrowing validation proves a completion scope is no wider than the
     * intent. It cannot prove the run did the work: an agent may declare
     * {@code {"kind":"all"}}, examine forty subjects, report COMPLETE, and be
     * entitled to resolve a backlog of nine hundred. Every check here is one
     * that holds without knowing the size of the estate:
     *
     * <ul>
     *   <li><b>COMPLETE on an enumerated scope</b> must have examined exactly as
     *       many subjects as the scope lists. The list is right there; anything
     *       else is arithmetic that does not add up.</li>
     *   <li><b>COMPLETE on any scope</b> must have examined at least one subject,
     *       unless the scope is an empty enumeration. Complete coverage of an
     *       unbounded set by looking at nothing is not a claim, it is a default
     *       that got written down.</li>
     *   <li><b>SKIPPED</b> must have examined none. Claiming to have examined
     *       subjects of a kind that was skipped means one of the two numbers is
     *       describing a different run.</li>
     *   <li><b>PARTIAL on an enumerated scope</b> cannot have examined more than
     *       the scope lists.</li>
     * </ul>
     *
     * <p>The check this deliberately does NOT make is the tempting one:
     * comparing subjects examined against the count of open findings the scope
     * would resolve. A resource deleted between runs leaves an open finding with
     * no subject left to examine — which is precisely the case the reaper exists
     * for — so a run legitimately covers fewer subjects than it resolves
     * findings for, and gating on it would block the main line. That comparison
     * is a gauge instead: {@link #overclaimSuspects}.
     */
    public void checkCoherent(Map<String, Integer> evaluatedByKind) {
        for (ScopeClaim claim : claims) {
            String key = claim.subjectKind() == null ? EVERY_KIND : claim.subjectKind();
            int evaluated = evaluatedByKind.getOrDefault(key, 0);
            Integer listed = claim.scope() instanceof SubjectScope.Enumerated enumerated
                    ? enumerated.subjectIdCount() : null;

            switch (claim.coverage()) {
                case COMPLETE -> {
                    if (listed != null && evaluated != listed) {
                        throw incoherent(key, "claims COMPLETE coverage of an explicit list of "
                                + listed + " subjects, having examined " + evaluated);
                    }
                    if (listed == null && evaluated == 0) {
                        throw incoherent(key, "claims COMPLETE coverage having examined no "
                                + "subjects at all");
                    }
                }
                case SKIPPED -> {
                    if (evaluated != 0) {
                        throw incoherent(key, "was skipped, yet reports " + evaluated
                                + " subjects examined");
                    }
                }
                case PARTIAL -> {
                    if (listed != null && evaluated > listed) {
                        throw incoherent(key, "examined " + evaluated + " subjects from a list "
                                + "of " + listed);
                    }
                }
            }
        }
    }

    private static AgentException incoherent(String kind, String what) {
        return AgentException.badRequest("scope_coverage_incoherent",
                "Subject kind '" + (kind.isEmpty() ? "<all>" : kind) + "' " + what + ". A "
                        + "coverage verdict the run's own numbers contradict would let the "
                        + "reaper resolve findings for subjects nobody looked at.");
    }

    private static AgentException reject(String why) {
        return AgentException.badRequest("scope_widened",
                why + " A completion scope may only narrow the scope the run started with — "
                        + "otherwise the two were computed from different inputs and neither can "
                        + "be trusted to describe the findings that were emitted.");
    }

    // ------------------------------------------------------------- render ---

    public JsonNode toJson(ObjectMapper mapper) {
        ArrayNode array = mapper.createArrayNode();
        for (ScopeClaim claim : claims) {
            ObjectNode node = array.addObject();
            switch (claim.scope()) {
                case SubjectScope.All all -> {
                    node.put("kind", "all");
                    if (all.subjectKind() != null) {
                        node.put("subject_kind", all.subjectKind());
                    }
                }
                case SubjectScope.Dimensional dimensional -> {
                    node.put("kind", "dimensional");
                    node.put("subject_kind", dimensional.subjectKind());
                    ObjectNode dims = node.putObject("dimensions");
                    dimensional.dimensions().forEach(
                            (key, values) -> values.forEach(dims.putArray(key)::add));
                }
                case SubjectScope.Enumerated enumerated -> {
                    node.put("kind", "enumerated");
                    node.put("subject_kind", enumerated.subjectKind());
                    node.put("subject_id_count", enumerated.subjectIdCount());
                    node.put("subject_ids_digest", enumerated.subjectIdsDigest());
                }
            }
            if (claim.coverage() != null) {
                node.put("coverage", claim.coverage().name());
            }
            if (claim.sourceVerified()) {
                node.put("source_verified", true);
            }
        }
        return array;
    }
}
