package com.intertec.autoops.alerts.service;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Map;

/**
 * The tenant boundary for the alert plane.
 *
 * <p>One engine serves every customer, so the boundary is not a credential per
 * customer — it is a <b>label on every alert, checked here on the way out</b>.
 * This is the {@code ProjectProvisioner} idea applied to reads instead of
 * writes, and it holds the same three ways:
 *
 * <ol>
 *   <li>the scope is <b>computed from the JWT, never accepted from the
 *       request</b> — no method here takes a tenant id, a filter or a query
 *       expression from a caller;</li>
 *   <li>it <b>fails closed</b>: an alert with no tenant label belongs to
 *       nobody, so a tenant never sees it. Unlabelled data leaking into every
 *       workspace is the failure this class exists to prevent, and the safe
 *       direction is to show a customer too little;</li>
 *   <li>filtering happens <b>in this process</b>. The engine's query API takes
 *       a CEL expression, and handing it one built from anything a caller
 *       supplied would make the boundary a string-escaping problem. It is
 *       instead a comparison between two values we already hold.</li>
 * </ol>
 *
 * <p>PROVIDER sees everything. That is the operator role, it already reads
 * every tenant's usage and audit, and the alert plane is infrastructure it
 * runs. Every other role is confined to its own {@code tenantId} claim.
 */
public final class TenantScope {

    /**
     * Stamped on an alert at ingest. Underscored rather than dotted: these land
     * in a JSON label map that is addressed with dotted paths elsewhere, and a
     * dot inside a key makes that ambiguous.
     */
    public static final String TENANT_LABEL = "autoops_tenant";
    public static final String PROJECT_LABEL = "autoops_project";

    /**
     * How the tenant label is addressed in an engine CEL expression.
     *
     * <p>Derived from {@link #TENANT_LABEL} rather than written out, because
     * the two drifting apart is silent: a correlation rule carrying a path the
     * alerts do not have simply matches nothing, no incident is ever created,
     * and nothing logs an error. See {@code CorrelationQuery}.
     */
    public static final String TENANT_LABEL_PATH = "labels." + TENANT_LABEL;

    private final String tenantId;
    private final boolean provider;
    private final Long projectId;
    private final java.util.Set<String> ownedProviderIds;

    private TenantScope(String tenantId, boolean provider, Long projectId,
                        java.util.Set<String> ownedProviderIds) {
        this.tenantId = tenantId;
        this.provider = provider;
        this.projectId = projectId;
        this.ownedProviderIds = ownedProviderIds == null ? java.util.Set.of() : ownedProviderIds;
    }

    /**
     * Narrows this scope to alerts that also arrived through one of the given
     * monitoring sources.
     *
     * <p>This is the second way an alert can be recognised as a tenant's own,
     * and it is the one that makes the connect flow useful. A source raises
     * alerts that know nothing about AutoOps — Datadog has never heard of a
     * project — so nothing stamps {@code autoops_tenant} on them. But the
     * source itself was connected by exactly one tenant, under a name only that
     * tenant's scope produces. Ownership of the source is therefore ownership
     * of its alerts.
     *
     * <p>It is still fail-closed: an alert from a source nobody connected
     * matches neither rule and stays invisible.
     */
    public TenantScope owning(java.util.Set<String> providerIds) {
        return new TenantScope(tenantId, provider, projectId, providerIds);
    }

    /**
     * @param projectId optional narrowing WITHIN the caller's scope. It can only
     *                  ever remove alerts from the answer, never add one, so a
     *                  caller passing someone else's project id gets nothing
     *                  rather than something.
     */
    /**
     * A scope for the {@code /internal/**} surface, where there is no JWT.
     *
     * <p><b>Deliberately NOT a provider scope.</b> A provider scope skips the
     * ownership filter entirely and sees every incident in the engine; this one
     * goes through exactly the same visibility rules a signed-in user of that
     * tenant would. The caller is trusted to state a tenant, not to widen what
     * the tenant can see.
     *
     * <p>The project id is required rather than optional. A signed-in user may
     * legitimately ask a workspace-wide question — that is what the alert plane
     * shows at tenant level — but an internal caller is always an agent tool
     * running inside one project, so a missing project id there is a caller
     * that forgot to say which, not a request to widen. Answering it with the
     * whole workspace would hand an agent subjects outside the scope it claims
     * coverage of.
     */
    public static TenantScope internal(String tenantId, Long projectId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("internal scope needs a tenant");
        }
        if (projectId == null) {
            throw new IllegalArgumentException(
                    "internal scope needs a project: without one no monitoring source can be "
                            + "resolved as owned, and the answer would be empty rather than wrong");
        }
        return new TenantScope(tenantId, false, projectId, java.util.Set.of());
    }

    public static TenantScope of(Jwt jwt, Long projectId) {
        String tenantId = jwt.getClaimAsString("tenantId");
        boolean provider = "PROVIDER".equals(jwt.getClaimAsString("role"));
        return new TenantScope(tenantId, provider, projectId, java.util.Set.of());
    }

    public String tenantId() {
        return tenantId;
    }

    public Long projectId() {
        return projectId;
    }

    public boolean isProvider() {
        return provider;
    }

    /**
     * Whether this scope may see the given engine alert.
     *
     * <p>Two independent routes in, and each is a POSITIVE check against
     * something this scope owns:
     *
     * <ol>
     *   <li><b>labels</b> — the alert was stamped on the way in, so the tenant
     *       must match and, when asked for, the project too;</li>
     *   <li><b>provenance</b> — it arrived through a monitoring source only
     *       this scope connected. Nothing stamps a label on these (Datadog has
     *       never heard of an AutoOps project) and they carry none, which is
     *       exactly why the project check cannot be applied to them: the
     *       source's own name is already tenant- AND project-scoped, so
     *       ownership implies both.</li>
     * </ol>
     *
     * <p>Fail closed: no claim, no matching label and no matching source is no.
     */
    public boolean admits(Map<String, Object> alert) {
        if (provider) {
            return projectId == null || matchesProject(alert);
        }
        if (tenantId == null || tenantId.isBlank()) {
            return false;
        }
        boolean byLabel = tenantId.equals(label(alert, TENANT_LABEL))
                && (projectId == null || matchesProject(alert));
        return byLabel || arrivedThroughOwnedSource(alert);
    }

    private boolean matchesProject(Map<String, Object> alert) {
        return String.valueOf(projectId).equals(label(alert, PROJECT_LABEL));
    }

    private boolean arrivedThroughOwnedSource(Map<String, Object> alert) {
        if (ownedProviderIds.isEmpty() || alert == null) {
            return false;
        }
        Object id = alert.get("providerId");
        return id != null && ownedProviderIds.contains(String.valueOf(id));
    }

    /**
     * Reads a label off an engine alert.
     *
     * <p>Values are stringified rather than cast: labels arrive from whatever
     * monitoring tool produced the alert, and a project id that came through as
     * a JSON number must still match one that came through as a string.
     */
    static String label(Map<String, Object> alert, String key) {
        if (alert == null) {
            return null;
        }
        Object labels = alert.get("labels");
        if (!(labels instanceof Map<?, ?> map)) {
            return null;
        }
        Object value = map.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
