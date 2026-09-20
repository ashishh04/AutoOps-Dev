package com.intertec.autoops.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intertec.autoops.agent.domain.Agent;
import com.intertec.autoops.agent.domain.AgentRun;
import com.intertec.autoops.agent.exception.AgentException;
import com.intertec.autoops.agent.repo.AgentRepository;
import com.intertec.autoops.agent.repo.AgentRunRepository;
import com.intertec.autoops.agent.scope.RunScope;
import com.intertec.autoops.agent.scope.SubjectDigest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What actually gets written when a run says what it covered.
 *
 * <p><b>This class did not exist until the sweeper needed it, which was the
 * gap.</b> {@code RunScopeTest} covers the value type — the shapes, the
 * narrowing rules, the coherence arithmetic — and that is the part where the
 * thinking is. None of it says whether the service <i>persists</i> the result,
 * and the service is where a coverage claim becomes something the reaper will
 * act on. A correct rule that is never written down reaps nothing; a correct
 * rule written down wrongly reaps the wrong things.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Import({RunScopeService.class, RunScopeServiceTest.Beans.class})
class RunScopeServiceTest {

    private static final String TENANT = "acme";
    private static final Long PROJECT = 9L;

    @TestConfiguration
    static class Beans {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @Autowired
    private RunScopeService scopes;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private AgentRunRepository runs;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TestEntityManager entities;

    private final ObjectMapper mapper = new ObjectMapper();
    private Long runId;
    private Long agentId;

    @BeforeEach
    void setUp() {
        // Flyway-only table: @DataJpaTest builds its schema from the entities,
        // and agent_run_subject deliberately has none — it is a join target the
        // reaper reads, never an object anything loads.
        jdbc.execute("CREATE TABLE IF NOT EXISTS agent_run_subject ("
                + "run_id BIGINT NOT NULL, subject_kind VARCHAR(64) NOT NULL, "
                + "subject_id_hash VARBINARY(32) NOT NULL, "
                + "PRIMARY KEY (run_id, subject_kind, subject_id_hash))");
        jdbc.update("DELETE FROM agent_run_subject");

        Agent agent = new Agent();
        agent.setTenantId(TENANT);
        agent.setProjectId(PROJECT);
        agent.setName("aws.idle_resource_reclaimer");
        agentId = agents.save(agent).getId();

        runId = newRun(AgentRun.Status.RUNNING).getId();
    }

    private AgentRun newRun(AgentRun.Status status) {
        AgentRun run = new AgentRun();
        run.setTenantId(TENANT);
        run.setProjectId(PROJECT);
        run.setAgentId(agentId);
        run.setInput("{}");
        run.setStatus(status);
        run.setStartedAt(Instant.now());
        return runs.save(run);
    }

