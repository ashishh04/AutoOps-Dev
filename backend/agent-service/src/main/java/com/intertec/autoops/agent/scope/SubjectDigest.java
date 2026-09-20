package com.intertec.autoops.agent.scope;

import com.intertec.autoops.agent.exception.AgentException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.TreeSet;

/**
 * The two hashes the scope machinery needs, and the exact recipe for each.
 *
 * <p>Written down rather than left to each caller because three systems compute
 * these: agent-runtime in Python, agent-service in Java, and the V7 migration in
 * SQL. <b>A disagreement between any two of them does not throw.</b> It makes a
 * join empty — findings never reap, the backlog grows, and the coverage gauge
 * reads healthy because the run genuinely is complete and genuinely does carry a
 * scope. Every symptom points at the reaper. See
 * {@code SubjectDigestVectorsTest} and its Python twin for the shared vectors
 * that pin this down.
 *
 * <p><b>The primitive is the easy half.</b> That {@code SHA2(x, 256)} agrees
 * across three languages proves almost nothing on its own; what matters is what
 * gets fed in. So the input rules are part of the contract:
 *
 * <ul>
 *   <li><b>No normalisation.</b> Ids are hashed as the exact UTF-8 bytes given.
 *       Not trimmed, not case-folded, not Unicode-normalised. Case-folding would
 *       be actively wrong — S3 object keys are case-sensitive, and two ids that
 *       differ only in case are two subjects until somebody proves otherwise.</li>
 *   <li><b>Malformed ids are refused, not repaired.</b> An id with surrounding
 *       whitespace is a producer bug; trimming it silently means the same
 *       resource hashes two ways depending on which code path found it.</li>
 *   <li><b>The set digest length-prefixes each element.</b> See
 *       {@link #digest}.</li>
 *   <li><b>{@code subject_kind} is never mixed in.</b> The kind lives in its own
 *       indexed column on both tables, so there is no concatenation and
 *       therefore no question about how a null kind renders.</li>
 * </ul>
 */
public final class SubjectDigest {

    /** Prefix on the wire, so the algorithm can change without ambiguity. */
    public static final String PREFIX = "sha256:";

    /**
     * UTF-8 byte order — what Python's {@code sorted} and MySQL's
     * {@code utf8mb4_bin} both use.
     *
     * <p>Deliberately not {@link String#compareTo}; see {@link #digest}.
     */
    private static final Comparator<String> BY_UTF8_BYTES =
            (left, right) -> Arrays.compareUnsigned(
                    left.getBytes(StandardCharsets.UTF_8),
                    right.getBytes(StandardCharsets.UTF_8));

    private SubjectDigest() {
    }

    /**
     * The per-subject hash stored in {@code findings.subject_id_hash} and
     * {@code agent_run_subject.subject_id_hash}.
     *
     * <p>A hash rather than the id itself because {@code subject_id VARCHAR(512)}
     * costs 2048 bytes of MySQL's 3072-byte index budget in utf8mb4 — two of
     * them in one composite is error 1071. {@code BINARY(32)} is fixed width and
     * needs no prefix, so there is no prefix-collision question to revisit once
     * there is volume.
     *
     * <p>Must agree byte for byte with the V7 migration's
     * {@code UNHEX(SHA2(subject_id, 256))}, which is asserted against a real
     * MySQL in {@code SchemaInvariantsIT}.
     */
    public static byte[] hash(String subjectId) {
        return sha256(subjectId.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The digest over a whole enumerated scope.
     *
     * <p><b>Sorted by UTF-8 bytes, deduplicated, length-prefixed, UTF-8.</b>
     * Sorted because the order a run happened to page its subjects in is not
     * part of what it covered. Deduplicated because the same bucket reachable
     * through two dimension slices is one subject, and because
     * {@code subject_id_count} feeds a coherence check — a duplicate would make
     * a correct run fail validation for doing nothing wrong.
     *
     * <p><b>By UTF-8 bytes, not by {@link String#compareTo}.</b> Java's natural
     * ordering compares UTF-16 code units, so a high surrogate (0xD83D…) sorts
     * below U+E000–U+FFFF. Python's {@code sorted} and MySQL's
     * {@code utf8mb4_bin} both compare UTF-8 bytes, where the astral character
     * sorts above. The set {@code {U+FFFD, U+1F600}} therefore frames in one
     * order here and the opposite order there — two digests for one set, in the
     * one place where a disagreement is silent. Sorting on the encoded bytes
     * makes all three agree by construction rather than by luck about which
     * characters turn up in a resource name.
     *
     * <p>The length prefix is the part that is easy to get wrong. An earlier
     * draft joined ids with a newline on the reasoning that an id cannot contain
     * one — which is an assumption about somebody else's data, and it is exactly
     * wrong: {@code ["a", "b"]} and {@code ["a\nb"]} both serialise to
     * {@code "a\nb\n"} and collide. Two different coverage claims with one
     * digest is a scope that passes verification while describing the wrong set.
     * Prefixing each element with its byte length — {@code "1:a\n1:b\n"} against
     * {@code "3:a\nb\n"} — makes the encoding injective, so no assumption about
     * the contents of an id is needed at all.
     */
    public static String digest(Collection<String> subjectIds) {
        StringBuilder canonical = new StringBuilder();
        TreeSet<String> ordered = new TreeSet<>(BY_UTF8_BYTES);
        ordered.addAll(subjectIds);
        for (String id : ordered) {
            canonical.append(id.getBytes(StandardCharsets.UTF_8).length)
                    .append(':').append(id).append('\n');
        }
        return PREFIX + HexFormat.of()
                .formatHex(sha256(canonical.toString().getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Refuses a subject id that would hash two ways depending on who found it.
     *
     * <p>Applied where ids enter the store, never inside {@link #hash}, so that
     * the hash stays a pure function of its bytes and this stays a policy about
     * what a producer is allowed to send.
     */
    public static String requireWellFormed(String subjectId) {
        if (subjectId == null || subjectId.isEmpty()) {
            throw AgentException.badRequest("subject_id_empty",
                    "A subject id cannot be empty. An empty id hashes to a real value and would "
                            + "silently become a subject nobody can name.");
        }
        if (!subjectId.equals(subjectId.strip())) {
            throw AgentException.badRequest("subject_id_padded",
                    "The subject id '" + subjectId + "' has surrounding whitespace. It is "
                            + "refused rather than trimmed: trimming means the same resource "
                            + "hashes two ways depending on which code path found it, and the "
                            + "join to it simply stops matching.");
        }
        return subjectId;
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every JVM; this branch cannot be reached.
            throw new IllegalStateException(e);
        }
    }
}
