package com.intertec.autoops.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intertec.autoops.agent.domain.AgentRun;
import com.intertec.autoops.agent.exception.AgentException;
import com.intertec.autoops.agent.repo.AgentRunRepository;
import com.intertec.autoops.agent.scope.RunScope;
import com.intertec.autoops.agent.scope.ScopeClaim;
import com.intertec.autoops.agent.scope.SubjectDigest;
import com.intertec.autoops.agent.scope.SubjectScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * What a run says it covered, recorded so that a later run can prove it looked
 * at the same subjects.
 *
 * <p><b>This is the thing that makes the reaper safe to build.</b> Reaping on
 * {@code last_seen_at} alone means one agent outage marks a whole backlog
 * resolved — and that failure is invisible, because a backlog that empties looks
 * exactly like a good week. A finding may only be resolved by absence when a run
 * that <i>demonstrably covered its subject</i> did not re-emit it. The coverage
 * claim is what turns "nobody mentioned it" into "somebody looked and it was
 * gone".
 *
 * <p>The claim is written twice and the asymmetry is deliberate:
 *
 * <ul>
 *   <li>{@link #declare} at start — what the run intends to cover. Enough for
 *       an operator to see what is in flight, not enough to reap.</li>
 *   <li>{@link #complete} at the end — what it actually reached, per subject
 *       kind, which may only ever <b>narrow</b> the start claim.</li>
 * </ul>
 *
 * <p><b>This class is the boundary, not a convenience over one.</b> agent-runtime
 * has a Python module that builds the same shapes, and every rule it applies is
 * applied again here, because an agent that constructs its own HTTP request
 * bypasses the client library entirely. If the two ever disagree, this one is
 * right.
 */
@Service
public class RunScopeService {

    private static final Logger log = LoggerFactory.getLogger(RunScopeService.class);

    /**
     * How long a run may sit in RUNNING before it is presumed abandoned.
     *
     * <p>Generous on purpose. A run parked on a human approval is not abandoned,
     * and sweeping it early costs a real coverage claim; sweeping it late costs
     * nothing at all, because a FAILED scope and a RUNNING one reap identically
     * — which is to say, neither does.
     */
    private static final Duration ABANDONED_AFTER = Duration.ofHours(24);

    private final AgentRunRepository runs;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;

    public RunScopeService(AgentRunRepository runs, JdbcTemplate jdbc,
                           TransactionTemplate tx, ObjectMapper mapper) {
        this.runs = runs;
        this.jdbc = jdbc;
        this.tx = tx;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------ declare ---

    /**
     * Records what a run set out to cover.
     *
     * @param subjectIds the actual ids behind every {@code enumerated} scope,
     *                   keyed by subject kind. Verified against the digest the
     *                   scope carries — see {@link #materialise}.
     */
    public void declare(Long runId, JsonNode scope, Map<String, List<String>> subjectIds) {
        RunScope declared = RunScope.parse(scope);
        for (ScopeClaim claim : declared.claims()) {
            if (claim.coverage() != null) {
                throw AgentException.badRequest("scope_coverage_at_declaration",
                        "A scope declared at start cannot carry a coverage verdict — nothing "
                                + "has happened to it yet. Send coverage at completion.");
            }
        }
        tx.executeWithoutResult(status -> {
            AgentRun run = load(runId);
            run.setSubjectScope(declared.toJson(mapper).toString());
            run.setScopeStatus(AgentRun.ScopeStatus.RUNNING);
            runs.save(run);
            materialise(runId, declared, subjectIds);
        });
    }

    // ----------------------------------------------------------- complete ---

    /**
     * Records what a run actually reached, per subject kind, and whether each
     * kind's claim can be trusted.
     *
     * <p><b>Why this method drives its own transactions rather than carrying
     * {@code @Transactional}.</b> It has to <i>persist</i> a rejection and then
     * throw it. An annotated method that throws rolls back its own work,
     * including the record saying why the claim was refused — the run would sit
     * in RUNNING, be swept to FAILED a day later by {@link #sweepAbandoned}, and
     * arrive at the same state with the reason gone. Do not "simplify" this back
     * into an annotation.
     *
     * @param evaluatedByKind how many subjects of each kind were actually
     *                        examined, keyed as the scope is ({@link RunScope#EVERY_KIND}
     *                        for a bare {@code all}). Cross-checked against the
     *                        coverage verdicts — see
     *                        {@link RunScope#checkCoherent}.
     * @throws AgentException if the completion scope is wider than the start
     *                        scope, leaves a declared kind unaccounted for, or
     *                        claims coverage its own numbers contradict. The run
     *                        is marked FAILED first, so the reason survives the
     *                        throw.
     */
    public void complete(Long runId, JsonNode scope, Map<String, List<String>> subjectIds,
                         Map<String, Integer> evaluatedByKind, int verdictsEmitted) {
        RunScope start = startScopeOf(runId);
        RunScope end;
        try {
            end = RunScope.parse(scope);
            end.checkNarrows(start);
            end.checkCoherent(evaluatedByKind);
        } catch (AgentException e) {
            markFailed(runId, e.getMessage());
            throw e;
        }

        int totalEvaluated = evaluatedByKind.values().stream().mapToInt(Integer::intValue).sum();
        tx.executeWithoutResult(status -> {
            AgentRun run = load(runId);
            run.setSubjectScope(end.toJson(mapper).toString());
            run.setSubjectsEvaluated(totalEvaluated);
            run.setVerdictsEmitted(verdictsEmitted);
            run.setScopeStatus(rollUp(end));
            runs.save(run);

            // The start scope's subjects described an intent; these describe
            // what was reached. Replacing rather than merging is what keeps the
            // table meaning one thing.
            jdbc.update("DELETE FROM agent_run_subject WHERE run_id = ?", runId);
            materialise(runId, end, subjectIds);
        });
    }

    /**
     * The run-level roll-up of per-element coverage.
     *
     * <p>Only a summary — <b>the element's own verdict is what decides whether
     * that kind reaps</b>. This exists so the reaper can find candidate runs on
     * an index instead of parsing JSON for every run in the window, and so an
     * operator reading the run list sees at a glance that something was missed.
     */
    private static AgentRun.ScopeStatus rollUp(RunScope end) {
        boolean all = end.claims().stream()
                .allMatch(claim -> claim.coverage() == ScopeClaim.Coverage.COMPLETE);
        return all ? AgentRun.ScopeStatus.COMPLETE : AgentRun.ScopeStatus.PARTIAL;
    }

    /** Marks a run's coverage untrustworthy, in its own transaction. */
    public void markFailed(Long runId, String why) {
        tx.executeWithoutResult(status -> {
            AgentRun run = load(runId);
            run.setScopeStatus(AgentRun.ScopeStatus.FAILED);
            runs.save(run);
        });
        log.warn("run {} coverage claim rejected: {}", runId, why);
    }

    // ------------------------------------------------------------- sweeps ---

    /**
     * Fails runs that never said how they ended.
     *
     * <p>A process killed mid-sweep leaves RUNNING behind forever. RUNNING and
     * FAILED reap identically — neither does — so this changes no behaviour;
     * what it buys is that {@link #coverageGaps} can tell a stuck agent from a
     * busy one, which is the signal that decides whether the reaper may be
     * switched on at all.
     */
    public int sweepAbandoned(Instant now) {
        Instant cutoff = now.minus(ABANDONED_AFTER);
        int swept = jdbc.update("""
                UPDATE agent_runs
                   SET scope_status = 'FAILED'
                 WHERE scope_status = 'RUNNING'
                   AND COALESCE(started_at, created_at) < ?
                """, java.sql.Timestamp.from(cutoff));
        if (swept > 0) {
            log.warn("swept {} run(s) abandoned in RUNNING before {}", swept, cutoff);
        }
        return swept;
    }

    // ------------------------------------------------------------- gauges ---

    /**
     * How much of an agent's recent work carries a coverage claim at all.
     *
     * <p><b>Read this before enabling the reaper, and keep reading it after.</b>
     * The reaper is only as safe as the proportion of runs that declare their
     * scope, and the number that says so has to exist before the switch does —
     * otherwise the first evidence is a backlog that emptied itself.
     *
     * <p><b>What it does not tell you.</b> This proves every agent
     * <i>declares</i> a scope. It says nothing about whether any scope is
     * <i>correct</i>. An agent declaring {@code all} while enumerating 40% of
     * the estate reads as a perfect zero here and reaps two thirds of its
     * backlog on the first run. {@link #overclaimSuspects} is the other half.
     */
    public List<CoverageGap> coverageGaps(String tenantId, Instant since) {
        return jdbc.query("""
                SELECT agent_id,
                       COUNT(*)                        AS total,
                       SUM(scope_status = 'COMPLETE')  AS trustworthy,
                       SUM(scope_status IS NULL)       AS silent
                  FROM agent_runs
                 WHERE tenant_id = ?
                   AND COALESCE(finished_at, started_at, created_at) >= ?
                 GROUP BY agent_id
                """,
                (rs, row) -> new CoverageGap(
                        rs.getLong("agent_id"),
                        rs.getLong("total"),
                        rs.getLong("trustworthy"),
                        rs.getLong("silent")),
                tenantId, java.sql.Timestamp.from(since));
    }

    /**
     * @param silent runs that declared nothing at all — an agent not yet updated
     *               to populate its scope, which is a different problem from one
     *               that keeps failing to narrow and needs a different fix.
     */
    public record CoverageGap(long agentId, long total, long trustworthy, long silent) {
        public double trustworthyFraction() {
            return total == 0 ? 0 : (double) trustworthy / total;
        }
    }

    /**
     * Runs that claimed to sweep everything while examining very little.
     *
     * <p>The over-broad claim is the failure the coverage gauge cannot see, and
     * this is the cheapest honest look at it: for a run whose scope is a bare
     * {@code all} and whose verdict is COMPLETE, compare the subjects it says it
     * examined against the distinct subjects that already have open findings for
     * that agent. A run entitled to resolve nine hundred subjects' findings on
     * the strength of forty examinations is not necessarily wrong — but it is
     * always worth a look.
     *
     * <p><b>A gauge and not a gate, for a specific reason.</b> A resource deleted
     * between runs leaves an open finding with no subject left to examine, which
     * is exactly what the reaper is for. So examined-fewer-than-resolved is the
     * normal case as well as the pathological one, and the two are told apart by
     * degree and by somebody looking — not by a threshold nobody can justify.
     *
     * <p><b>Do not delete this once scopes are derived from tool output.</b> A
     * derived {@code enumerated} scope cannot overclaim — the ids come from what
     * a tool actually returned, not from a model's account of it — and that is
     * genuinely most of the surface area. But {@code all} and
     * {@code dimensional} scopes are still <i>authored</i>, and an agent
     * declaring {@code all} while examining a fraction of the estate is exactly
     * as dangerous as it ever was. This is the only cover for the authored
     * case.
     */
    public List<Overclaim> overclaimSuspects(String tenantId, Instant since) {
        return jdbc.query("""
                SELECT r.id                AS run_id,
                       r.agent_id          AS agent_id,
                       r.subjects_evaluated AS evaluated,
                       (SELECT COUNT(DISTINCT f.subject_id_hash)
                          FROM findings f
                         WHERE f.tenant_id = r.tenant_id
                           -- graph_ref, not name: findings.agent_name carries
                           -- what the RUNTIME stamped (aws.incident_rca_analyst),
                           -- while agents.name is the display name a customer
                           -- sees. Joining on name matches nothing and this
                           -- gauge silently reads zero forever.
                           AND f.agent_name = COALESCE(a.graph_ref, a.name)
                           AND f.state IN ('OPEN','ACKNOWLEDGED')) AS in_scope
                  FROM agent_runs r
                  JOIN agents a ON a.id = r.agent_id
                 WHERE r.tenant_id = ?
                   AND r.scope_status = 'COMPLETE'
                   AND JSON_CONTAINS(r.subject_scope, JSON_OBJECT('kind', 'all'))
                   AND COALESCE(r.finished_at, r.started_at, r.created_at) >= ?
                """,
                (rs, row) -> new Overclaim(
                        rs.getLong("run_id"),
                        rs.getLong("agent_id"),
                        rs.getLong("evaluated"),
                        rs.getLong("in_scope")),
                tenantId, java.sql.Timestamp.from(since));
    }

    /** @param inScope distinct subjects with open findings this run could resolve. */
    public record Overclaim(long runId, long agentId, long subjectsEvaluated, long inScope) {
        public boolean worthALook() {
            return inScope > 0 && subjectsEvaluated < inScope;
        }
    }

    // ------------------------------------------------------------ helpers ---

    /**
     * Writes the ids behind every enumerated scope, after checking they are the
     * ids the scope says they are.
     *
     * <p>The digest check is the only thing standing between a mismatched pair
     * and a silent one. If the count or the digest disagrees with the list, the
     * scope and the rows describe different sets, and the reaper would trust
     * whichever it read — resolving findings for subjects nobody visited. It
     * costs one hash of a sorted list per kind.
     */
    private void materialise(Long runId, RunScope scope, Map<String, List<String>> subjectIds) {
        for (SubjectScope element : scope.scopes()) {
            if (!(element instanceof SubjectScope.Enumerated enumerated)) {
                continue;
            }
            List<String> ids = subjectIds == null
                    ? null : subjectIds.get(enumerated.subjectKind());
            if (ids == null) {
                throw AgentException.badRequest("scope_subjects_missing",
                        "The scope enumerates " + enumerated.subjectIdCount() + " subjects of "
                                + "kind '" + enumerated.subjectKind() + "' but did not send them. "
                                + "An enumerated scope the reaper cannot join to is a claim it "
                                + "has to ignore.");
            }
            Collection<String> distinct = new TreeSet<>();
            ids.forEach(id -> distinct.add(SubjectDigest.requireWellFormed(id)));
            if (distinct.size() != enumerated.subjectIdCount()) {
                throw AgentException.badRequest("scope_subject_count_mismatch",
                        "The scope for '" + enumerated.subjectKind() + "' claims "
                                + enumerated.subjectIdCount() + " subjects and "
                                + distinct.size() + " distinct ids arrived.");
            }
            String digest = SubjectDigest.digest(distinct);
            if (!digest.equals(enumerated.subjectIdsDigest())) {
                throw AgentException.badRequest("scope_subject_digest_mismatch",
                        "The subjects sent for '" + enumerated.subjectKind() + "' do not hash to "
                                + "the digest the scope carries, so the two describe different "
                                + "sets and neither can be trusted.");
            }

            List<Object[]> batch = new ArrayList<>(distinct.size());
            for (String id : distinct) {
                batch.add(new Object[] {runId, enumerated.subjectKind(), SubjectDigest.hash(id)});
            }
            jdbc.batchUpdate("""
                    INSERT INTO agent_run_subject (run_id, subject_kind, subject_id_hash)
                    VALUES (?, ?, ?)
                    """, batch);
        }
    }

    private RunScope startScopeOf(Long runId) {
        AgentRun run = load(runId);
        if (run.getSubjectScope() == null) {
            throw AgentException.badRequest("scope_never_declared",
                    "This run never declared what it set out to cover, so there is nothing for "
                            + "its completion claim to narrow.");
        }
        try {
            return RunScope.parse(mapper.readTree(run.getSubjectScope()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw AgentException.internal("scope_unreadable",
                    "The stored scope for run " + runId + " is not readable JSON.");
        }
    }

    private AgentRun load(Long runId) {
        return runs.findById(runId).orElseThrow(() ->
                AgentException.notFound("run_not_found", "No run " + runId + "."));
    }
}
