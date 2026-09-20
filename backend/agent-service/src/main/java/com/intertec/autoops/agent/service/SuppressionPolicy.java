package com.intertec.autoops.agent.service;

import com.intertec.autoops.agent.domain.FindingSuppression;
import com.intertec.autoops.agent.exception.AgentException;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * What a dismissal is allowed to be.
 *
 * <p><b>Written before the endpoint that uses it, deliberately.</b> A
 * service-wide dismissal is one click and can hide hundreds of findings nobody
 * ever sees again. Retrofitting a role check after people have created fifty of
 * them means either breaking their dismissals or grandfathering the hole, and
 * both of those are decisions nobody wants to make under pressure. So the caps
 * exist first and the API is written against them.
 *
 * <p>Three rules:
 *
 * <ol>
 *   <li><b>Wide scopes need an elevated role.</b> Silencing a whole category
 *       across a service, or across a tenant, is an administrative act.
 *       Dismissing one finding, or one category on one resource, is ordinary
 *       work and stays open to anyone who can see it.</li>
 *   <li><b>Wide scopes cannot be permanent.</b> A never-expiring tenant-wide
 *       dismissal is indistinguishable from deleting the check, except that
 *       nobody remembers it exists. They get a hard TTL ceiling, deliberately
 *       shorter than the narrow ones.</li>
 *   <li><b>Permanent dismissal needs a reason from a closed set.</b> Free text
 *       alone is how a backlog becomes fiction — everything is silenced "for
 *       now" and nobody can later tell an accepted risk from a false
 *       positive.</li>
 * </ol>
 */
public final class SuppressionPolicy {

    /** Scopes that hide findings somebody has never seen, including future ones. */
    private static final Set<FindingSuppression.Scope> WIDE = Set.of(
            FindingSuppression.Scope.SERVICE_CATEGORY,
            FindingSuppression.Scope.CATEGORY_GLOBAL);

    /**
     * The longest a dismissal at each scope may last.
     *
     * <p>Wide scopes get a shorter ceiling than narrow ones, which is the
     * opposite of what convenience would suggest and the point: the more a
     * dismissal hides, the sooner somebody should have to look at it again.
     */
    private static final Map<FindingSuppression.Scope, Duration> MAX_TTL = Map.of(
            FindingSuppression.Scope.FINDING, Duration.ofDays(365),
            FindingSuppression.Scope.SUBJECT_CATEGORY, Duration.ofDays(180),
            FindingSuppression.Scope.SERVICE_CATEGORY, Duration.ofDays(14),
            FindingSuppression.Scope.CATEGORY_GLOBAL, Duration.ofDays(14));

    /** Roles that may silence a whole category. */
    private static final Set<String> ELEVATED = Set.of("ADMIN", "OWNER", "PROVIDER");

    private SuppressionPolicy() {
    }

    public static boolean isWide(FindingSuppression.Scope scope) {
        return WIDE.contains(scope);
    }

    public static Duration maxTtl(FindingSuppression.Scope scope) {
        return MAX_TTL.getOrDefault(scope, Duration.ofDays(14));
    }

    /**
     * Refuses a dismissal the caller is not entitled to make.
     *
     * @param role      the caller's role claim
     * @param expiresAt when the dismissal lapses; null means permanent
     */
    public static void check(FindingSuppression.Scope scope, String role, Instant expiresAt,
                             FindingSuppression.ReasonCode reasonCode, Instant now) {
        if (scope == null) {
            throw AgentException.badRequest("scope_required",
                    "A dismissal has to say how widely it applies.");
        }
        if (reasonCode == null) {
            throw AgentException.badRequest("reason_code_required",
                    "A dismissal needs a reason from the supported set, not only free text — "
                            + "otherwise nobody can later tell an accepted risk from a false "
                            + "positive.");
        }

        boolean wide = isWide(scope);
        if (wide && !ELEVATED.contains(role == null ? "" : role.toUpperCase())) {
            throw AgentException.forbidden("elevated_role_required",
                    "Silencing a whole category across a service or a tenant is an "
                            + "administrative act. Dismiss this finding, or this category on "
                            + "this resource, instead.");
        }

        if (expiresAt == null) {
            if (wide) {
                throw AgentException.badRequest("permanent_wide_dismissal",
                        "A never-expiring dismissal at this scope is the same as deleting the "
                                + "check, except that nobody remembers it exists. Set an expiry "
                                + "of at most " + maxTtl(scope).toDays() + " days.");
            }
            // Permanent AND narrow is allowed, but only for a reason that means
            // it. "Deferred" is by definition temporary.
            if (reasonCode == FindingSuppression.ReasonCode.DEFERRED) {
                throw AgentException.badRequest("deferred_needs_an_expiry",
                        "A deferred finding is one somebody intends to come back to, so it "
                                + "needs a date to come back on.");
            }
            return;
        }

        if (!expiresAt.isAfter(now)) {
            throw AgentException.badRequest("expiry_in_the_past",
                    "That dismissal would have already lapsed.");
        }
        Duration requested = Duration.between(now, expiresAt);
        Duration ceiling = maxTtl(scope);
        if (requested.compareTo(ceiling) > 0) {
            throw AgentException.badRequest("ttl_too_long",
                    "A dismissal at this scope may last at most " + ceiling.toDays()
                            + " days. The wider a dismissal reaches, the sooner somebody "
                            + "should have to look at it again.");
        }
    }
}
