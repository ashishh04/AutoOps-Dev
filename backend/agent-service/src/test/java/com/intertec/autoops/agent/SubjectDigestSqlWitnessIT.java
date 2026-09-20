package com.intertec.autoops.agent;

import com.intertec.autoops.agent.scope.SubjectDigest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A third implementation of the digest recipe, in a language nobody on this
 * project will edit while editing the other two.
 *
 * <p><b>Why this exists and {@code SchemaInvariantsIT}'s hash check is not
 * enough.</b> That check proves {@code SHA2(x, 256)} means the same thing in
 * MySQL and Java — the primitive. The primitive was never the problem. The bug
 * that actually shipped was in the <i>framing</i>: how a set of ids becomes the
 * one string that gets hashed. An earlier draft joined ids with a newline, so
 * {@code ["a","b"]} and {@code ["a\nb"]} produced the same digest, and no
 * amount of agreeing about SHA-256 would have caught it.
 *
 * <p>The vector tables in Java and Python are two copies, and two copies share a
 * weakness: somebody changes a recipe and its expected values in the same
 * commit, and both suites stay green. This file removes that, because SQL
 * cannot import either implementation — it rebuilds the canonical string from
 * first principles out of {@code LENGTH}, {@code CONCAT} and {@code
 * GROUP_CONCAT}, and asserts against the same literal hex the other two assert.
 * A drifting recipe now has to get past an implementation that is not yours.
 *
 * <p><b>The collation is load-bearing, and it is why this is a test and not a
 * production code path.</b> MySQL's default {@code utf8mb4_0900_ai_ci} is
 * accent- and case-insensitive: under it {@code DISTINCT} would collapse
 * {@code arn:aws:s3:::my-bucket} and {@code ARN:AWS:S3:::my-bucket} into one
 * row, and {@code ORDER BY} would put them in an unspecified order. Both are
 * silently wrong for a digest. {@code utf8mb4_bin} compares UTF-8 bytes, which
 * is what Java and Python now do — see {@link #theDefaultCollationWouldBeWrong}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SubjectDigestSqlWitnessIT {

    private static MySqlTestDatabase database;
    private static Connection connection;

    @BeforeAll
    void connect() throws Exception {
        // No migration needed: this exercises MySQL's own string handling, not
        // the schema.
        database = MySqlTestDatabase.start();
        connection = database.connection();
    }

    @AfterAll
    void release() throws Exception {
        database.close();
    }

    // --------------------------------------------------- the framed digest ---

    @Test
    void mysqlFramesTheSameSetsTheSameWay() throws Exception {
        assertFramedDigest(
                "sha256:700dfd192c1fa8813a20119f60fac69bdaf8fbd38520b4fa5b0a21d00621d59b",
                "a", "b");
        assertFramedDigest(
                "sha256:d20ff229eedb5b28b66d96040345c4bb80ba7ef808be730958f5b91dda477500",
                "café");
        assertFramedDigest(
                "sha256:536124faeeb8578c4efb8d1542947613a45777f05516336f9ebeceaab380d45c",
                "arn:aws:s3:::my-bucket", "ARN:AWS:S3:::my-bucket");
    }

    /**
     * The collision vector, witnessed by an implementation that cannot have
     * inherited the mistake.
     */
    @Test
    void mysqlAlsoSeparatesTwoIdsFromOneContainingTheSeparator() throws Exception {
        assertFramedDigest(
                "sha256:15b4953576b15097f8aa76543ba28a6de87bf818764bc78889b2e28d45f61817",
                "a\nb");
    }

    /** Order in, one digest out. */
    @Test
    void mysqlCanonicalisesOrder() throws Exception {
        assertFramedDigest(
                "sha256:700dfd192c1fa8813a20119f60fac69bdaf8fbd38520b4fa5b0a21d00621d59b",
                "b", "a");
    }

    /**
     * A duplicate is one subject.
     *
     * <p>Not cosmetic: {@code subject_id_count} feeds the completion coherence
     * check, so a bucket enumerated twice — reachable through two dimension
     * slices, say — would otherwise make a correct run fail validation for doing
     * nothing wrong.
     */
    @Test
    void mysqlDeduplicates() throws Exception {
        assertFramedDigest(
                "sha256:700dfd192c1fa8813a20119f60fac69bdaf8fbd38520b4fa5b0a21d00621d59b",
                "a", "b", "a", "b");
    }

    /**
     * The astral-character vector: where Java's natural ordering would have
     * disagreed with both of the others.
     *
     * <p>{@code String.compareTo} compares UTF-16 code units, so the high
     * surrogate of U+1F600 (0xD83D) sorts below U+FFFD. UTF-8 byte order — which
     * Python and {@code utf8mb4_bin} use — puts U+FFFD first. Java now sorts on
     * the encoded bytes, and this is the assertion that keeps it that way.
     */
    @Test
    void mysqlAndJavaAgreeOnOrderAboveTheBasicPlane() throws Exception {
        assertFramedDigest(
                "sha256:18a4a4e516bc3b8c9de456e47a112b72b41de050ab91a039e2defa40a269995c",
                "�", "😀");
    }

    /** A run that covered nothing is not a special case in any of the three. */
    @Test
    void mysqlFramesTheEmptySet() throws Exception {
        assertFramedDigest(
                "sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    // ------------------------------------------------------- the collation ---

    /**
     * Demonstrates the trap rather than merely warning about it.
     *
     * <p>The literals carry a {@code _utf8mb4} introducer rather than relying on
     * the session charset — without it the comparison is attempted in whatever
     * the connection negotiated, and on a latin1 session MySQL refuses the
     * collation outright rather than answering wrongly.
     *
     * <p>Under MySQL's default collation the two ARNs are equal, so
     * {@code DISTINCT} keeps one of them. If the framing were ever done in SQL
     * against a default-collated column, a run covering both would hash as a run
     * covering one — a digest mismatch with the rows sitting beside it, and the
     * scope quietly unusable.
     */
    @Test
    void theDefaultCollationWouldBeWrong() throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT
                       (SELECT COUNT(DISTINCT id COLLATE utf8mb4_0900_ai_ci) FROM (
                          SELECT _utf8mb4'arn:aws:s3:::my-bucket' AS id
                          UNION ALL SELECT _utf8mb4'ARN:AWS:S3:::my-bucket') t) AS ci,
                       (SELECT COUNT(DISTINCT id COLLATE utf8mb4_bin) FROM (
                          SELECT _utf8mb4'arn:aws:s3:::my-bucket' AS id
                          UNION ALL SELECT _utf8mb4'ARN:AWS:S3:::my-bucket') t) AS bin
                     """)) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt("ci"))
                    .as("the default collation folds case, so these two ARNs read as one")
                    .isEqualTo(1);
            assertThat(rows.getInt("bin"))
                    .as("utf8mb4_bin keeps them apart, as Java and Python do")
                    .isEqualTo(2);
        }
    }

    // ------------------------------------------------------------ helpers ---

    /**
     * Rebuilds the canonical string in SQL and hashes it there.
     *
     * <p>Ids are bound as parameters rather than inlined, because a literal in
     * the statement text travels under whatever charset the driver negotiated —
     * the one setting that could make this pass here and fail in production,
     * where the value always arrives as a bind.
     *
     * <p>Three MySQL details, each of which silently produces a wrong answer:
     * {@code LENGTH} is <i>byte</i> length while {@code CHAR_LENGTH} is
     * characters (the recipe wants bytes, which is what makes the café vector
     * meaningful); {@code CHAR(10 USING utf8mb4)} rather than a literal newline
     * keeps the separator from being coerced to a binary string; and
     * <b>{@code SHA2} already returns lowercase hex, not raw bytes</b> — wrapping
     * it in {@code HEX()} double-encodes and yields a 128-character string that
     * still looks like a hash. That last one is why V7's backfill reads
     * {@code UNHEX(SHA2(...))}.
     */
    private void assertFramedDigest(String expected, String... ids) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TEMPORARY TABLE IF EXISTS digest_vector");
            statement.execute("""
                    CREATE TEMPORARY TABLE digest_vector (
                      id VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL)
                    """);
        }
        try (PreparedStatement insert =
                     connection.prepareStatement("INSERT INTO digest_vector (id) VALUES (?)")) {
            for (String id : ids) {
                insert.setString(1, id);
                insert.addBatch();
            }
            insert.executeBatch();
        }

        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT LOWER(SHA2(COALESCE((
                         SELECT GROUP_CONCAT(
                                  CONCAT(LENGTH(id), ':', id, CHAR(10 USING utf8mb4))
                                  ORDER BY id SEPARATOR '')
                           FROM (SELECT DISTINCT id FROM digest_vector) d), ''), 256))
                     """)) {
            assertThat(rows.next()).isTrue();
            String fromSql = SubjectDigest.PREFIX + rows.getString(1);

            assertThat(fromSql)
                    .as("MySQL framed %s", List.of(ids))
                    .isEqualTo(expected)
                    .isEqualTo(SubjectDigest.digest(List.of(ids)));
        }
    }
}
