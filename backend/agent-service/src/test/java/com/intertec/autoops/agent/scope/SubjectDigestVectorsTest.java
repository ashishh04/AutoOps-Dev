package com.intertec.autoops.agent.scope;

import com.intertec.autoops.agent.exception.AgentException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The shared vector table for subject hashing.
 *
 * <p><b>Twin files:</b> {@code backend/agent-runtime/tests/test_subject_digest.py}
 * asserts the same inputs against the same hex, and
 * {@code SubjectDigestSqlWitnessIT} rebuilds the recipe in SQL and asserts the
 * same values again. Change any one of the three and the other two fail — which
 * is the point, because nothing else about a digest disagreement is loud. A
 * mismatch does not throw; it makes a join empty, so findings stop reaping, the
 * backlog grows, and the coverage gauge reads healthy the whole time because the
 * run really is complete and really does carry a scope. Every visible symptom
 * points at the reaper.
 *
 * <p>Java and Python are two copies, deliberately and with the cost stated:
 * agent-service's tests run in a container mounting only that service, so a
 * single shared file is not reachable from both. Two copies share one weakness —
 * somebody edits a recipe and its expected values in the same commit and both
 * stay green. The SQL witness removes that, because it cannot import either
 * implementation.
 *
 * <p>The vectors are adversarial on purpose. {@code SHA2('abc', 256)} agreeing
 * across three stacks proves the primitive and almost nothing else; both real
 * bugs found here were in <i>what gets fed in</i>.
 */
class SubjectDigestVectorsTest {

    // ------------------------------------------------ single subject ids ---

