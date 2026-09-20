package com.intertec.autoops.alerts.service;

import com.intertec.autoops.alerts.exception.AlertException;

/**
 * The tenant boundary for connected monitoring sources.
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
        return PREFIX + SEP + sanitize(tenantId, MAX_TENANT) + SEP
                + sanitize(projectId, MAX_TENANT) + SEP;
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

    /** Whether an engine provider name belongs to this tenant+project. */
    public static boolean belongsTo(String engineName, String tenantId, String projectId) {
        return engineName != null && engineName.startsWith(prefix(tenantId, projectId));
    }

    /** The customer's own label, with the scoping prefix removed. */
    public static String label(String engineName, String tenantId, String projectId) {
        String p = prefix(tenantId, projectId);
        return engineName != null && engineName.startsWith(p)
                ? engineName.substring(p.length())
                : engineName;
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
