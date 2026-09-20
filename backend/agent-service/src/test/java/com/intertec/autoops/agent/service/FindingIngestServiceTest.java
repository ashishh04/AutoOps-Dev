package com.intertec.autoops.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intertec.autoops.agent.domain.Finding;
import com.intertec.autoops.agent.domain.FindingSuppression;
import com.intertec.autoops.agent.domain.Agent;
import com.intertec.autoops.agent.domain.AgentRun;
import com.intertec.autoops.agent.repo.AgentRepository;
import com.intertec.autoops.agent.repo.AgentRunRepository;
import com.intertec.autoops.agent.repo.FindingObservationRepository;
import com.intertec.autoops.agent.repo.FindingRepository;
import com.intertec.autoops.agent.repo.FindingSuppressionRepository;
import com.intertec.autoops.agent.repo.FindingTransitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The disposition table, which is the contract.
 *
 * <p>Everything downstream — dedupe rates, dismissal churn, materiality
 * tuning, agent drift — becomes measurable the moment an agent is told what
 * happened to what it emitted, and stays anecdotal until then. So these tests
 * are about the DISPOSITION rather than about rows: what an agent is told, and
 * whether a human is interrupted.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Import({FindingIngestService.class, VerdictAttributionService.class,
        RunScopeServiceTest.Beans.class})
class FindingIngestServiceTest {

    private static final String TENANT = "acme";
    private static final Long PROJECT = 9L;
    private static final String AGENT_NAME = "aws.idle_resource_reclaimer";

    private Long openRunId;

    @Autowired
    private FindingIngestService service;
    @Autowired
    private FindingRepository findings;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private AgentRunRepository agentRuns;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private VerdictAttributionService attributions;
    @Autowired
    private TestEntityManager entities;
    @Autowired
    private FindingSuppressionRepository suppressions;
    @Autowired
    private FindingObservationRepository observations;
    @Autowired
    private FindingTransitionRepository transitions;

    private final ObjectMapper mapper = new ObjectMapper();
    private int verdictCounter;

    @BeforeEach
    void reset() {
        verdictCounter = 0;

        // @DataJpaTest builds its schema from the JPA entities, so a table that
        // only a Flyway migration creates does not exist here. This one is a
        // counter with a composite key and an upsert, which is worse as an entity
        // than as a few lines of SQL, so it is created by hand here; the real DDL
        // is asserted against a real MySQL in SchemaInvariantsIT.
        jdbc.execute("CREATE TABLE IF NOT EXISTS verdict_attribution_daily ("
                + "tenant_id VARCHAR(64) NOT NULL, agent_name VARCHAR(128) NOT NULL, "
                + "bucket_day DATE NOT NULL, attributed BIGINT NOT NULL DEFAULT 0, "
                + "unattributed BIGINT NOT NULL DEFAULT 0, unscoped BIGINT NOT NULL DEFAULT 0, "
                + "out_of_scope BIGINT NOT NULL DEFAULT 0, "
                + "foreign_run BIGINT NOT NULL DEFAULT 0, late BIGINT NOT NULL DEFAULT 0, "
                + "PRIMARY KEY (tenant_id, agent_name, bucket_day))");
        jdbc.update("DELETE FROM verdict_attribution_daily");
        jdbc.execute("CREATE TABLE IF NOT EXISTS agent_run_subject ("
                + "run_id BIGINT NOT NULL, subject_kind VARCHAR(64) NOT NULL, "
                + "subject_id_hash VARBINARY(32) NOT NULL, "
                + "PRIMARY KEY (run_id, subject_kind, subject_id_hash))");
        jdbc.update("DELETE FROM agent_run_subject");

        // A real agent and a real open run, because ingest now validates that a
        // verdict's run belongs to the agent that emitted it. Passing a run id
        // nobody owns would make every test below exercise the refusal path
        // rather than the disposition table it is about.
        Agent agent = new Agent();
        agent.setTenantId(TENANT);
        agent.setProjectId(PROJECT);
        // The DISPLAY name differs from the graph ref, exactly as it does in
        // production — and the verdicts name the graph ref. An agent whose two
        // names were identical would have hidden the mismatch that refused every
        // live verdict as foreign_run.
        agent.setName("AWS Idle Resource Reclaimer");
        agent.setGraphRef(AGENT_NAME);
        agent = agents.save(agent);

        AgentRun run = new AgentRun();
        run.setTenantId(TENANT);
        run.setProjectId(PROJECT);
        run.setAgentId(agent.getId());
        run.setInput("{}");
        run.setStatus(AgentRun.Status.RUNNING);
        // Declared, because ingest now distinguishes a run that said what it
        // covers from one that has not got round to it.
        run.setSubjectScope("[{\"kind\":\"all\"}]");
        run.setScopeStatus(AgentRun.ScopeStatus.RUNNING);
        openRunId = agentRuns.save(run).getId();
    }

