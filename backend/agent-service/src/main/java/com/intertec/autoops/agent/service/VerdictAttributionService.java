package com.intertec.autoops.agent.service;

import com.intertec.autoops.agent.domain.AgentRun;
import com.intertec.autoops.agent.repo.AgentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intertec.autoops.agent.repo.AgentRunRepository;
import com.intertec.autoops.agent.scope.RunScope;
import com.intertec.autoops.agent.scope.ScopeClaim;
import com.intertec.autoops.agent.scope.SubjectDigest;
import com.intertec.autoops.agent.scope.SubjectScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * Whether a verdict can be traced to the run that produced it.
 *
 * <p><b>This is phase A of making {@code run_id} mandatory.</b> Doing it in one
 * step would be a breaking change to the ingest contract across three
 * codebases, so by {@code backend/MIGRATIONS.md} it is expand/contract:
 *
 * <ol>
 *   <li><b>A</b> — accept verdicts with or without a run, count both. Ships
 *       before any agent changes.</li>
 *   <li><b>B</b> — agents open runs and pass {@code run_id}. The unattributed
 *       count falls to zero per agent as each ships, and the coverage gauge
 *       stops reading 100% silent. The two move together, which is what makes
 *       progress legible.</li>
 *   <li><b>C</b> — reject run-less verdicts. Only once A's count has been zero
 *       across a full cycle of the <i>slowest</i> agent.</li>
 * </ol>
 *
 * <p><b>Two things are refused from day one</b>, because they are never
 * legitimate at any phase and retrofitting them would mean either breaking
 * existing data or grandfathering a hole:
 *
 * <ul>
 *   <li><b>A foreign run.</b> A verdict's run must belong to the same tenant and
 *       to the agent the verdict names. Without this, agent A can emit under
 *       agent B's run and be reaped against a scope that never covered it — the
 *       silent-resolution failure two migrations went into closing, arriving
 *       through the front door.</li>
 *   <li><b>A verdict after its run reported completion.</b> The completion
 *       claim already counted its subjects and was validated for coherence; a
 *       straggler is claiming coverage retroactively for a subject that
 *       validation never saw. It gets its own count and its own error code, so
 *       the gauge can tell <i>this agent has not been updated</i> apart from
 *       <i>this agent raced its own completion</i> — different bugs, different
 *       fixes.</li>
 * </ul>
 */
@Service
public class VerdictAttributionService {

    private static final Logger log = LoggerFactory.getLogger(VerdictAttributionService.class);

    /** What happened when the verdict's run was looked up. */
    public enum Attribution {
        /** Carries a run that exists, belongs here, and is still open. */
        ATTRIBUTED("attributed"),
        /** Carries no run at all — tolerated in phase A, counted, rejected in C. */
        UNATTRIBUTED("unattributed"),
        /**
         * Carries a good run that has not declared what it covers.
         *
         * <p>Half-wired: the agent learned to pass a run id and not to declare a
         * scope. Counted apart from {@link #UNATTRIBUTED} because otherwise it
         * hides — the attribution gauge reads healthy while the coverage gauge
         * stays silent, and nothing connects the two.
         */
        UNSCOPED("unscoped"),
        /**
         * About a subject the run's own scope says it never examined.
         *
         * <p>A contradiction, and the only check here that does not depend on
         * the scope being derived correctly — see V11.
         */
        OUT_OF_SCOPE("out_of_scope"),
        /** Names a run of another tenant or another agent, or no run at all. */
        FOREIGN_RUN("foreign_run"),
        /** Arrived after its run reported completion. */
        LATE("late");

        private final String column;

        Attribution(String column) {
            this.column = column;
        }

        public boolean acceptable() {
            return this == ATTRIBUTED || this == UNATTRIBUTED || this == UNSCOPED;
        }
    }

