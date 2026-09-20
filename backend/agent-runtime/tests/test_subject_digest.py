"""The shared vector table for subject hashing — Python half.

**Twin file:** ``backend/agent-service/src/test/java/com/intertec/autoops/agent/
scope/SubjectDigestVectorsTest.java`` asserts the same inputs against the same
hex, and ``SchemaInvariantsIT`` asserts the single-id half against a real MySQL.
Change any one of the three and the other two fail.

That matters more here than for most tests, because **nothing about a digest
disagreement is loud**. A mismatch does not throw. It makes a join empty:
findings stop reaping, the backlog grows, and the coverage gauge reads healthy
the whole time because the run really is complete and really does carry a scope.
Every visible symptom points at the reaper, and you would read reaper predicates
for a day before suspecting that two ``sha256`` implementations disagree about
encoding.

Two copies rather than one shared file, with the cost stated: agent-service's
tests run in a container mounting only that service, so a single file is not
reachable from both. This still catches the realistic failure — one language's
recipe drifting — because that side fails against its own constants at once.
"""

from __future__ import annotations

import pytest

from agent_runtime.app.subject_scope import (
    SubjectIdError,
    require_well_formed,
    scope_dimensional,
    scope_enumerated,
    subject_hash,
    subject_set_digest,
)

# --------------------------------------------------------- single ids ---


def test_the_subject_hash_vectors():
    assert subject_hash("abc") == (
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    )
    assert subject_hash("arn:aws:s3:::my-bucket") == (
        "921a72e33486437a36c4290e4d105563b4691a410164c49609b1b0174cb3fba4"
    )
    assert subject_hash("ali.hassan@intertecsys.com") == (
        "9e8ac69442c847ed2396d687371f42792516065a733de3c1ada14a0903fb9ad3"
    )


def test_case_is_significant():
    """The ARN pair is why folding is refused rather than merely unimplemented.

    A careless producer treats these as one resource. They are two subjects
    here, and case-folding to "fix" it would be worse: S3 object keys are
    case-sensitive, so folding would merge subjects that really are different
    and resolve findings against the wrong one.
    """
    assert subject_hash("ARN:AWS:S3:::my-bucket") == (
        "200555faa6307d2b247ca1908a9fa4cfe182c4e80c18991d7fd9860ec6d6d1ee"
    )
    assert subject_hash("ARN:AWS:S3:::my-bucket") != subject_hash("arn:aws:s3:::my-bucket")


def test_surrounding_whitespace_is_not_silently_removed():
    assert subject_hash(" arn:aws:s3:::my-bucket") == (
        "3a8643381cfc9a84cfbf5871272e20d269eae3b7a70e9692d33f92a98fade3eb"
    )


def test_non_ascii_is_utf8_without_a_bom():
    assert subject_hash("café") == (
        "850f7dc43910ff890f8879c0ed26fe697c93a067ad93a7d50f466a7028a9bf4e"
    )


def test_the_empty_id_still_has_a_defined_hash():
    """Pinned even though ``require_well_formed`` refuses it.

    Otherwise somebody "fixes" the empty case by special-casing the hash and
    quietly changes what every other empty value hashes to.
    """
    assert subject_hash("") == (
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    )


def test_an_id_containing_the_set_separator_hashes_normally():
    assert subject_hash("a\nb") == (
        "7e18f737311b2dc3b2f269dd78396b0351f14fb66efa879f768cb23181883c78"
    )


# -------------------------------------------------------- set digests ---


def test_the_set_digest_vectors():
    assert subject_set_digest(["a", "b"]) == (
        "sha256:700dfd192c1fa8813a20119f60fac69bdaf8fbd38520b4fa5b0a21d00621d59b"
    )
    assert subject_set_digest(["café"]) == (
        "sha256:d20ff229eedb5b28b66d96040345c4bb80ba7ef808be730958f5b91dda477500"
    )
    assert subject_set_digest(["arn:aws:s3:::my-bucket", "ARN:AWS:S3:::my-bucket"]) == (
        "sha256:536124faeeb8578c4efb8d1542947613a45777f05516336f9ebeceaab380d45c"
    )