    /** A verdict in the shape agent-runtime emits. */
    private JsonNode verdict(String severity, String subjectId) {
        verdictCounter++;
        String json = """
                {
                  "verdict_id": "v-%d",
                  "idempotency_key": "key-%s",
                  "stable_key": true,
                  "agent": { "name": "aws.idle_resource_reclaimer", "version": "1.0.0" },
                  "subject": { "kind": "cloud_resource", "id": "%s" },
                  "finding": { "type": "idle_resource", "severity": "%s",
                               "summary": "unattached for a long time" },
                  "autonomy": { "requested_tier": "suggest" },
                  "evidence": [ { "label": "run at %d", "value": "noise that differs every run" } ]
                }
                """.formatted(verdictCounter, subjectId, subjectId, severity, System.nanoTime());
        try {
            return mapper.readTree(json);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * The findings AS STORED, not as the session remembers them.
     *
     * <p>{@code findAll()} runs the query but resolves each row to the instance
     * already managed in the persistence context, so its field values come from
     * the session rather than the database. A persistence test that never
     * re-reads is testing the object graph — which is exactly how an H2 column
     * defect hid in the scope tests until something cleared the context.
     */
    private List<Finding> stored() {
        entities.flush();
        entities.clear();
        return findings.findAll();
    }

    private FindingIngestService.Result ingest(JsonNode verdict) {
        return service.ingest(TENANT, PROJECT, openRunId, verdict);
    }

    // ------------------------------------------------------------- creating ---

    @Test
    void aNewKeyCreatesAFindingAndIsWorthTelling() {
        FindingIngestService.Result result = ingest(verdict("medium", "vol-1"));

        assertThat(result.disposition()).isEqualTo(FindingIngestService.Disposition.CREATED);
        assertThat(result.state()).isEqualTo(Finding.State.OPEN);
        assertThat(result.occurrenceCount()).isEqualTo(1);
        assertThat(result.episodeCount()).isEqualTo(1);
        assertThat(result.shouldNotify()).isTrue();
    }

    /**
     * The point of the whole store. A nightly sweep re-reports what it already
     * reported, and the second sighting must be a counter rather than a new
     * problem — even though the evidence text differs every run.
     */
    @Test
    void theSameFindingSeenAgainIsACounterNotANewProblem() {
        ingest(verdict("medium", "vol-1"));
        FindingIngestService.Result again = ingest(verdict("medium", "vol-1"));

        assertThat(again.disposition()).isEqualTo(FindingIngestService.Disposition.NO_CHANGE);
        assertThat(again.occurrenceCount()).isEqualTo(2);
        assertThat(again.shouldNotify()).isFalse();
        assertThat(findings.count()).isEqualTo(1);
    }

    /**
     * Evidence excerpts carry timestamps and free text that differ on every
     * run. Hashing them would make every sighting material, which reopens
     * every dismissed finding nightly and teaches people to dismiss
     * permanently.
     */
    @Test
    void noisyEvidenceDoesNotMakeASightingMaterial() {
        ingest(verdict("medium", "vol-1"));
        assertThat(ingest(verdict("medium", "vol-1")).material()).isFalse();
    }

    @Test
    void aChangeOfSubstanceIsAnUpdate() {
        ingest(verdict("medium", "vol-1"));
        FindingIngestService.Result worse = ingest(verdict("critical", "vol-1"));

        assertThat(worse.disposition()).isEqualTo(FindingIngestService.Disposition.UPDATED);
        assertThat(worse.material()).isTrue();
        // An escalation is worth an interruption; a sideways move would not be.
        assertThat(worse.shouldNotify()).isTrue();
    }

    @Test
    void anObservationIsNotWrittenForEverySighting() {
        ingest(verdict("medium", "vol-1"));      // created  -> observation
        ingest(verdict("medium", "vol-1"));      // no change -> none
        ingest(verdict("medium", "vol-1"));      // no change -> none
        ingest(verdict("critical", "vol-1"));    // material  -> observation

        assertThat(observations.count())
                .as("a row per sighting buys nothing the counters do not already carry")
                .isEqualTo(2);
    }

    // -------------------------------------------------------------- replays ---

    @Test
    void thesameVerdictIngestedTwiceIsARetryNotASighting() {
        JsonNode once = verdict("medium", "vol-1");
        ingest(once);
        FindingIngestService.Result replay = ingest(once);

        assertThat(replay.disposition())
                .isEqualTo(FindingIngestService.Disposition.DUPLICATE_VERDICT);
        // The easiest way to make every counter lie is to count a network
        // retry as a fresh observation.
        assertThat(stored().get(0).getOccurrenceCount()).isEqualTo(1);
    }

    // ---------------------------------------------------------- suppression ---

    private FindingSuppression dismiss(FindingSuppression.Scope scope, Instant expiresAt,
                                       boolean survivesMaterialChange) {
        FindingSuppression suppression = new FindingSuppression();
        suppression.setTenantId(TENANT);
        suppression.setScope(scope);
        suppression.setCategory("idle_resource");
        suppression.setSubjectId("vol-1");
        suppression.setSubjectKind("cloud_resource");
        suppression.setServiceRef("svc-checkout");
        suppression.setReason("accepted for this quarter");
        suppression.setReasonCode(FindingSuppression.ReasonCode.ACCEPTED_RISK);
        suppression.setCreatedBy("p.nair");
        suppression.setCreatedAt(Instant.now());
        suppression.setExpiresAt(expiresAt);
        suppression.setSurvivesMaterialChange(survivesMaterialChange);
        return suppressions.save(suppression);
    }

    @Test
    void aDismissedFindingStaysQuietWhenNothingChanges() {
        ingest(verdict("medium", "vol-1"));
        dismiss(FindingSuppression.Scope.SUBJECT_CATEGORY,
                Instant.now().plus(Duration.ofDays(30)), false);
        stored().forEach(f -> {
            f.setState(Finding.State.SUPPRESSED);
            findings.save(f);
        });

        FindingIngestService.Result quiet = ingest(verdict("medium", "vol-1"));

        assertThat(quiet.disposition()).isEqualTo(FindingIngestService.Disposition.SUPPRESSED);
        assertThat(quiet.shouldNotify()).isFalse();
        assertThat(quiet.suppressedBy()).isNotNull();
    }

    /**
     * What somebody accepted is no longer what is true.
     *
     * <p>A dismissal covers the problem AS IT WAS. This is why
     * survives_material_change defaults to false — a $40/month problem that
     * became $4,000 is not the thing anyone signed off.
     */
    @Test
    void aDismissedFindingComesBackWhenItsSubstanceChanges() {
        ingest(verdict("medium", "vol-1"));
        dismiss(FindingSuppression.Scope.SUBJECT_CATEGORY,
                Instant.now().plus(Duration.ofDays(30)), false);
        stored().forEach(f -> {
            f.setState(Finding.State.SUPPRESSED);
            findings.save(f);
        });

        FindingIngestService.Result back = ingest(verdict("critical", "vol-1"));

        assertThat(back.disposition()).isEqualTo(FindingIngestService.Disposition.REOPENED);
        assertThat(back.state()).isEqualTo(Finding.State.OPEN);
        assertThat(back.reasonCode()).isEqualTo("material_change_under_suppression");
        assertThat(back.shouldNotify()).isTrue();
    }

    @Test
    void anExplicitlyDurableDismissalSurvivesAMaterialChange() {
        ingest(verdict("medium", "vol-1"));
        dismiss(FindingSuppression.Scope.SUBJECT_CATEGORY,
                Instant.now().plus(Duration.ofDays(30)), true);
        stored().forEach(f -> {
            f.setState(Finding.State.SUPPRESSED);
            findings.save(f);
        });

        assertThat(ingest(verdict("critical", "vol-1")).disposition())
                .isEqualTo(FindingIngestService.Disposition.SUPPRESSED);
    }

    @Test
    void anExpiredDismissalLetsTheFindingBack() {
        ingest(verdict("medium", "vol-1"));
        dismiss(FindingSuppression.Scope.SUBJECT_CATEGORY,
                Instant.now().minus(Duration.ofDays(1)), false);
        stored().forEach(f -> {
            f.setState(Finding.State.SUPPRESSED);
            findings.save(f);
        });

        FindingIngestService.Result back = ingest(verdict("medium", "vol-1"));

        assertThat(back.disposition()).isEqualTo(FindingIngestService.Disposition.REOPENED);
        assertThat(back.reasonCode()).isEqualTo("suppression_expired");
    }

    /**
     * A category dismissed at service scope months ago must not surface a
     * brand-new finding today just because this particular one is new.
     */
    @Test
    void aWideDismissalSuppressesAFindingThatHasNeverBeenSeenBefore() {
        dismiss(FindingSuppression.Scope.CATEGORY_GLOBAL,
                Instant.now().plus(Duration.ofDays(30)), false);

        FindingIngestService.Result first = ingest(verdict("medium", "vol-99"));

        assertThat(first.disposition()).isEqualTo(FindingIngestService.Disposition.SUPPRESSED);
        assertThat(first.state()).isEqualTo(Finding.State.SUPPRESSED);
        assertThat(first.shouldNotify()).isFalse();
    }

    /**
     * Any live cover suppresses; the one RECORDED must be the most specific,
     * or "why did this never appear" gets answered with a tenant-wide rule
     * when a finding-level dismissal was the real reason.
     */
    @Test
    void theNarrowestCoveringDismissalIsTheOneRecorded() {
        FindingSuppression wide = dismiss(FindingSuppression.Scope.CATEGORY_GLOBAL,
                Instant.now().plus(Duration.ofDays(30)), false);
        FindingSuppression narrow = dismiss(FindingSuppression.Scope.SUBJECT_CATEGORY,
                Instant.now().plus(Duration.ofDays(30)), false);

        FindingIngestService.Result result = ingest(verdict("medium", "vol-1"));

        assertThat(result.suppressedBy()).isEqualTo(narrow.getId());
        assertThat(result.suppressedBy()).isNotEqualTo(wide.getId());
    }

    // ------------------------------------------------------------ regression ---

    /**
     * The interesting fact is that a fix did not hold, not what the numbers
     * currently are — so a reopen is always worth telling somebody, however
     * small the change.
     */
    @Test
    void aFindingThatComesBackAfterBeingResolvedIsARegression() {
        ingest(verdict("medium", "vol-1"));
        stored().forEach(f -> {
            f.setState(Finding.State.RESOLVED);
            findings.save(f);
        });

        FindingIngestService.Result back = ingest(verdict("medium", "vol-1"));

        assertThat(back.disposition()).isEqualTo(FindingIngestService.Disposition.REOPENED);
        assertThat(back.reasonCode()).isEqualTo("regression");
        assertThat(back.episodeCount()).isEqualTo(2);
        assertThat(back.shouldNotify()).isTrue();
    }

    /**
     * STALE and RESOLVED are different on purpose: one means somebody acted,
     * the other means it stopped being observed and nobody knows why.
     */
    @Test
    void aStaleFindingReobservedIsDistinguishedFromARegression() {
        ingest(verdict("medium", "vol-1"));
        stored().forEach(f -> {
            f.setState(Finding.State.STALE);
            findings.save(f);
        });

        assertThat(ingest(verdict("medium", "vol-1")).reasonCode())
                .isEqualTo("reobserved_after_stale");
    }

    @Test
    void everyReopenLeavesATransitionSomebodyCanRead() {
        ingest(verdict("medium", "vol-1"));
        stored().forEach(f -> {
            f.setState(Finding.State.RESOLVED);
            findings.save(f);
        });
        ingest(verdict("medium", "vol-1"));

        Long id = stored().get(0).getId();
        assertThat(transitions.findTop100ByFindingIdOrderByAtDesc(id))
                .anyMatch(t -> "regression".equals(t.getReasonCode()));
    }

    // ----------------------------------------------------------- refusals ---

    /**
     * An unstable key deduplicates nothing across runs. Storing it would
     * create a new finding every night that nobody could ever dismiss, so it
     * is refused rather than quietly filling the table.
     */
    @Test
    void aVerdictWhoseKeyIsNotStableIsRefusedRatherThanStored() throws Exception {
        JsonNode unstable = mapper.readTree("""
                {
                  "verdict_id": "v-unstable",
                  "idempotency_key": "prose-hash",
                  "stable_key": false,
                  "agent": { "name": "a", "version": "1" },
                  "subject": { "kind": "unknown", "id": "" },
                  "finding": { "type": "unclassified", "severity": "info" }
                }
                """);

        FindingIngestService.Result result = ingest(unstable);

        assertThat(result.disposition()).isEqualTo(FindingIngestService.Disposition.REJECTED);
        assertThat(result.error()).contains("not stable");
        assertThat(findings.count()).isZero();
    }

    @Test
    void aVerdictWithNoIdIsRefusedBecauseThereIsNoReplayProtectionWithoutIt() throws Exception {
        JsonNode anonymous = mapper.readTree("""
                { "idempotency_key": "k", "finding": { "type": "t" } }
                """);

        assertThat(ingest(anonymous).disposition())
                .isEqualTo(FindingIngestService.Disposition.REJECTED);
    }

    // ------------------------------------------------------ key forks ---

    /**
     * When somebody changes what goes into the idempotency key without bumping
     * its version, every existing finding silently forks into a duplicate that
     * looks like a new problem — and nothing else in the system can tell.
     */
    @Test
    void twoLiveFindingsOnOneSubjectAndCategoryAreDetectableAsAKeyFork() {
        ingest(verdict("medium", "vol-1"));
        // The same subject and category arriving under a different key: what an
        // undeclared change to the key composition looks like from here.
        JsonNode forked = verdict("medium", "vol-1");
        ((com.fasterxml.jackson.databind.node.ObjectNode) forked)
                .put("idempotency_key", "key-vol-1-RECOMPOSED");
        ingest(forked);

        Finding any = stored().get(0);
        assertThat(service.forkedDuplicates(TENANT, any))
                .as("two OPEN findings for one agent on one subject+category")
                .hasSize(2);
    }

    // ------------------------------------------------------- attribution ---

    /**
     * Phase A tolerates a verdict with no run and counts it.
     *
     * <p>Rejecting today would break every agent at once, which is why this is
     * expand/contract; the count is what makes phase C schedulable rather than
     * guessed at.
     */
    @Test
    void aVerdictWithNoRunIsStillAcceptedAndCounted() {
        FindingIngestService.Result result =
                service.ingest(TENANT, PROJECT, null, verdict("medium", "vol-1"));

        assertThat(result.disposition()).isEqualTo(FindingIngestService.Disposition.CREATED);
        assertThat(attributionOf(AGENT_NAME).unattributed()).isEqualTo(1);
        assertThat(attributionOf(AGENT_NAME).fullyAttributed()).isFalse();
    }

    @Test
    void anAttributedVerdictIsCountedAsSuch() {
        ingest(verdict("medium", "vol-1"));

        VerdictAttributionService.Attributed counts = attributionOf(AGENT_NAME);
        assertThat(counts.attributed()).isEqualTo(1);
        assertThat(counts.unattributed()).isZero();
        assertThat(counts.fullyAttributed()).isTrue();
    }

    /**
     * <b>The failure this closes.</b> Without ownership validation, agent A can
     * emit under agent B's run and be resolved by absence against a coverage
     * claim that never covered it — the same silent resolution two migrations
     * went into preventing, arriving through the front door.
     */
    @Test
    void aVerdictNamingAnotherAgentsRunIsRefused() {
        Agent other = new Agent();
        other.setTenantId(TENANT);
        other.setProjectId(PROJECT);
        other.setName("aws.public_exposure_auditor");
        other = agents.save(other);

        AgentRun otherRun = new AgentRun();
        otherRun.setTenantId(TENANT);
        otherRun.setProjectId(PROJECT);
        otherRun.setAgentId(other.getId());
        otherRun.setInput("{}");
        otherRun.setStatus(AgentRun.Status.RUNNING);
        Long otherRunId = agentRuns.save(otherRun).getId();

        FindingIngestService.Result result =
                service.ingest(TENANT, PROJECT, otherRunId, verdict("medium", "vol-1"));

        assertThat(result.disposition()).isEqualTo(FindingIngestService.Disposition.REJECTED);
        assertThat(result.reasonCode()).isEqualTo("foreign_run");
        assertThat(findings.count()).isZero();
        assertThat(attributionOf(AGENT_NAME).foreignRun()).isEqualTo(1);
    }

    @Test
    void aVerdictNamingARunThatDoesNotExistIsRefused() {
        FindingIngestService.Result result =
                service.ingest(TENANT, PROJECT, 424242L, verdict("medium", "vol-1"));

        assertThat(result.reasonCode()).isEqualTo("foreign_run");
        assertThat(findings.count()).isZero();
    }

    /** A run id is not a capability: it does not cross a tenant boundary. */
    @Test
    void aVerdictNamingAnotherTenantsRunIsRefused() {
        FindingIngestService.Result result =
                service.ingest("someone-else", PROJECT, openRunId, verdict("medium", "vol-1"));

        assertThat(result.reasonCode()).isEqualTo("foreign_run");
    }

    /**
     * A straggler after completion claims coverage retroactively for a subject
     * the completion validation never counted.
     *
     * <p>The error code is distinct from the run-less case on purpose: "this
     * agent has not been updated" and "this agent raced its own completion" are
     * different bugs with different fixes, and one code for both means the first
     * gets diagnosed as the second.
     */
    @Test
    void aVerdictArrivingAfterItsRunFinishedIsRefusedWithItsOwnCode() {
        AgentRun run = agentRuns.findById(openRunId).orElseThrow();
        run.setFinishedAt(Instant.now());
        agentRuns.save(run);

        FindingIngestService.Result result = ingest(verdict("medium", "vol-1"));

        assertThat(result.disposition()).isEqualTo(FindingIngestService.Disposition.REJECTED);
        assertThat(result.reasonCode())
                .isEqualTo("run_already_completed")
                .isNotEqualTo("foreign_run");
        assertThat(attributionOf(AGENT_NAME).late()).isEqualTo(1);
    }

    /** A settled coverage claim closes the run even before it is marked finished. */
    @Test
    void aVerdictArrivingAfterTheCoverageClaimIsSettledIsAlsoLate() {
        AgentRun run = agentRuns.findById(openRunId).orElseThrow();
        run.setScopeStatus(AgentRun.ScopeStatus.COMPLETE);
        agentRuns.save(run);

        assertThat(ingest(verdict("medium", "vol-1")).reasonCode())
                .isEqualTo("run_already_completed");
    }

    /** The counter accumulates rather than overwriting — it is the gauge. */
    @Test
    void countsAccumulateAcrossVerdicts() {
        ingest(verdict("medium", "vol-1"));
        ingest(verdict("medium", "vol-2"));
        service.ingest(TENANT, PROJECT, null, verdict("medium", "vol-3"));

        VerdictAttributionService.Attributed counts = attributionOf(AGENT_NAME);
        assertThat(counts.attributed()).isEqualTo(2);
        assertThat(counts.unattributed()).isEqualTo(1);
    }

    /**
     * The half-wired case: a run id, but no declared scope.
     *
     * <p>This is the failure that looks like progress. The attribution gauge
     * moves — the agent clearly learned something — while the coverage gauge
     * stays silent, and without a counter that separates them nothing connects
     * the two. It is also the achievable form of 'a run without a scope is
     * impossible': declaration cannot happen at run start, because a scope
     * enumerates subjects the agent has not fetched yet, so the check lands at
     * the first moment the run makes a claim that could be reaped.
     */
    @Test
    void aVerdictFromARunThatNeverDeclaredAScopeIsCountedApart() {
        AgentRun run = agentRuns.findById(openRunId).orElseThrow();
        run.setSubjectScope(null);
        run.setScopeStatus(null);
        agentRuns.save(run);

        FindingIngestService.Result result = ingest(verdict("medium", "vol-1"));

        // Accepted in phase A: rejecting today would break every agent at once.
        assertThat(result.disposition()).isEqualTo(FindingIngestService.Disposition.CREATED);

        VerdictAttributionService.Attributed counts = attributionOf(AGENT_NAME);
        assertThat(counts.unscoped()).isEqualTo(1);
        assertThat(counts.unattributed())
                .as("a run id WAS passed, so this is not the not-yet-updated case")
                .isZero();
        assertThat(counts.halfWired()).isTrue();
        assertThat(counts.fullyAttributed()).isFalse();
    }

    /**
     * <b>The invariant that survives a wrong extraction.</b>
     *
     * <p>This is the compaction failure made concrete. {@code reduce._absorb}
     * elides the middle of a long tool result, so an inventory of many volumes
     * can reach the scope builder as a handful. The resulting scope is entirely
     * self-consistent — the count matches the digest, the digest matches the
     * materialised rows, the coverage verdict matches the count — and it
     * describes a smaller set than the run actually examined. Every other check
     * in this system passes.
     *
     * <p>What does not pass is the run having an opinion about a volume its own
     * scope says it never looked at. That contradiction is detectable without
     * knowing what the correct scope was, which matters because if we knew the
     * correct scope we would not need the extraction.
     */
    @Test
    void aVerdictAboutASubjectOutsideTheRunsOwnScopeIsRefused() {
        declareEnumerated("cloud_resource", "vol-1", "vol-2");

        FindingIngestService.Result inside = ingest(verdict("medium", "vol-1"));
        assertThat(inside.disposition()).isEqualTo(FindingIngestService.Disposition.CREATED);

        FindingIngestService.Result outside = ingest(verdict("medium", "vol-900"));
        assertThat(outside.disposition()).isEqualTo(FindingIngestService.Disposition.REJECTED);
        assertThat(outside.reasonCode()).isEqualTo("subject_out_of_scope");
        assertThat(attributionOf(AGENT_NAME).outOfScope()).isEqualTo(1);
        assertThat(attributionOf(AGENT_NAME).fullyAttributed()).isFalse();
    }

    /** A kind the run never claimed at all is the same contradiction, wider. */
    @Test
    void aVerdictAboutAKindTheRunNeverClaimedIsRefused() {
        declareEnumerated("principal", "ali@intertecsys.com");

        assertThat(ingest(verdict("medium", "vol-1")).reasonCode())
                .isEqualTo("subject_out_of_scope");
    }

    /**
     * An {@code all} scope admits anything of its kind by definition, so there
     * is nothing here to contradict — which is exactly why
     * {@code overclaimSuspects} exists for the authored shapes.
     */
    @Test
    void anAllScopeAdmitsAnySubjectOfItsKind() {
        AgentRun run = agentRuns.findById(openRunId).orElseThrow();
        run.setSubjectScope("[{\"kind\":\"all\",\"subject_kind\":\"cloud_resource\"}]");
        agentRuns.save(run);

        assertThat(ingest(verdict("medium", "vol-900")).disposition())
                .isEqualTo(FindingIngestService.Disposition.CREATED);
    }

    /** Materialises a run scope the way RunScopeService would have. */
    private void declareEnumerated(String kind, String... ids) {
        AgentRun run = agentRuns.findById(openRunId).orElseThrow();
        run.setSubjectScope("[{\"kind\":\"enumerated\",\"subject_kind\":\"" + kind
                + "\",\"subject_id_count\":" + ids.length
                + ",\"subject_ids_digest\":\""
                + com.intertec.autoops.agent.scope.SubjectDigest.digest(java.util.List.of(ids))
                + "\"}]");
        agentRuns.save(run);
        for (String id : ids) {
            jdbc.update("INSERT INTO agent_run_subject (run_id, subject_kind, subject_id_hash) "
                    + "VALUES (?, ?, ?)", openRunId, kind,
                    com.intertec.autoops.agent.scope.SubjectDigest.hash(id));
        }
    }

    /**
     * <b>The mismatch that refused every live verdict.</b>
     *
     * <p>The runtime stamps a verdict with the agent's GRAPH REF
     * (aws.idle_resource_reclaimer); agents.name is the display name a
     * customer sees. Attribution compared those two, so nothing ever matched
     * and every verdict from every Python agent was classified FOREIGN_RUN —
     * a code meaning 'an agent is emitting under another agent's coverage
     * claim', which reads like a tenancy bug and is not one.
     *
     * <p>This test only has teeth because the fixture's two names DIFFER. An
     * earlier version set both to the same string, which is why a green suite
     * coexisted with production refusing everything.
     */
    @Test
    void aVerdictNamingTheGraphRefIsAttributedEvenThoughTheDisplayNameDiffers() {
        Agent stored = agents.findAll().getFirst();
        assertThat(stored.getName())
                .as("the premise: display name and graph ref are different")
                .isNotEqualTo(stored.getGraphRef());

        FindingIngestService.Result result = ingest(verdict("medium", "vol-1"));

        assertThat(result.disposition()).isEqualTo(FindingIngestService.Disposition.CREATED);
        assertThat(attributionOf(AGENT_NAME).foreignRun()).isZero();
        assertThat(attributionOf(AGENT_NAME).attributed()).isEqualTo(1);
    }

    /**
     * <b>The column nothing was writing.</b>
     *
     * <p>V7 created {@code findings.subject_id_hash}; the entity never carried
     * it, so every finding was stored with NULL. Nothing failed, because
     * nothing reads it yet — but the reaper joins on it and a NULL never
     * matches, so no finding would ever have been reaped, and
     * {@code overclaimSuspects}' COUNT(DISTINCT ...) skips NULLs, so the gauge
     * covering the blast-radius gap would have read a confident zero.
     */
    @Test
    void aStoredFindingCarriesTheHashTheReaperJoinsOn() {
        ingest(verdict("medium", "vol-1"));

        Finding stored = stored().getFirst();
        assertThat(stored.getSubjectIdHash())
                .as("a NULL here is a finding the reaper can never match")
                .isEqualTo(com.intertec.autoops.agent.scope.SubjectDigest.hash("vol-1"));
    }

    /**
     * An empty subject stays NULL rather than hashing the empty string.
     *
     * <p>Unreachable through the normal path — a finding that names no subject
     * gets {@code stable_key: false} from the runtime and is refused before it
     * is stored. This guards a MALFORMED producer claiming a stable key with no
     * subject, where hashing "" would give every such finding the same
     * well-known digest and join them all to each other.
     */
    @Test
    void aFindingWithNoSubjectHasNoHashRatherThanASharedOne() {
        JsonNode malformed = verdict("medium", "vol-9");
        ((com.fasterxml.jackson.databind.node.ObjectNode) malformed.path("subject"))
                .put("id", "");

        FindingIngestService.Result result = service.ingest(
                TENANT, PROJECT, openRunId, malformed);

        assertThat(result.disposition()).isEqualTo(FindingIngestService.Disposition.CREATED);
        assertThat(stored().getFirst().getSubjectIdHash()).isNull();
    }

    private VerdictAttributionService.Attributed attributionOf(String agentName) {
        return attributions.byAgent(TENANT, java.time.LocalDate.now(java.time.ZoneOffset.UTC))
                .stream()
                .filter(row -> row.agentName().equals(agentName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no attribution row for " + agentName));
    }
}