    private JsonNode json(String value) {
        try {
            return mapper.readTree(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * Pushes JPA's pending writes to the database and forgets what it cached.
     *
     * <p>Needed because this service deliberately mixes both: entities for the
     * run row, raw SQL for the sweep and the gauges. Inside {@code @DataJpaTest}
     * everything shares one un-committed transaction, so an unflushed
     * {@code scope_status} is invisible to a {@code JdbcTemplate} statement, and
     * a row that SQL just updated is still cached in the persistence context.
     *
     * <p><b>This is a property of the test, not of the service.</b> In
     * production each call arrives on its own committed transaction and the two
     * views cannot diverge. The temptation when these tests fail is to make the
     * service flush — which would be adding machinery to production to satisfy a
     * test harness.
     */
    private void sync() {
        entities.flush();
        entities.clear();
    }

    private int sweep() {
        sync();
        int swept = scopes.sweepAbandoned(Instant.now());
        entities.clear();
        return swept;
    }

    /**
     * Reads the run back FROM THE DATABASE, not from the session.
     *
     * <p>Without the clear this returns the very instance the service just
     * mutated, so every assertion below would be checking the persistence
     * context rather than anything that was written — a persistence test that
     * never re-reads is testing the object graph. That is how the H2 JSON
     * column defect hid: nothing here re-read the column, so nothing noticed
     * that H2 stores it unreadably.
     */
    private AgentRun reload() {
        sync();
        return runs.findById(runId).orElseThrow();
    }

    // ------------------------------------------------------------ declare ---

    @Test
    void declaringWritesTheScopeAndMarksTheRunRunning() {
        scopes.declare(runId, json("[{\"kind\":\"all\",\"subject_kind\":\"principal\"}]"), Map.of());

        AgentRun run = reload();
        assertThat(run.getScopeStatus()).isEqualTo(AgentRun.ScopeStatus.RUNNING);
        assertThat(run.getSubjectScope()).contains("principal");
    }

    /** At start a scope is an intent; nothing has happened to it yet. */
    @Test
    void aDeclarationCannotCarryACoverageVerdict() {
        assertThatThrownBy(() -> scopes.declare(runId,
                json("[{\"kind\":\"all\",\"subject_kind\":\"principal\",\"coverage\":\"COMPLETE\"}]"),
                Map.of()))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("nothing has happened to it yet");
    }

    @Test
    void anEnumeratedScopeMaterialisesOneRowPerSubject() {
        List<String> volumes = List.of("vol-1", "vol-2", "vol-3");
        scopes.declare(runId, enumerated("cloud_resource", volumes),
                Map.of("cloud_resource", volumes));

        assertThat(subjectRows()).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_run_subject WHERE run_id = ? AND subject_id_hash = ?",
                Integer.class, runId, SubjectDigest.hash("vol-2")))
                .isEqualTo(1);
    }

    /**
     * The check standing between a mismatched pair and a silent one.
     *
     * <p>If the digest and the rows describe different sets, the reaper trusts
     * whichever it reads — and resolves findings for subjects nobody visited.
     */
    @Test
    void subjectsThatDoNotHashToTheDeclaredDigestAreRefused() {
        JsonNode scope = enumerated("cloud_resource", List.of("vol-1", "vol-2"));

        assertThatThrownBy(() -> scopes.declare(runId, scope,
                Map.of("cloud_resource", List.of("vol-1", "vol-9"))))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("do not hash to the digest");
    }

    @Test
    void anEnumeratedScopeWhoseSubjectsWereNotSentIsRefused() {
        assertThatThrownBy(() -> scopes.declare(runId,
                enumerated("cloud_resource", List.of("vol-1")), Map.of()))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("did not send them");
    }

    // ----------------------------------------------------------- complete ---

    @Test
    void completingRecordsTheNarrowedScopeAndTheCounts() {
        scopes.declare(runId, json("[{\"kind\":\"all\",\"subject_kind\":\"cloud_resource\"}]"),
                Map.of());
        scopes.complete(runId,
                json("[{\"kind\":\"all\",\"subject_kind\":\"cloud_resource\","
                        + "\"coverage\":\"COMPLETE\"}]"),
                Map.of(), Map.of("cloud_resource", 412), 7);

        AgentRun run = reload();
        assertThat(run.getScopeStatus()).isEqualTo(AgentRun.ScopeStatus.COMPLETE);
        assertThat(run.getSubjectsEvaluated()).isEqualTo(412);
        assertThat(run.getVerdictsEmitted()).isEqualTo(7);
        assertThat(run.hasUsableCoverage()).isTrue();
    }

    /**
     * The single-kind narrowing the correlators will need the multi-kind version
     * of: the inventory returned 800 of 1200, and that is a CORRECT outcome.
     *
     * <p>Worth asserting explicitly rather than inferring, because the shape has
     * to be visible before anything harder copies it — a partial page narrowing
     * to an exact enumerated list is the normal case, not a failure.
     */
    @Test
    void anInventoryThatPagedShortCompletesOverWhatItActuallySaw() {
        scopes.declare(runId, json("[{\"kind\":\"all\",\"subject_kind\":\"cloud_resource\"}]"),
                Map.of());

        List<String> seen = List.of("vol-1", "vol-2", "vol-3");
        JsonNode reached = covered(enumerated("cloud_resource", seen), "COMPLETE");
        scopes.complete(runId, reached, Map.of("cloud_resource", seen),
                Map.of("cloud_resource", seen.size()), 1);

        AgentRun run = reload();
        assertThat(run.getScopeStatus()).isEqualTo(AgentRun.ScopeStatus.COMPLETE);
        // The materialised rows, not the JSON text: H2 stores a JSON column by
        // wrapping the string as a JSON string literal, so reading the column
        // back here returns an escaped blob rather than the document. MySQL
        // parses it properly — ScopeFixtureIT asserts the text on a real one.
        // This assertion used to read the JSON and passed only because nothing
        // re-read the row; it was checking the persistence context.
        assertThat(subjectRows())
                .as("the three it actually saw are the three it claims")
                .isEqualTo(3);
    }

    /** The intent's subjects are replaced by what was reached, never merged. */
    @Test
    void completingReplacesTheDeclaredSubjectsRatherThanAddingToThem() {
        List<String> intended = List.of("vol-1", "vol-2", "vol-3");
        scopes.declare(runId, enumerated("cloud_resource", intended),
                Map.of("cloud_resource", intended));

        List<String> reached = List.of("vol-1", "vol-2");
        scopes.complete(runId, covered(enumerated("cloud_resource", reached), "PARTIAL"),
                Map.of("cloud_resource", reached), Map.of("cloud_resource", 2), 0);

        assertThat(subjectRows()).isEqualTo(2);
    }

    /**
     * <b>The test protecting the design decision most likely to be undone.</b>
     *
     * <p>{@code complete()} drives its own transactions instead of carrying
     * {@code @Transactional}, because it has to PERSIST a rejection and then
     * throw it. An annotated method would roll back the record saying why, the
     * run would sit in RUNNING, and the sweeper would relabel it FAILED a day
     * later with the reason gone. Somebody will eventually see a manual
     * {@code TransactionTemplate} next to an annotated codebase and tidy it up;
     * this fails when they do.
     */
    @Test
    void aRejectedClaimIsStillRecordedAsFailed() {
        scopes.declare(runId,
                json("[{\"kind\":\"dimensional\",\"subject_kind\":\"cloud_resource\","
                        + "\"dimensions\":{\"environment\":[\"staging\"]}}]"), Map.of());

        assertThatThrownBy(() -> scopes.complete(runId,
                json("[{\"kind\":\"all\",\"coverage\":\"COMPLETE\"}]"),
                Map.of(), Map.of(RunScope.EVERY_KIND, 900), 3))
                .isInstanceOf(AgentException.class);

        assertThat(reload().getScopeStatus())
                .as("the refusal has to outlive the throw that reported it")
                .isEqualTo(AgentRun.ScopeStatus.FAILED);
        assertThat(reload().hasUsableCoverage()).isFalse();
    }

    @Test
    void aMixedRunRollsUpToPartialAndStaysUsable() {
        scopes.declare(runId,
                json("[{\"kind\":\"all\",\"subject_kind\":\"cloud_resource\"},"
                        + "{\"kind\":\"all\",\"subject_kind\":\"principal\"}]"), Map.of());

        scopes.complete(runId,
                json("[{\"kind\":\"all\",\"subject_kind\":\"cloud_resource\","
                        + "\"coverage\":\"COMPLETE\"},"
                        + "{\"kind\":\"all\",\"subject_kind\":\"principal\","
                        + "\"coverage\":\"SKIPPED\"}]"),
                Map.of(), Map.of("cloud_resource", 412, "principal", 0), 2);

        AgentRun run = reload();
        assertThat(run.getScopeStatus()).isEqualTo(AgentRun.ScopeStatus.PARTIAL);
        assertThat(run.hasUsableCoverage())
                .as("the buckets it did enumerate are still real coverage")
                .isTrue();
    }

    @Test
    void completingARunThatNeverDeclaredIsRefused() {
        assertThatThrownBy(() -> scopes.complete(runId,
                json("[{\"kind\":\"all\",\"coverage\":\"COMPLETE\"}]"),
                Map.of(), Map.of(RunScope.EVERY_KIND, 1), 0))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("never declared");
    }

    // -------------------------------------------------------------- sweep ---

    /**
     * A run whose agent died mid-sweep is relabelled, eventually.
     *
     * <p>Not because it changes any reaping decision — RUNNING and FAILED both
     * ground nothing — but because a zombie run is indistinguishable from work
     * in flight, so an agent that has quietly stopped reads as healthy activity
     * on the coverage gauge.
     */
    @Test
    void anAbandonedRunIsSweptToFailed() {
        scopes.declare(runId, json("[{\"kind\":\"all\"}]"), Map.of());
        ageRun(runId, Duration.ofHours(30));

        assertThat(sweep()).isEqualTo(1);
        assertThat(reload().getScopeStatus()).isEqualTo(AgentRun.ScopeStatus.FAILED);
    }

    /** A run parked on a human approval is not abandoned. */
    @Test
    void aRunYoungerThanTheThresholdIsLeftAlone() {
        scopes.declare(runId, json("[{\"kind\":\"all\"}]"), Map.of());
        ageRun(runId, Duration.ofHours(23));

        assertThat(sweep()).isZero();
        assertThat(reload().getScopeStatus()).isEqualTo(AgentRun.ScopeStatus.RUNNING);
    }

    /**
     * <b>The race the guard exists for.</b>
     *
     * <p>A run that completes between the sweeper deciding to act and the write
     * landing must not be clobbered — its COMPLETE claim is real coverage, and
     * overwriting it with FAILED would silently discard a night's work. The
     * protection is that the sweep is a single guarded {@code UPDATE ... WHERE
     * scope_status = 'RUNNING'} with no read-then-write window at all, so a run
     * that has moved on simply does not match. Asserted rather than reasoned
     * about, because the obvious refactor — select the stale ids, then update
     * them — reintroduces exactly this window and would still pass every other
     * test here.
     */
    @Test
    void aRunThatCompletedIsNeverSweptEvenIfItIsOld() {
        scopes.declare(runId, json("[{\"kind\":\"all\",\"subject_kind\":\"cloud_resource\"}]"),
                Map.of());
        scopes.complete(runId,
                json("[{\"kind\":\"all\",\"subject_kind\":\"cloud_resource\","
                        + "\"coverage\":\"COMPLETE\"}]"),
                Map.of(), Map.of("cloud_resource", 5), 1);
        ageRun(runId, Duration.ofDays(9));

        assertThat(sweep()).isZero();
        assertThat(reload().getScopeStatus()).isEqualTo(AgentRun.ScopeStatus.COMPLETE);
    }

    /** A run that never declared anything is not the sweeper's business. */
    @Test
    void aRunWithNoScopeStatusIsNotSwept() {
        ageRun(runId, Duration.ofDays(3));

        assertThat(sweep()).isZero();
        assertThat(reload().getScopeStatus()).isNull();
    }

    // -------------------------------------------------------------- gauge ---

    @Test
    void theCoverageGaugeSeparatesSilentRunsFromTrustworthyOnes() {
        scopes.declare(runId, json("[{\"kind\":\"all\",\"subject_kind\":\"cloud_resource\"}]"),
                Map.of());
        scopes.complete(runId,
                json("[{\"kind\":\"all\",\"subject_kind\":\"cloud_resource\","
                        + "\"coverage\":\"COMPLETE\"}]"),
                Map.of(), Map.of("cloud_resource", 5), 1);
        newRun(AgentRun.Status.SUCCEEDED);   // declared nothing at all

        sync();
        List<RunScopeService.CoverageGap> gaps =
                scopes.coverageGaps(TENANT, Instant.now().minus(Duration.ofDays(1)));

        assertThat(gaps).singleElement().satisfies(gap -> {
            assertThat(gap.total()).isEqualTo(2);
            assertThat(gap.trustworthy()).isEqualTo(1);
            assertThat(gap.silent()).isEqualTo(1);
            assertThat(gap.trustworthyFraction()).isEqualTo(0.5);
        });
    }

    // ------------------------------------------------------------ helpers ---

    private JsonNode enumerated(String kind, List<String> ids) {
        return json("[{\"kind\":\"enumerated\",\"subject_kind\":\"" + kind + "\","
                + "\"subject_id_count\":" + ids.size() + ","
                + "\"subject_ids_digest\":\"" + SubjectDigest.digest(ids) + "\"}]");
    }

    private JsonNode covered(JsonNode scope, String coverage) {
        ((com.fasterxml.jackson.databind.node.ObjectNode) scope.get(0))
                .put("coverage", coverage);
        return scope;
    }

    private int subjectRows() {
        sync();
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_run_subject WHERE run_id = ?", Integer.class, runId);
    }

    /** Backdates a run so the sweep's threshold can be exercised without waiting. */
    private void ageRun(Long id, Duration age) {
        sync();
        jdbc.update("UPDATE agent_runs SET started_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minus(age)), id);
        entities.clear();
    }

    @Test
    void declaringOnARunThatDoesNotExistIsRefused() {
        assertThatCode(() -> scopes.declare(runId, json("[{\"kind\":\"all\"}]"), Map.of()))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> scopes.declare(9_999_999L, json("[{\"kind\":\"all\"}]"), Map.of()))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("No run");
    }
}
