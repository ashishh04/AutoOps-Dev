package com.intertec.autoops.agent;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the migrations actually build, on the database they actually run on.
 *
 * <p><b>Why this exists.</b> {@code @DataJpaTest} builds its schema from
 * Hibernate's DDL, not from Flyway. Every fast test in this module therefore
 * validates a schema that no environment ever runs, and a migration can be
 * wrong in ways all of them pass. V6 proved it: 18 green tests, and the
 * migration then failed on the real database with error 1071 because two
 * composite indexes exceeded MySQL's key-length limit.
 *
 * <p>That was not a MySQL quirk, it was a permanent blind spot — every future
 * migration has the same one. So this runs Flyway to head against a real MySQL
 * of the deployed version.
 *
 * <p><b>The migration running at all is the primary assertion.</b> MySQL
 * refuses an oversized index at CREATE time, so the V6 failure was not
 * something a schema query could have detected after the fact — the schema
 * never existed. {@link #migrate()} throwing is what catches that class of bug,
 * and it catches every other DDL error for free.
 *
 * <p>The tests below cover what MySQL does NOT enforce: a table that silently
 * lands on latin1, a named constraint lost in a refactor, a column the reaper
 * will need that quietly disappeared — plus a headroom check, because an index
 * at 90% of the key budget is legal today and breaks on the next innocent
 * widening. A new migration inherits all of it without anyone remembering.
 *
 * <p>An {@code IT} rather than a {@code Test}: it pulls an image and takes tens
 * of seconds, which is the wrong price on every save and the right one on every
 * push. CI already runs {@code mvn verify} for this module.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SchemaInvariantsIT {

    /**
     * Pinned to the deployed major.minor. A migration that passes on 8.0 and
     * fails on 8.4 is exactly the class of surprise this exists to prevent, so
     * the test must not drift from production by using a floating tag.
     */
    private static MySqlTestDatabase database;
    private static Connection connection;

    @BeforeAll
    void migrate() throws Exception {
        database = MySqlTestDatabase.start();
        database.migrate();
        connection = database.connection();
    }

    @AfterAll
    void release() throws Exception {
        database.close();
    }

    /**
     * The ceiling MySQL enforces, in bytes.
     *
     * <p>Exceeding it is refused at CREATE time, so no schema can ever be in
     * that state — which is exactly why a post-hoc query could not have caught
     * V6 and the migration itself had to.
     */
    private static final long KEY_LIMIT_BYTES = 3072;

    /**
     * The point at which an index is close enough to be a problem.
     *
     * <p>Roughly 78% of the budget. The widest index in the schema today is
     * 1796 bytes, so nothing is near this — it exists to fire when somebody
     * widens a column and pushes an existing index towards a limit they will
     * otherwise meet as a failed migration on the deployed database.
     */
    private static final long HEADROOM_WARN_BYTES = 2400;

    /**
     * No index is close to the key-length limit.
     *
     * <p>Not a check that the limit is respected — MySQL guarantees that by
     * refusing to create the index, and a schema violating it cannot exist to
     * be queried. This is the EARLY warning: an index at 2900 bytes is legal
     * and one {@code VARCHAR} widening away from error 1071 on a production
     * migration.
     *
     * <p>{@code sub_part} is the important column: a prefix index
     * (<code>subject_id(191)</code>) is bounded by its prefix, so only columns
     * indexed WHOLE cost their full width. utf8mb4 charges four bytes per
     * character, which is what turns an innocent {@code VARCHAR(512)} into
     * 2048 bytes of key.
     */
    @Test
    void noIndexIsCloseToTheKeyLengthLimit() throws Exception {
        String sql = """
                SELECT s.table_name, s.index_name,
                       SUM(CASE
                             WHEN s.sub_part IS NOT NULL THEN s.sub_part * 4
                             WHEN c.data_type IN ('varchar','char') THEN c.character_maximum_length * 4
                             WHEN c.data_type IN ('text','mediumtext','longtext','json') THEN 3072
                             ELSE 8
                           END) AS bytes
                FROM information_schema.statistics s
                JOIN information_schema.columns c
                  ON c.table_schema = s.table_schema
                 AND c.table_name   = s.table_name
                 AND c.column_name  = s.column_name
                WHERE s.table_schema = DATABASE()
                GROUP BY s.table_name, s.index_name
                HAVING bytes > %d
                """.formatted(HEADROOM_WARN_BYTES);

        List<String> oversized = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                oversized.add("%s.%s = %d bytes"
                        .formatted(rows.getString(1), rows.getString(2), rows.getLong(3)));
            }
        }

        assertThat(oversized)
                .as("These indexes are within %d bytes of MySQL's %d-byte key limit. The next "
                        + "column widening makes them error 1071 on a production migration. "
                        + "Use a prefix index — subject_id(191) — or a BINARY(32) hash column "
                        + "with the full value stored unindexed.",
                        KEY_LIMIT_BYTES - HEADROOM_WARN_BYTES, KEY_LIMIT_BYTES)
                .isEmpty();
    }

    /**
     * Every table and column is utf8mb4.
     *
     * <p>A table that lands on latin1 stores a customer's resource names
     * wrongly and only fails when one of them contains a character nobody
     * tested with — which is both late and hard to attribute.
     */
    @Test
    void everythingIsUtf8mb4() throws Exception {
        List<String> wrong = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT table_name, column_name, character_set_name
                     FROM information_schema.columns
                     WHERE table_schema = DATABASE()
                       AND character_set_name IS NOT NULL
                       AND character_set_name <> 'utf8mb4'
                     """)) {
            while (rows.next()) {
                wrong.add("%s.%s is %s".formatted(rows.getString(1), rows.getString(2),
                        rows.getString(3)));
            }
        }
        assertThat(wrong).isEmpty();
    }

    /** Every migration applied, and none of them half-applied. */
    @Test
    void everyMigrationSucceeded() throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0")) {
            rows.next();
            assertThat(rows.getInt(1)).isZero();
        }
    }

    /**
     * The identity constraint the whole findings store rests on.
     *
     * <p>Without it two agents racing on the same finding both insert, and the
     * duplicate is indistinguishable from a real second problem. Asserted by
     * NAME because a rename during a refactor is exactly how it would be lost
     * silently.
     */
    @Test
    void theFindingIdentityConstraintExistsAndCoversTheKeyVersion() throws Exception {
        assertThat(indexColumns("findings", "uq_finding_identity"))
                .containsExactly("tenant_id", "idempotency_key", "idempotency_key_version");
    }

    /**
     * Replay protection on observations.
     *
     * <p>Losing this makes a network retry indistinguishable from a fresh
     * sighting, and every occurrence counter in the estate starts drifting
     * upwards with no way to tell.
     */
    @Test
    void verdictReplayProtectionExists() throws Exception {
        assertThat(indexColumns("finding_observations", "uq_observation_verdict"))
                .containsExactly("tenant_id", "verdict_id");
    }

    /**
     * The columns the staleness reaper will depend on.
     *
     * <p>Asserted before the reaper exists on purpose: reaping without a run
     * scope lets one agent outage mark a whole backlog resolved, so the column
     * must not quietly disappear between now and then.
     */
    @Test
    void agentRunsCarryTheScopeTheReaperWillNeed() throws Exception {
        assertThat(columnsOf("agent_runs"))
                .contains("subject_scope", "subjects_evaluated", "verdicts_emitted");
    }

    // --------------------------------------------------- V7: run coverage ---

    /**
     * The scope claim is an ARRAY of scope objects, one per subject kind.
     *
     * <p>Not a nicety: {@code aws.public_exposure_auditor} correlates buckets
     * and security groups ({@code cloud_resource}) with IAM users ({@code
     * principal}) in one run, and the chain between those kinds is the product.
     * The column has to hold a JSON array, and MySQL will accept a scalar into a
     * JSON column without complaint — so this asserts the type, and
     * {@code RunScopeTest} asserts the shape.
     */
    @Test
    void theRunScopeColumnHoldsJson() throws Exception {
        assertThat(columnType("agent_runs", "subject_scope")).isEqualTo("json");
        assertThat(columnsOf("agent_runs")).contains("scope_status");
    }

    /**
     * Subjects belong to their run and go when it goes.
     *
     * <p>MySQL enforces that the FK's columns are compatible — it refused an
     * earlier draft of V7 outright because {@code agent_runs.id} is {@code
     * BIGINT UNSIGNED} and the child column was signed. It does NOT enforce the
     * delete rule, and that is the part that fails silently: without CASCADE,
     * every purged run leaves its subject rows behind forever, and the table
     * that exists to make the reaper cheap becomes the reason it is not.
     */
    @Test
    void runSubjectsAreDeletedWithTheirRun() throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT delete_rule FROM information_schema.referential_constraints
                     WHERE constraint_schema = DATABASE()
                       AND constraint_name = 'fk_run_subject_run'
                     """)) {
            assertThat(rows.next())
                    .as("fk_run_subject_run is missing — agent_run_subject rows would "
                            + "outlive their runs and the reaper would join to them.")
                    .isTrue();
            assertThat(rows.getString(1)).isEqualTo("CASCADE");
        }
    }

    /** The reaper's join, in the order it filters: kind first, then the hash. */
    @Test
    void theReaperCanJoinFindingsToRunSubjectsOnAnIndex() throws Exception {
        assertThat(columnsOf("findings")).contains("subject_id_hash");
        assertThat(indexColumns("findings", "idx_findings_subject_hash"))
                .containsExactly("tenant_id", "subject_kind", "subject_id_hash");
        assertThat(indexColumns("agent_runs", "idx_agent_runs_reap"))
                .containsExactly("tenant_id", "agent_id", "scope_status", "finished_at");
    }

    /**
     * MySQL and Java agree on what a subject hash is.
     *
     * <p>The single most valuable assertion in this class, because it is the
     * only one covering a contract that spans two languages. The migration
     * backfills with {@code UNHEX(SHA2(subject_id, 256))}; agent-service writes
     * new rows with {@link com.intertec.autoops.agent.scope.SubjectDigest#hash};
     * agent-runtime computes the same thing in Python. If any of the three
     * disagrees — a different encoding, a trailing newline, a hex string stored
     * where bytes were meant — nothing throws. The join simply never matches,
     * findings never reap, and the backlog grows for a reason that looks nothing
     * like a hashing bug.
     */
    @Test
    void theSubjectHashMeansTheSameThingInMysqlAndInJava() throws Exception {
        // The same adversarial table asserted by SubjectDigestVectorsTest and
        // by tests/test_subject_digest.py in agent-runtime. The primitive is
        // the easy half; these cover the INPUT rules — case is significant,
        // whitespace is not stripped, non-ASCII is UTF-8 without a BOM.
        assertMysqlAgrees("abc");
        assertMysqlAgrees("arn:aws:s3:::intertec-prod-reports");
        assertMysqlAgrees("arn:aws:s3:::my-bucket");
        assertMysqlAgrees("ARN:AWS:S3:::my-bucket");
        assertMysqlAgrees(" arn:aws:s3:::my-bucket");
        assertMysqlAgrees("café");
        assertMysqlAgrees("ali.hassan@intertecsys.com");
    }

    /**
     * MySQL's connection charset does not quietly re-encode a non-ASCII id.
     *
     * <p>{@code SHA2} returns lowercase hex directly; wrapping it in
     * {@code HEX()} double-encodes into a 128-character string that still looks
     * like a hash, which is why V7's backfill reads {@code UNHEX(SHA2(...))}.
     *
     * <p>Bound as a parameter rather than inlined, because a literal in the
     * statement text travels under whatever charset the driver negotiated —
     * which is the one setting that could make Java and MySQL agree in this test
     * and disagree in production, where the value arrives as a bind.
     */
    private void assertMysqlAgrees(String subjectId) throws Exception {
        try (var statement = connection.prepareStatement("SELECT SHA2(?, 256)")) {
            statement.setString(1, subjectId);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1))
                        .as("subject id '%s'", subjectId)
                        .isEqualToIgnoringCase(java.util.HexFormat.of().formatHex(
                                com.intertec.autoops.agent.scope.SubjectDigest.hash(subjectId)));
            }
        }
    }

    /**
     * The V8 correction is present: coverage is decided per subject kind.
     *
     * <p>V7's comment said "PARTIAL never reaps", and that file cannot be edited
     * to say otherwise — it is applied on the deployed database and Flyway
     * validates its checksum. The corrected rule lives in a column comment,
     * which is where somebody reading the schema will actually find it.
     */
    @Test
    void theScopeStatusColumnSaysItIsOnlyARollUp() throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT column_comment FROM information_schema.columns
                     WHERE table_schema = DATABASE() AND table_name = 'agent_runs'
                       AND column_name = 'scope_status'
                     """)) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).contains("Roll-up only");
        }
    }

    /**
     * The hash column is BINARY(32), not a hex string.
     *
     * <p>A {@code CHAR(64)} holding hex would work and cost twice the index
     * bytes, and the difference only shows up as a slower reaper nobody traces
     * back here.
     */
    @Test
    void theSubjectHashIsStoredAsBytes() throws Exception {
        assertThat(columnType("findings", "subject_id_hash")).isEqualTo("binary");
        assertThat(columnType("agent_run_subject", "subject_id_hash")).isEqualTo("binary");
    }

    /**
     * The attribution counter, and the column name that is not a reserved word.
     *
     * <p>It was originally {@code day}, which MySQL accepts and H2 rejects — so
     * the unit tests caught a portability problem the deployed database never
     * would have. Pinned here because renaming it back would break the upsert in
     * a way only the H2 suite fails on, which is the wrong place to find out.
     */
    @Test
    void theVerdictAttributionCounterExists() throws Exception {
        assertThat(columnsOf("verdict_attribution_daily"))
                .contains("tenant_id", "agent_name", "bucket_day", "attributed",
                        "unattributed", "unscoped", "out_of_scope", "foreign_run", "late")
                .doesNotContain("day");
    }

    /** The store's four tables, so a dropped one is caught here and not in prod. */
    @Test
    void theFindingsStoreIsComplete() throws Exception {
        assertThat(tables()).contains(
                "findings", "finding_observations", "finding_suppressions",
                "finding_transitions", "agent_run_subject");
    }

    // ------------------------------------------------------------- helpers ---

    private List<String> indexColumns(String table, String index) throws Exception {
        List<String> columns = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT column_name FROM information_schema.statistics
                     WHERE table_schema = DATABASE() AND table_name = '%s'
                       AND index_name = '%s'
                     ORDER BY seq_in_index
                     """.formatted(table, index))) {
            while (rows.next()) {
                columns.add(rows.getString(1));
            }
        }
        return columns;
    }

    private String columnType(String table, String column) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT data_type FROM information_schema.columns
                     WHERE table_schema = DATABASE() AND table_name = '%s'
                       AND column_name = '%s'
                     """.formatted(table, column))) {
            return rows.next() ? rows.getString(1) : null;
        }
    }

    private List<String> columnsOf(String table) throws Exception {
        List<String> columns = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT column_name FROM information_schema.columns
                     WHERE table_schema = DATABASE() AND table_name = '%s'
                     """.formatted(table))) {
            while (rows.next()) {
                columns.add(rows.getString(1));
            }
        }
        return columns;
    }

    private List<String> tables() throws Exception {
        List<String> names = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT table_name FROM information_schema.tables "
                             + "WHERE table_schema = DATABASE()")) {
            while (rows.next()) {
                names.add(rows.getString(1));
            }
        }
        return names;
    }
}