    private final AgentRunRepository runs;
    private final AgentRepository agents;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public VerdictAttributionService(AgentRunRepository runs, AgentRepository agents,
                                     JdbcTemplate jdbc, ObjectMapper mapper) {
        this.runs = runs;
        this.agents = agents;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /**
     * Classifies one verdict and records it against today's counters.
     *
     * <p>Counting happens for every outcome including the refused ones —
     * a rejection nobody counts is a rejection nobody discovers.
     */
    public Attribution classify(String tenantId, String agentName, Long runId,
                                String subjectKind, String subjectId, Instant now) {
        Attribution attribution = decide(tenantId, agentName, runId, subjectKind, subjectId);
        record(tenantId, agentName, attribution, now);
        if (!attribution.acceptable()) {
            log.warn("verdict from agent '{}' in tenant {} names run {}: {}",
                    agentName, tenantId, runId, attribution);
        }
        return attribution;
    }

    private Attribution decide(String tenantId, String agentName, Long runId,
                               String subjectKind, String subjectId) {
        if (runId == null) {
            return Attribution.UNATTRIBUTED;
        }
        Optional<AgentRun> found = runs.findById(runId);
        if (found.isEmpty()) {
            // Indistinguishable from a made-up id, and treated as one. A run
            // purged while its verdicts were in flight lands here too, which is
            // the right side to err on: an unresolvable run cannot ground a
            // coverage claim either way.
            return Attribution.FOREIGN_RUN;
        }
        AgentRun run = found.get();
        // TAUTOLOGICAL ON THE INTERNAL PATH, and kept anyway.
        //
        // AgentRunService calls ingest with run.getTenantId(), so this compares
        // that value to itself and can never fail there. It is real defence for
        // the REST path that does not exist yet, where the tenant comes from the
        // caller's token and the run id from the request body — the one place a
        // verdict could name a run belonging to somebody else.
        //
        // Stated because a check that cannot fail reads as a check that passed,
        // and somebody counting tenant isolation guarantees should know this one
        // is not currently exercised by production traffic.
        // FindingIngestServiceTest.aVerdictNamingAnotherTenantsRunIsRefused
        // drives it non-tautologically.
        if (!run.getTenantId().equals(tenantId)) {
            return Attribution.FOREIGN_RUN;
        }
        if (!ownedBy(run, agentName)) {
            return Attribution.FOREIGN_RUN;
        }
        if (isClosed(run)) {
            return Attribution.LATE;
        }
        if (run.getSubjectScope() == null) {
            // The achievable form of "a run without a scope is impossible".
            // Declaration cannot happen at run start — a scope enumerates
            // subjects the agent has not fetched yet — so it is enforced here,
            // at the first moment the run makes a claim that could be reaped.
            return Attribution.UNSCOPED;
        }
        return withinScope(run, subjectKind, subjectId)
                ? Attribution.ATTRIBUTED
                : Attribution.OUT_OF_SCOPE;
    }

    /**
     * Whether the run's own coverage claim admits having examined this subject.
     *
     * <p><b>The one check here that survives a wrong extraction.</b> Everything
     * else about a scope is self-consistent by construction: the count matches
     * the digest, the digest matches the rows, the coverage verdict matches the
     * count. All of that stays true when a scope describes a smaller set than
     * the run actually examined, which is precisely what a truncated tool result
     * produces. A verdict about a subject outside the claim is a contradiction
     * detectable without knowing what the right claim was — and knowing that is
     * the thing we do not have.
     *
     * <p>Only {@code enumerated} elements can be checked, because only they have
     * subjects to check against. {@code all} and {@code dimensional} admit
     * anything of their kind by definition, which is the same reason
     * {@code overclaimSuspects} exists for them.
     */
    private boolean withinScope(AgentRun run, String subjectKind, String subjectId) {
        if (subjectKind == null || subjectId == null || subjectId.isEmpty()) {
            // Nothing to contradict. A verdict that names no subject is refused
            // earlier for having no stable key.
            return true;
        }
        RunScope scope;
        try {
            scope = RunScope.parse(mapper.readTree(run.getSubjectScope()));
        } catch (Exception unreadable) {
            // A scope nobody can read grounds no reap either way, so this must
            // not also start rejecting the run's findings.
            log.warn("run {} has an unreadable scope; not checking subject bounds", run.getId());
            return true;
        }
        if (scope.isEverything()) {
            return true;
        }
        ScopeClaim claim = scope.byKind().get(subjectKind);
        if (claim == null) {
            // A verdict about a kind the run never claimed to cover at all.
            return false;
        }
        if (!(claim.scope() instanceof SubjectScope.Enumerated)) {
            return true;
        }
        Integer found = jdbc.queryForObject("""
                SELECT COUNT(*) FROM agent_run_subject
                 WHERE run_id = ? AND subject_kind = ? AND subject_id_hash = ?
                """, Integer.class, run.getId(), subjectKind, SubjectDigest.hash(subjectId));
        return found != null && found > 0;
    }

    /**
     * Whether the run's agent is the one the verdict names.
     *
     * <p><b>Matches the GRAPH REF first, and that is the whole point.</b> The
     * runtime stamps every verdict with {@code spec.ref} — {@code
     * aws.incident_rca_analyst} — while {@code agents.name} is the display name
     * a customer sees, {@code "AWS Incident RCA Analyst"}. Comparing those two
     * can never succeed, so every verdict from every Python agent was classified
     * FOREIGN_RUN and refused. The first live run in this environment produced
     * exactly one verdict and exactly one {@code foreign_run}.
     *
     * <p>That failure is worse than it sounds because {@code foreign_run} means
     * "an agent is emitting under another agent's coverage claim" — a security-
     * shaped reading that would have sent somebody looking for a tenancy bug.
     *
     * <p>The name is still accepted as a fallback for agents with no graph ref:
     * the legacy JSON path, where the runtime reports the generic shim's ref
     * rather than anything identifying. Those still will not match, and are
     * still refused — an honest gap rather than a papered-over one, because a
     * JSON agent's verdicts cannot be attributed to it from what it sends.
     */
    private boolean ownedBy(AgentRun run, String agentName) {
        return agents.findById(run.getAgentId())
                .map(agent -> agentName.equals(agent.getGraphRef())
                        || agentName.equals(agent.getName()))
                .orElse(false);
    }

    /**
     * A run whose coverage claim is already settled.
     *
     * <p>Two conditions rather than one, because they become true at different
     * times and either alone leaves a gap. {@code scope_status} past RUNNING
     * means completion validation has already counted the subjects — that is the
     * claim a straggler would be retroactively altering. {@code finished_at}
     * covers phase A, where no agent declares a scope yet and {@code
     * scope_status} is null for every run.
     */
    private static boolean isClosed(AgentRun run) {
        AgentRun.ScopeStatus scope = run.getScopeStatus();
        boolean settled = scope != null && scope != AgentRun.ScopeStatus.RUNNING;
        return settled || run.getFinishedAt() != null;
    }

    /**
     * Increments today's counter, creating the row if this is the first verdict.
     *
     * <p><b>Update-then-insert rather than {@code ON DUPLICATE KEY UPDATE}</b>,
     * which is MySQL-only: H2 rejects it even in MySQL mode, so the whole
     * counter would be exercised for the first time in production. The portable
     * form costs one extra round trip on the first verdict of a day and is
     * covered by the same tests as everything else.
     *
     * <p>The insert races: two verdicts arriving together when the row does not
     * yet exist both try to create it, and one loses. Losing is fine and is
     * handled rather than prevented — the loser simply applies its increment to
     * the row the winner created. Preventing it would mean a lock held across
     * every ingest, to protect a counter.
     *
     * <p>The column name is chosen from a closed enum and never from input.
     */
    private void record(String tenantId, String agentName, Attribution attribution, Instant now) {
        LocalDate day = now.atZone(ZoneOffset.UTC).toLocalDate();
        String column = attribution.column;
        java.sql.Date bucket = java.sql.Date.valueOf(day);

        if (bump(column, tenantId, agentName, bucket) > 0) {
            return;
        }
        try {
            jdbc.update("""
                    INSERT INTO verdict_attribution_daily
                           (tenant_id, agent_name, bucket_day, %s)
                    VALUES (?, ?, ?, 1)
                    """.formatted(column), tenantId, agentName, bucket);
        } catch (org.springframework.dao.DuplicateKeyException raced) {
            bump(column, tenantId, agentName, bucket);
        }
    }

    private int bump(String column, String tenantId, String agentName, java.sql.Date bucket) {
        return jdbc.update("""
                UPDATE verdict_attribution_daily SET %s = %s + 1
                 WHERE tenant_id = ? AND agent_name = ? AND bucket_day = ?
                """.formatted(column, column), tenantId, agentName, bucket);
    }

    // -------------------------------------------------------------- gauge ---

    /**
     * What still cannot be traced to a run, per agent.
     *
     * <p><b>The number that decides when phase C may ship.</b> Read it over a
     * window at least as long as the slowest agent's cadence: a nightly agent
     * proves itself in a day, and a monthly FinOps sweep does not.
     */
    public List<Attributed> byAgent(String tenantId, LocalDate since) {
        return jdbc.query("""
                SELECT agent_name,
                       SUM(attributed)   AS attributed,
                       SUM(unattributed) AS unattributed,
                       SUM(unscoped)     AS unscoped,
                       SUM(out_of_scope) AS out_of_scope,
                       SUM(foreign_run)  AS foreign_run,
                       SUM(late)         AS late
                  FROM verdict_attribution_daily
                 WHERE tenant_id = ? AND bucket_day >= ?
                 GROUP BY agent_name
                """,
                (rs, row) -> new Attributed(
                        rs.getString("agent_name"),
                        rs.getLong("attributed"),
                        rs.getLong("unattributed"),
                        rs.getLong("unscoped"),
                        rs.getLong("out_of_scope"),
                        rs.getLong("foreign_run"),
                        rs.getLong("late")),
                tenantId, java.sql.Date.valueOf(since));
    }

    /**
     * @param foreignRun never expected; non-zero means an agent is emitting
     *                   under somebody else's coverage claim, which is a
     *                   different and worse problem than one lagging a rollout
     */
    public record Attributed(String agentName, long attributed, long unattributed,
                             long unscoped, long outOfScope, long foreignRun, long late) {

        /** True when this agent is ready for phase C on its own. */
        public boolean fullyAttributed() {
            return unattributed == 0 && unscoped == 0 && outOfScope == 0
                    && foreignRun == 0 && late == 0 && attributed > 0;
        }

        /**
         * The half-wired signature: passing a run id without declaring a scope.
         *
         * <p>Worth its own predicate because it is the failure that looks like
         * progress — the attribution count moves while coverage stays silent.
         */
        public boolean halfWired() {
            return unscoped > 0;
        }
    }
}
