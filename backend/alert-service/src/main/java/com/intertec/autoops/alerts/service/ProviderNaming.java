package com.intertec.autoops.alerts.service;

import com.intertec.autoops.alerts.exception.AlertException;

/**
 * The tenant boundary for the objects AutoOps creates inside the alert engine.
 *
 * <p>Two kinds so far, and they are named identically because they have the
 * identical problem: connected monitoring SOURCES, and the correlation RULES
 * that decide which alerts become one incident. The engine has no tenant
 * concept for either, so for both the name IS the ownership record.
 *
 * <p>One engine holds every customer's providers, so — exactly as
 * {@code ProjectProvisioner} does for execution — the boundary is a
 * <b>computed name</b>:
 *
 * <pre>
 *   autoops--{sanitized tenantId}--{projectId}--{customer's label}
 * </pre>
 *
 * <p>It holds three ways:
 *
 * <ol>
 *   <li>the prefix is <b>computed, never accepted</b>. No method here takes a
 *       full provider name from a request; the caller supplies only the label
 *       and the prefix is derived from their JWT;</li>
 *   <li>the tenant id is <b>sanitized to {@code [a-z0-9-]} and length-bounded</b>,
 *       so a hostile workspace name cannot smuggle a separator into the prefix
 *       and make its provider appear to belong to another tenant;</li>
 *   <li>the customer's label is sanitized for the separator too. Without that,
 *       a label of {@code "--acme--7--x"} would produce a name that
 *       {@link #belongsTo} reads as another scope's.</li>
 * </ol>
 *
 * <p>The separator is <b>two</b> hyphens because tenant ids contain single
 * ones ({@code intertec-systems-1542f8a3}). With one, the prefix for tenant
 * {@code a} project {@code 1-b} and tenant {@code a-1} project {@code b} are
 * the same string.
 */
public final class ProviderNaming {

    static final String PREFIX = "autoops";
    static final String SEP = "--";
    private static final int MAX_TENANT = 48;
    private static final int MAX_LABEL = 64;

    private ProviderNaming() {
    }

    /** The scope prefix every provider owned by this tenant+project starts with. */
    public static String prefix(String tenantId, String projectId) {
        return tenantPrefix(tenantId) + sanitize(projectId, MAX_TENANT) + SEP;
    }

    /**
     * The prefix every provider owned by this tenant starts with, whichever
     * project connected it.
     *
     * <p>This is a boundary in its own right, not a loosened one. The separator
     * is two hyphens and {@link #sanitize} collapses runs, so no tenant id can
     * contain one — which means {@code autoops--acme--} cannot be a prefix of
     * any name belonging to a tenant other than {@code acme}. The project
     * segment that follows is the only thing this stops checking.
     */
    public static String tenantPrefix(String tenantId) {
        return PREFIX + SEP + sanitize(tenantId, MAX_TENANT) + SEP;
    }

    /**
     * The prefix for a scope, which is tenant-wide when no project is named.
     *
     * <p>A null project means "everything this tenant connected", and that is
     * the whole reason the alert plane can be read at workspace level: a
     * customer connects Datadog once and sees its alerts without having to
     * repeat the connection in every project.
     *
     * <p>A BLANK project is rejected rather than treated as null. Blank is what
     * an empty form field or a stringified absent value looks like, and
     * silently reading it as "the whole tenant" would widen a scope by
     * accident — the one direction this class exists to prevent.
     */
    private static String scopePrefix(String tenantId, String projectId) {
        if (projectId == null) {
            return tenantPrefix(tenantId);
        }
        if (projectId.isBlank()) {
            throw AlertException.badRequest("invalid_project",
                    "Name a project, or omit it entirely to include the whole workspace.");
        }
        return prefix(tenantId, projectId);
    }

    /** Full engine name for a source the customer calls {@code label}. */
    public static String qualify(String tenantId, String projectId, String label) {
        String clean = sanitize(label, MAX_LABEL);
        if (clean.isEmpty()) {
            throw AlertException.badRequest("invalid_name",
                    "Give this connection a name using letters, numbers or spaces.");
        }
        return prefix(tenantId, projectId) + clean;
    }

    /**
     * Whether an engine provider name belongs to this scope.
     *
     * <p>{@code projectId} null widens to the whole tenant. It cannot widen
     * past one: the tenant segment is still compared in full.
     */
    public static boolean belongsTo(String engineName, String tenantId, String projectId) {
        return engineName != null && engineName.startsWith(scopePrefix(tenantId, projectId));
    }

    /**
     * The customer's own label, with the scoping prefix removed.
     *
     * <p>With no project in hand the project segment is stripped too, so a
     * workspace-level list shows the name the customer typed rather than
     * {@code 9004--production-datadog}. Which project it belongs to is a
     * separate field — see {@link #projectOf} — because a label is what a human
     * reads and an id is what the console navigates with.
     */
    public static String label(String engineName, String tenantId, String projectId) {
        if (!belongsTo(engineName, tenantId, projectId)) {
            return engineName;
        }
        String rest = engineName.substring(scopePrefix(tenantId, projectId).length());
        if (projectId != null) {
            return rest;
        }
        int sep = rest.indexOf(SEP);
        return sep < 0 ? rest : rest.substring(sep + SEP.length());
    }

    /**
     * Which project connected this source, read back out of its own name.
     *
     * <p>Needed only by the workspace-level list, where sources from several
     * projects appear together and each row has to say where it lives. Derived
     * rather than stored because the name is the record — there is no row in
     * any AutoOps table for a connected source.
     *
     * @return null when the name does not belong to this tenant at all, or
     *         carries no project segment
     */
    public static String projectOf(String engineName, String tenantId) {
        if (!belongsTo(engineName, tenantId, null)) {
            return null;
        }
        String rest = engineName.substring(tenantPrefix(tenantId).length());
        int sep = rest.indexOf(SEP);
        if (sep <= 0) {
            return null;
        }
        return rest.substring(0, sep);
    }

    /**
     * Lowercase, {@code [a-z0-9-]} only, runs collapsed, bounded.
     *
     * <p>Collapsing runs is what removes the separator: any {@code --} a caller
     * supplies becomes a single {@code -}, so a crafted label cannot forge a
     * scope boundary.
     */
    static String sanitize(String raw, int max) {
        if (raw == null) {
            return "";
        }
        String s = raw.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-|-$", "");
        return s.length() > max ? s.substring(0, max) : s;
    }
}
