package com.intertec.autoops.agent.scope;

import com.fasterxml.jackson.databind.JsonNode;
import com.intertec.autoops.agent.exception.AgentException;

/**
 * One subject kind's scope, and how that kind actually ended.
 *
 * <p><b>Coverage is per element, not per run, and the reason is a specific
 * failure.</b> {@code aws.public_exposure_auditor} enumerates buckets and
 * security groups, then calls IAM. When the IAM call throws, the run has real,
 * usable coverage of {@code cloud_resource} and none at all of {@code
 * principal} — and from the agent's own point of view it produced findings and
 * finished, so it reports success. A run-level verdict has to choose: call it
 * COMPLETE and {@code principal} findings from previous runs reap against a run
 * that never looked at them, or call it PARTIAL and throw away good
 * {@code cloud_resource} coverage every single time one dimension flakes.
 *
 * <p>Per-element coverage refuses the choice. The IAM element is marked
 * {@link Coverage#PARTIAL}, the reaper skips that kind for this run, and buckets
 * reap normally.
 *
 * <p>{@code coverage} is {@code null} on a declaration — at start a scope is an
 * intent, and nothing has happened to it yet. It is <b>required</b> on a
 * completion, which is what turns a silently omitted kind into a rejection
 * rather than a coverage claim.
 *
 * @param sourceVerified whether the enumeration was checked against a total the
 *                       SOURCE reported, rather than merely being the list the
 *                       tool chose to print. False means the claim is one the
 *                       platform cannot validate — see
 *                       {@code agent-runtime/TRUNCATION.md}.
 */
public record ScopeClaim(SubjectScope scope, Coverage coverage, boolean sourceVerified) {

    /** How one subject kind ended. */
    public enum Coverage {
        /** Every subject the scope describes was examined. Only this reaps. */
        COMPLETE,
        /** Some were. Honest, and still not grounds to resolve anything. */
        PARTIAL,
        /** None were — the call failed, or the agent chose not to. */
        SKIPPED;

        /**
         * Deliberately positive, so a value added later is excluded until
         * somebody decides otherwise rather than inheriting permission to
         * resolve findings.
         */
        public boolean mayReap() {
            return this == COMPLETE;
        }
    }

    public ScopeClaim {
        if (scope == null) {
            throw AgentException.badRequest("scope_required", "A claim needs a scope.");
        }
    }

    // NO two-argument convenience constructor, deliberately.
    //
    // There was one, defaulting sourceVerified to false, and it caused exactly
    // the bug it looks harmless enough to cause: withCoverage() used it, so
    // stamping a completion verdict silently discarded the flag and every
    // completed scope read back as unverified. Nothing failed — false is the
    // safe value — so uncheckedEnumerations() would have reported 100% forever
    // and sent somebody chasing automations that were reporting totals
    // perfectly well.
    //
    // A flag that safely defaults to the ALARMING value is worse than one that
    // defaults to silence: it manufactures work instead of hiding it. Every
    // construction now names all three fields, so forgetting one is a compile
    // error rather than a plausible reading of reality.

    public static ScopeClaim declaring(SubjectScope scope) {
        return new ScopeClaim(scope, null, false);
    }

    /**
     * True when this claim rests on an enumeration nobody could check.
     *
     * <p><b>Deliberately not a distinct coverage status.</b> The reaper must
     * treat it identically — an unverifiable enumeration is still the best
     * evidence available, and refusing to reap on it would strand real findings
     * forever. What this buys is that "which of our reaps rest on unverifiable
     * enumerations" is a query rather than a per-agent re-derivation, and it has
     * to be recorded at the time because the tool output it would otherwise be
     * reconstructed from is long gone.
     */
    public boolean restsOnAnUncheckedEnumeration() {
        return scope instanceof SubjectScope.Enumerated && !sourceVerified;
    }

    public String subjectKind() {
        return scope.subjectKind();
    }

    public ScopeClaim withCoverage(Coverage coverage) {
        return new ScopeClaim(scope, coverage, sourceVerified);
    }

    static ScopeClaim parse(JsonNode node) {
        SubjectScope scope = SubjectScope.parse(node);
        // Absent means unverified, which is the safe default: a claim is only
        // marked checkable when something actually checked it.
        boolean verified = node.path("source_verified").asBoolean(false);
        JsonNode coverage = node.get("coverage");
        if (coverage == null || coverage.isNull()) {
            return new ScopeClaim(scope, null, verified);
        }
        try {
            return new ScopeClaim(scope, Coverage.valueOf(coverage.asText()), verified);
        } catch (IllegalArgumentException e) {
            throw AgentException.badRequest("scope_coverage_unsupported",
                    "'" + coverage.asText() + "' is not a coverage verdict. Use COMPLETE, "
                            + "PARTIAL or SKIPPED.");
        }
    }
}