def test_two_ids_and_one_id_containing_the_separator_are_different_sets():
    """The vector that found a real bug.

    The first version joined ids with a newline on the stated reasoning that an
    id cannot contain one — an assumption about somebody else's data, and wrong
    in the worst way: ``["a","b"]`` and ``["a\\nb"]`` both serialised to
    ``"a\\nb\\n"`` and produced the SAME digest. Two different coverage claims,
    one digest: a scope that passes verification while describing a different
    set than the rows materialised beside it.
    """
    assert subject_set_digest(["a\nb"]) == (
        "sha256:15b4953576b15097f8aa76543ba28a6de87bf818764bc78889b2e28d45f61817"
    )
    assert subject_set_digest(["a\nb"]) != subject_set_digest(["a", "b"])


def test_ordering_above_the_basic_plane_follows_utf8_bytes():
    """The second bug these vectors found — on the Java side.

    Java's ``String.compareTo`` compares UTF-16 code units, so the high
    surrogate of U+1F600 (0xD83D) sorts BELOW U+FFFD, while Python's ``sorted``
    and MySQL's ``utf8mb4_bin`` compare UTF-8 bytes and put U+FFFD first. A
    ``TreeSet<String>`` framed this two-element set in the opposite order and
    produced a different digest — two ordinary ids, nothing malformed, nothing
    in any log. Both sides now sort on encoded bytes.
    """
    replacement = "�"
    grin = "\U0001f600"

    assert subject_set_digest([replacement, grin]) == (
        "sha256:18a4a4e516bc3b8c9de456e47a112b72b41de050ab91a039e2defa40a269995c"
    )
    # The premise: UTF-8 byte order puts the astral character last. Java's
    # natural ordering puts it first, which is the disagreement being pinned.
    assert replacement.encode("utf-8") < grin.encode("utf-8")


def test_a_duplicated_id_does_not_change_the_set_or_its_size():
    """Not cosmetic: ``subject_id_count`` feeds the completion coherence check.

    A bucket enumerated twice — reachable through two dimension slices, say —
    would otherwise make a correct run fail validation for doing nothing wrong.
    """
    assert subject_set_digest(["a", "a"]) == subject_set_digest(["a"])
    assert scope_enumerated("cloud_resource", ["a", "a"])["subject_id_count"] == 1


def test_the_set_digest_is_over_a_set():
    canonical = subject_set_digest(["a", "b"])
    assert subject_set_digest(["b", "a"]) == canonical
    assert subject_set_digest(["a", "b", "a"]) == canonical


def test_the_empty_set_has_a_digest():
    assert subject_set_digest([]) == (
        "sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    )


def test_the_subject_kind_is_not_part_of_either_hash():
    """No concatenation, so no question about how a null kind renders.

    The kind lives in its own indexed column on both tables. Mixing it into the
    hash is the classic way two implementations of one recipe diverge — empty
    string in one language, the literal ``"null"`` in another.
    """
    one = scope_enumerated("cloud_resource", ["i-0123"])
    other = scope_enumerated("principal", ["i-0123"])
    assert one["subject_ids_digest"] == other["subject_ids_digest"]


# -------------------------------------------------------- well-formed ---


def test_a_padded_id_is_refused_rather_than_trimmed():
    with pytest.raises(SubjectIdError, match="refused rather than trimmed"):
        require_well_formed(" arn:aws:s3:::my-bucket")


def test_an_empty_id_is_refused():
    with pytest.raises(SubjectIdError, match="cannot be empty"):
        require_well_formed("")


# ------------------------------------------------------------ builders ---


def test_an_enumerated_scope_reports_the_deduplicated_count():
    scope = scope_enumerated("alert_rule", ["b", "a", "a"])
    assert scope["subject_id_count"] == 2
    assert scope["subject_ids_digest"] == subject_set_digest(["a", "b"])


def test_a_derived_field_is_refused_as_a_dimension():
    """A scope says what was LOOKED AT, so anything produced by looking is out.

    ``severity`` is seductive because it yields a scope that reads tighter and
    more precise while describing the output set rather than the input set.
    """
    with pytest.raises(ValueError, match="not a dimension"):
        scope_dimensional("cloud_resource", severity=["high"])


def test_an_empty_dimension_list_is_refused():
    with pytest.raises(ValueError, match="Omit it entirely"):
        scope_dimensional("cloud_resource", environment=[])