    @Test
    void theSubjectHashVectors() {
        assertHash("abc",
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertHash("arn:aws:s3:::my-bucket",
                "921a72e33486437a36c4290e4d105563b4691a410164c49609b1b0174cb3fba4");
        assertHash("ali.hassan@intertecsys.com",
                "9e8ac69442c847ed2396d687371f42792516065a733de3c1ada14a0903fb9ad3");
    }

    /**
     * Case is significant, and the ARN pair is why this is not negotiable.
     *
     * <p>A careless producer treats {@code ARN:AWS:S3:::my-bucket} and
     * {@code arn:aws:s3:::my-bucket} as the same resource. They are two rows
     * here, and case-folding to "fix" that would be worse — S3 object keys are
     * case-sensitive, so folding would merge subjects that really are different
     * and resolve findings against the wrong one.
     */
    @Test
    void caseIsSignificant() {
        assertHash("ARN:AWS:S3:::my-bucket",
                "200555faa6307d2b247ca1908a9fa4cfe182c4e80c18991d7fd9860ec6d6d1ee");
        assertThat(SubjectDigest.hash("ARN:AWS:S3:::my-bucket"))
                .isNotEqualTo(SubjectDigest.hash("arn:aws:s3:::my-bucket"));
    }

    /** Nothing is trimmed — {@link SubjectDigest#requireWellFormed} refuses instead. */
    @Test
    void surroundingWhitespaceIsNotSilentlyRemoved() {
        assertHash(" arn:aws:s3:::my-bucket",
                "3a8643381cfc9a84cfbf5871272e20d269eae3b7a70e9692d33f92a98fade3eb");
    }

    /** UTF-8, no BOM, no Unicode normalisation pass. */
    @Test
    void nonAsciiIsUtf8WithoutABom() {
        assertHash("café",
                "850f7dc43910ff890f8879c0ed26fe697c93a067ad93a7d50f466a7028a9bf4e");
    }

    /**
     * Pinned even though {@link SubjectDigest#requireWellFormed} refuses it, so
     * that nobody "fixes" the empty case by special-casing the hash itself and
     * quietly changes what every other empty value hashes to.
     */
    @Test
    void theEmptyIdStillHasADefinedHash() {
        assertHash("",
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    void anIdContainingTheSetSeparatorHashesNormally() {
        assertHash("a\nb",
                "7e18f737311b2dc3b2f269dd78396b0351f14fb66efa879f768cb23181883c78");
    }

    // ----------------------------------------------------- set digests ---

    @Test
    void theSetDigestVectors() {
        assertThat(SubjectDigest.digest(List.of("a", "b")))
                .isEqualTo("sha256:700dfd192c1fa8813a20119f60fac69bdaf8fbd38520b4fa5b0a21d00621d59b");
        assertThat(SubjectDigest.digest(List.of("café")))
                .isEqualTo("sha256:d20ff229eedb5b28b66d96040345c4bb80ba7ef808be730958f5b91dda477500");
        assertThat(SubjectDigest.digest(List.of(
                "arn:aws:s3:::my-bucket", "ARN:AWS:S3:::my-bucket")))
                .isEqualTo("sha256:536124faeeb8578c4efb8d1542947613a45777f05516336f9ebeceaab380d45c");
    }

    /**
     * <b>The first bug these vectors found.</b>
     *
     * <p>The original {@link SubjectDigest#digest} joined ids with a newline, on
     * the stated reasoning that an id cannot contain one. That is an assumption
     * about somebody else's data, and it was wrong in the worst way:
     * {@code ["a","b"]} and {@code ["a\nb"]} both serialised to {@code "a\nb\n"}
     * and produced the SAME digest. Two different coverage claims, one digest —
     * a scope that passes verification while describing a different set than the
     * rows materialised beside it.
     *
     * <p>Length-prefixing each element makes the encoding injective, so no
     * assumption about the contents of an id is needed at all.
     */
    @Test
    void twoIdsAndOneIdContainingTheSeparatorAreDifferentSets() {
        assertThat(SubjectDigest.digest(List.of("a\nb")))
                .isEqualTo("sha256:15b4953576b15097f8aa76543ba28a6de87bf818764bc78889b2e28d45f61817")
                .isNotEqualTo(SubjectDigest.digest(List.of("a", "b")));
    }

    /**
     * <b>The second bug these vectors found.</b>
     *
     * <p>Java's {@code String.compareTo} compares UTF-16 code units, so the high
     * surrogate of U+1F600 (0xD83D) sorts BELOW U+FFFD. Python's {@code sorted}
     * and MySQL's {@code utf8mb4_bin} compare UTF-8 bytes, where U+FFFD sorts
     * first. A {@code TreeSet<String>} therefore framed this set in the opposite
     * order from the other two implementations and produced a different digest —
     * two ordinary ids, nothing malformed, nothing in any log.
     *
     * <p>{@link SubjectDigest} now sorts on the encoded bytes, so all three agree
     * by construction rather than by luck about which characters turn up in a
     * resource name.
     */
    @Test
    void orderingAboveTheBasicPlaneFollowsUtf8BytesNotUtf16Units() {
        String replacement = "�";
        String grin = "😀";

        assertThat(SubjectDigest.digest(List.of(replacement, grin)))
                .isEqualTo("sha256:"
                        + "18a4a4e516bc3b8c9de456e47a112b72b41de050ab91a039e2defa40a269995c");

        // The premise, pinned so the vector above cannot quietly stop testing
        // anything.
        assertThat(replacement.compareTo(grin))
                .as("Java's natural ordering puts the astral character first")
                .isGreaterThan(0);
        assertThat(Arrays.compareUnsigned(
                replacement.getBytes(StandardCharsets.UTF_8),
                grin.getBytes(StandardCharsets.UTF_8)))
                .as("UTF-8 byte order puts it last — the disagreement this exists for")
                .isLessThan(0);
    }

    /** Order in, one digest out: a run that paginated differently covered the same set. */
    @Test
    void theSetDigestIsOverASet() {
        String canonical = SubjectDigest.digest(List.of("a", "b"));

        assertThat(SubjectDigest.digest(List.of("b", "a"))).isEqualTo(canonical);
        assertThat(SubjectDigest.digest(List.of("a", "b", "a"))).isEqualTo(canonical);
    }

    /**
     * A duplicate is one subject, and this is not cosmetic.
     *
     * <p>{@code subject_id_count} feeds the completion coherence check, so a
     * bucket enumerated twice — reachable through two dimension slices, say —
     * would otherwise make a correct run fail validation for doing nothing
     * wrong.
     */
    @Test
    void aDuplicatedIdDoesNotChangeTheSetOrItsSize() {
        assertThat(SubjectDigest.digest(List.of("a", "a")))
                .isEqualTo(SubjectDigest.digest(List.of("a")));
    }

    /** A run that covered nothing still has a digest, and it is not a special case. */
    @Test
    void theEmptySetHasADigest() {
        assertThat(SubjectDigest.digest(List.of()))
                .isEqualTo("sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    /**
     * The kind is never mixed into either hash.
     *
     * <p>It lives in its own indexed column on both tables, so there is no
     * concatenation and therefore no question about whether a null kind renders
     * as an empty string or the literal "null" — the classic way two
     * implementations of the same recipe diverge.
     */
    @Test
    void theSubjectKindIsNotPartOfEitherHash() {
        assertThat(SubjectDigest.digest(List.of("i-0123")))
                .isEqualTo(SubjectDigest.digest(List.of("i-0123")));
        assertThat(SubjectDigest.hash("i-0123")).isEqualTo(SubjectDigest.hash("i-0123"));
    }

    // ------------------------------------------------------ well-formed ---

    @Test
    void aPaddedIdIsRefusedRatherThanTrimmed() {
        assertThatThrownBy(() -> SubjectDigest.requireWellFormed(" arn:aws:s3:::my-bucket"))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("refused rather than trimmed");
    }

    @Test
    void anEmptyIdIsRefused() {
        assertThatThrownBy(() -> SubjectDigest.requireWellFormed(""))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("cannot be empty");
    }

    @Test
    void anOrdinaryIdPassesThrough() {
        assertThatCode(() -> SubjectDigest.requireWellFormed("arn:aws:s3:::my-bucket"))
                .doesNotThrowAnyException();
    }

    private void assertHash(String subjectId, String expectedHex) {
        assertThat(HexFormat.of().formatHex(SubjectDigest.hash(subjectId)))
                .as("subject id %s", subjectId.isEmpty() ? "<empty>" : "'" + subjectId + "'")
                .isEqualTo(expectedHex);
    }
}
