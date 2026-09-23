package com.intertec.autoops.agent.web;

import com.intertec.autoops.agent.exception.AgentException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Whether the agent product is actually working, per customer.
 *
 * <p><b>The numbers here have existed for a while and nobody has been able to
 * see them.</b> Coverage claims, verdict attribution, findings — all recorded,
 * all only readable by somebody with a SQL client. That is fine while a feature
 * is being built and wrong once customers have it, because the questions these
 * answer are the provider's daily ones: is this customer's agent running, is it
 * finding anything, and is what it finds trustworthy enough to act on.
 *
 * <p>Deliberately NOT the tenant's agent page with a wider filter. A customer
 * asks what their agent found; a provider asks which customers have an agent
 * rolled out that has never produced a verdict — a question that is invisible
 * from inside any one tenant, because the answer is an absence.
 *
 * <p><b>On the counters that read zero.</b> Several of these are zero when
 * healthy and zero when broken, and this session has been bitten by exactly
 * that three times. So the response reports {@code attributed} beside them: a
 * column of zeros with {@code attributed} zero means nothing ran, and the same
 * zeros with {@code attributed} non-zero means it ran and was clean. Reporting
 * one without the other is how a dead pipeline reads as a quiet estate.
 */
@RestController
public class ProviderAgentFleetController {

    /** How far back the run and verdict counts look. */
    private static final Duration WINDOW = Duration.ofDays(7);

    private final JdbcTemplate jdbc;

    public ProviderAgentFleetController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/provider/fleet/agents")
    public Map<String, Object> fleet(@RequestParam(defaultValue = "7") int days,
                                     @AuthenticationPrincipal Jwt jwt) {
        requireProvider(jwt);

        int bounded = Math.max(1, Math.min(days, 90));
        Timestamp since = Timestamp.from(Instant.now().minus(Duration.ofDays(bounded)));

        // One row per (tenant, agent). Joined rather than assembled in Java
        // because the interesting output is a single sortable table and three
        // round trips would have to be stitched back together on exactly these
        // keys anyway.
        List<Map<String, Object>> rows = jdbc.query("""
                SELECT a.tenant_id,
                       a.name                                        AS agent_name,
                       COALESCE(a.graph_ref, a.name)                 AS agent_ref,
                       a.enabled,
                       COUNT(DISTINCT r.id)                          AS runs,
                       SUM(r.status = 'FAILED')                      AS failed_runs,
                       SUM(r.scope_status = 'COMPLETE')              AS complete_coverage,
                       SUM(r.scope_status IS NULL)                   AS silent_coverage,
                       MAX(r.finished_at)                            AS last_run_at
                  FROM agents a
                  LEFT JOIN agent_runs r
                         ON r.agent_id = a.id
                        AND COALESCE(r.finished_at, r.started_at, r.created_at) >= ?
                 GROUP BY a.tenant_id, a.name, a.graph_ref, a.enabled
                 ORDER BY a.tenant_id, a.name
                """,
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("tenant_id", rs.getString("tenant_id"));
                    row.put("agent_name", rs.getString("agent_name"));
                    row.put("agent_ref", rs.getString("agent_ref"));
                    row.put("enabled", rs.getBoolean("enabled"));
                    row.put("runs", rs.getLong("runs"));
                    row.put("failed_runs", rs.getLong("failed_runs"));
                    row.put("complete_coverage", rs.getLong("complete_coverage"));
                    row.put("silent_coverage", rs.getLong("silent_coverage"));
                    row.put("last_run_at", rs.getString("last_run_at"));
                    return row;
                }, since);

        // Verdict attribution, keyed the same way so the console can join them.
        // agent_name here is the RUNTIME's ref, not the display name — the two
        // differ, and confusing them refused every verdict in this platform
        // once already.
        List<Map<String, Object>> attribution = jdbc.query("""
                SELECT tenant_id, agent_name,
                       SUM(attributed)   AS attributed,
                       SUM(unattributed) AS unattributed,
                       SUM(unscoped)     AS unscoped,
                       SUM(out_of_scope) AS out_of_scope,
                       SUM(foreign_run)  AS foreign_run,
                       SUM(late)         AS late
                  FROM verdict_attribution_daily
                 WHERE bucket_day >= DATE_SUB(UTC_DATE(), INTERVAL ? DAY)
                 GROUP BY tenant_id, agent_name
                """,
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("tenant_id", rs.getString("tenant_id"));
                    row.put("agent_ref", rs.getString("agent_name"));
                    row.put("attributed", rs.getLong("attributed"));
                    row.put("unattributed", rs.getLong("unattributed"));
                    row.put("unscoped", rs.getLong("unscoped"));
                    row.put("out_of_scope", rs.getLong("out_of_scope"));
                    row.put("foreign_run", rs.getLong("foreign_run"));
                    row.put("late", rs.getLong("late"));
                    return row;
                }, bounded);

        List<Map<String, Object>> findings = jdbc.query("""
                SELECT tenant_id, agent_name,
                       COUNT(*)                              AS total,
                       SUM(state IN ('OPEN','ACKNOWLEDGED'))  AS open_findings
                  FROM findings
                 GROUP BY tenant_id, agent_name
                """,
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("tenant_id", rs.getString("tenant_id"));
                    row.put("agent_ref", rs.getString("agent_name"));
                    row.put("findings", rs.getLong("total"));
                    row.put("open_findings", rs.getLong("open_findings"));
                    return row;
                });

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("window_days", bounded);
        out.put("agents", rows);
        out.put("attribution", attribution);
        out.put("findings", findings);
        return out;
    }

    /**
     * 403 rather than an empty fleet.
     *
     * <p>Every query above reads across every tenant, so there is no safe
     * narrowing for a non-provider caller — and an empty result would be
     * indistinguishable from a platform where nothing is rolled out.
     */
    private static void requireProvider(Jwt jwt) {
        if (jwt == null || !"PROVIDER".equals(jwt.getClaimAsString("role"))) {
            throw AgentException.forbidden(
                    "provider_only",
                    "The agent fleet view reads across every tenant and is available to the "
                            + "provider operator role only.");
        }
    }
}
