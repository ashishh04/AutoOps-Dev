"""Reading subjects out of a tool result, and refusing when that is not safe.

The assertions worth reading twice are the ones separating cases that all
produce *no subjects*. An empty enumeration is a valid coverage claim — "I
looked and found nothing" — that the reaper acts on, so anything that yields
zero subjects for a reason other than genuine emptiness has to be a refusal.
"""

from __future__ import annotations

import json

from agent_runtime.app.extraction import (
    Outcome,
    SubjectSource,
    extract,
    merge,
    resolve,
)
from agent_runtime.app.config import ELISION_MARKER

# Modelled on RD-136, which really does return three lists from one call and
# really does put `region` at the document level while volume ids sit on items.
VOLUMES = SubjectSource(
    subject_kind="cloud_resource",
    items="volumes",
    id_template="{region}/{volume_id}",
    total_field="total_in_region",
)


def result(**document) -> str:
    document.setdefault("region", "me-south-1")
    return json.dumps(document)


# ------------------------------------------------------------ the good path ---


def test_subjects_are_read_from_the_declared_field():
    got = extract(result(volumes=[{"volume_id": "vol-1"}, {"volume_id": "vol-2"}]),
                  VOLUMES, tool_ok=True)

    assert got.outcome is Outcome.ENUMERATED
    assert got.subject_ids == ("me-south-1/vol-1", "me-south-1/vol-2")
    assert got.subject_kind == "cloud_resource"


def test_a_trailing_json_line_after_a_human_report_is_read():
    """Several catalog automations print a report then one machine line."""
    text = "IDLE RESOURCE INVENTORY\nregion=me-south-1\n\nJSON " + result(
        volumes=[{"volume_id": "vol-9"}])

    assert extract(text, VOLUMES, tool_ok=True).subject_ids == ("me-south-1/vol-9",)


# --------------------------------------------------------- source verified ---


def test_a_source_total_is_recorded_and_marks_the_claim_verified():
    got = extract(result(volumes=[{"volume_id": "vol-1"}], total_in_region=1),
                  VOLUMES, tool_ok=True)

    assert got.source_total == 1
    assert got.source_verified is True


def test_without_a_declared_total_the_claim_is_unverified():
    """``len(subject_ids)`` is not a source total and must not read as one.

    A count derived from the list agrees with the list in every case including
    the broken ones, so it provides no signal — which is worse than none,
    because it reads as verification. See TRUNCATION.md.
    """
    source = SubjectSource("cloud_resource", "volumes", "{region}/{volume_id}")

    got = extract(result(volumes=[{"volume_id": "vol-1"}]), source, tool_ok=True)

    assert got.source_total is None
    assert got.source_verified is False


# ------------------------------------------- the four ways to produce zero ---


def test_a_genuinely_empty_inventory_is_empty_not_a_failure():
    """An estate with no idle volumes is not a bug, and may be declared."""
    got = extract(result(volumes=[], total_in_region=0), VOLUMES, tool_ok=True)

    assert got.outcome is Outcome.EMPTY
    assert got.outcome.may_enumerate is True


def test_a_failed_tool_is_not_an_extraction_problem():
    """Ordinary degraded operation — the element becomes PARTIAL or SKIPPED.

    Calling it unreadable would diagnose a flaky API as a platform bug, which
    is the same conflation ``foreign_run`` and ``run_already_completed`` were
    split to avoid.
    """
    got = extract("boto3 threw", VOLUMES, tool_ok=False)

    assert got.outcome is Outcome.TOOL_FAILED
    assert "did not succeed" in got.reason
    # Not a platform bug — but still a GAP, which is what keeps the kind from
    # claiming COMPLETE over the sources that happened to work.
    assert got.outcome.is_a_gap is True


def test_unreadable_output_refuses_rather_than_declaring_nothing():
    got = extract("a wall of prose with no machine line", VOLUMES, tool_ok=True)

    assert got.outcome is Outcome.UNREADABLE
    assert got.outcome.may_enumerate is False


def test_elided_content_refuses():
    """The guard for somebody moving extraction after ``_compact``.

    Ordering is the real enforcement; this turns a reorder from a silently
    smaller scope into a loud refusal.
    """
    text = result(volumes=[{"volume_id": "vol-1"}])
    text = text[:20] + "\n" + ELISION_MARKER.format(count=9000) + "\n" + text[20:]

    got = extract(text, VOLUMES, tool_ok=True)

    assert got.outcome is Outcome.TRUNCATED
    assert got.outcome.may_enumerate is False


# --------------------------------------------------------------- the trap ---


def test_a_non_empty_list_with_no_readable_ids_refuses():
    """**The zero-subjects trap arriving through the declaration.**

    The author declared ``volume_id``; the tool now returns ``VolumeId``. The
    result is well-formed and non-empty, and every id reads as absent. Reporting
    EMPTY would declare "I looked and found nothing" about an estate full of
    volumes, and the reaper would act on that — it is a valid claim.

    This is the more likely version of the trap than an unparseable document,
    because tool output shapes change and declarations do not follow.
    """
    got = extract(result(volumes=[{"VolumeId": "vol-1"}, {"VolumeId": "vol-2"}]),
                  VOLUMES, tool_ok=True)

    assert got.outcome is Outcome.UNREADABLE
    assert "could not fill" in got.reason
    assert "2 item(s)" in got.reason


def test_a_missing_list_refuses_rather_than_reading_as_empty():
    got = extract(result(instances=[{"volume_id": "vol-1"}]), VOLUMES, tool_ok=True)

    assert got.outcome is Outcome.UNREADABLE
    assert "shape has changed" in got.reason


def test_a_malformed_subject_id_refuses_the_whole_extraction():
    """One padded id is a producer bug, not a subject to silently drop.

    Dropping it would under-declare the scope by exactly one subject, which
    reaps that subject's findings on the next run.

    The padding is checked on each COMPONENT rather than on the composed id,
    because composition hides it: " vol-2" inside "{region}/{volume_id}" becomes
    "me-south-1/ vol-2", which has no surrounding whitespace at all. This test
    failed exactly that way when the template was introduced.
    """
    got = extract(result(volumes=[{"volume_id": "vol-1"}, {"volume_id": " vol-2"}]),
                  VOLUMES, tool_ok=True)

    assert got.outcome is Outcome.UNREADABLE
    assert "whitespace" in got.reason


# -------------------------------------------------------------- multi-kind ---


def test_one_kind_may_come_from_several_tools():
    """The correlator shape: buckets and security groups are both cloud_resource.

    Kept separate until the end so one audit failing does not cost the coverage
    the other established.
    """
    buckets = extract(result(volumes=[{"volume_id": "arn:aws:s3:::a"}]),
                      VOLUMES, tool_ok=True)
    groups = extract(result(volumes=[{"volume_id": "sg-1"}]), VOLUMES, tool_ok=True)
    principals = extract(
        result(users=[{"user": "ali@intertecsys.com"}]),
        SubjectSource("principal", "users", "{user}"), tool_ok=True)

    grouped = merge([buckets, groups, principals])

    assert set(grouped) == {"cloud_resource", "principal"}
    assert len(grouped["cloud_resource"]) == 2
    assert all(e.outcome is Outcome.ENUMERATED for e in grouped["principal"])


def test_one_failing_kind_does_not_contaminate_another():
    """The forced-IAM-failure shape, at the extraction layer."""
    resources = extract(result(volumes=[{"volume_id": "vol-1"}]), VOLUMES, tool_ok=True)
    principals = extract("IAM threw", SubjectSource("principal", "users", "{user}"),
                         tool_ok=False)

    grouped = merge([resources, principals])

    assert grouped["cloud_resource"][0].outcome is Outcome.ENUMERATED
    assert grouped["principal"][0].outcome is Outcome.TOOL_FAILED
    assert grouped["cloud_resource"][0].subject_ids == ("me-south-1/vol-1",)


# ------------------------------------------------------------- composition ---


def test_region_scoped_ids_are_composed_so_two_regions_cannot_collide():
    """**Why the id is a template and not a field name.**

    ``vol-…`` and ``sg-…`` are region-scoped and an IAM ``user`` is
    account-scoped, so a run covering two regions would otherwise produce two
    different resources under one subject id — and a finding reaped by evidence
    about something else entirely.
    """
    one = extract(result(region="me-south-1", volumes=[{"volume_id": "vol-1"}]),
                  VOLUMES, tool_ok=True)
    two = extract(result(region="eu-west-1", volumes=[{"volume_id": "vol-1"}]),
                  VOLUMES, tool_ok=True)

    assert one.subject_ids != two.subject_ids
    assert one.subject_ids == ("me-south-1/vol-1",)


def test_an_item_field_wins_over_a_document_field_of_the_same_name():
    """The item is the more specific answer where both carry a name."""
    source = SubjectSource("cloud_resource", "volumes", "{region}/{volume_id}")

    got = extract(result(region="doc-region",
                         volumes=[{"volume_id": "vol-1", "region": "item-region"}]),
                  source, tool_ok=True)

    assert got.subject_ids == ("item-region/vol-1",)


def test_a_missing_document_level_placeholder_refuses():
    """Composition failure is a changed shape, not a missing subject."""
    got = extract(json.dumps({"volumes": [{"volume_id": "vol-1"}]}),
                  VOLUMES, tool_ok=True)

    assert got.outcome is Outcome.UNREADABLE
    assert "could not fill" in got.reason


# ------------------------------------------------- tool-reported truncation ---


def test_a_tool_that_reports_its_own_truncation_is_refused():
    """RD-203 already sets a ``truncated`` flag.

    This is the ONLY signal that exists for truncation upstream of this service
    — TRUNCATION.md otherwise records that case as unclosable — so where a tool
    offers it, refusing on it closes a real gap.
    """
    source = SubjectSource("principal", "rules", "{user}", truncated_field="truncated")

    got = extract(json.dumps({"truncated": True, "rules": [{"user": "a@b.com"}]}),
                  source, tool_ok=True)

    assert got.outcome is Outcome.TRUNCATED
    assert "reported its own output truncated" in got.reason


def test_a_tool_reporting_not_truncated_enumerates_normally():
    source = SubjectSource("principal", "rules", "{user}", truncated_field="truncated")

    got = extract(json.dumps({"truncated": False, "rules": [{"user": "a@b.com"}]}),
                  source, tool_ok=True)

    assert got.outcome is Outcome.ENUMERATED


def test_our_elision_and_their_truncation_have_different_reasons():
    """Kept apart so "our pipeline elided" is not diagnosed as "their API paged"."""
    source = SubjectSource("principal", "rules", "{user}", truncated_field="truncated")

    theirs = extract(json.dumps({"truncated": True, "rules": []}), source, tool_ok=True)
    ours = extract(ELISION_MARKER.format(count=1) + json.dumps({"rules": []}),
                   source, tool_ok=True)

    assert theirs.outcome is ours.outcome is Outcome.TRUNCATED
    assert theirs.reason != ours.reason


# ------------------------------------- three sources, one kind, one tool ---

EIPS = SubjectSource("cloud_resource", "eips", "{region}/{alloc_id}")
INSTANCES = SubjectSource("cloud_resource", "instances", "{region}/{instance_id}")


def test_all_three_sources_succeeding_is_complete_coverage_of_the_kind():
    """RD-136 really does return three lists from one call."""
    got = resolve("cloud_resource", [
        extract(result(volumes=[{"volume_id": "vol-1"}]), VOLUMES, tool_ok=True),
        extract(result(eips=[{"alloc_id": "eipalloc-1"}]), EIPS, tool_ok=True),
        extract(result(instances=[{"instance_id": "i-1"}]), INSTANCES, tool_ok=True),
    ])

    assert got.coverage == "COMPLETE"
    assert len(got.subject_ids) == 3
    assert got.gaps == ()


def test_one_failing_source_degrades_the_whole_kind_to_partial():
    """**The failure that would otherwise reap what nobody looked at.**

    EIP enumeration throws; volumes and instances succeed. The run really did
    examine those volumes and instances, so their coverage is real and is kept.
    But claiming COMPLETE coverage of ``cloud_resource`` over two of three
    sources would reap every EIP finding on evidence that never looked at an
    EIP. PARTIAL keeps the union and never reaps.
    """
    got = resolve("cloud_resource", [
        extract(result(volumes=[{"volume_id": "vol-1"}]), VOLUMES, tool_ok=True),
        extract("DescribeAddresses threw", EIPS, tool_ok=False),
        extract(result(instances=[{"instance_id": "i-1"}]), INSTANCES, tool_ok=True),
    ])

    assert got.coverage == "PARTIAL"
    assert got.subject_ids == ("me-south-1/i-1", "me-south-1/vol-1")
    assert len(got.gaps) == 1
    assert "TOOL_FAILED" in got.gaps[0]


def test_an_unreadable_source_also_degrades_the_kind():
    """A changed output shape is a gap too, and a louder one."""
    got = resolve("cloud_resource", [
        extract(result(volumes=[{"volume_id": "vol-1"}]), VOLUMES, tool_ok=True),
        extract(result(eips=[{"AllocationId": "eipalloc-1"}]), EIPS, tool_ok=True),
    ])

    assert got.coverage == "PARTIAL"
    assert "UNREADABLE" in got.gaps[0]


def test_a_genuinely_empty_source_is_not_a_gap():
    """No elastic IPs is full coverage of the elastic IPs there are."""
    got = resolve("cloud_resource", [
        extract(result(volumes=[{"volume_id": "vol-1"}]), VOLUMES, tool_ok=True),
        extract(result(eips=[]), EIPS, tool_ok=True),
    ])

    assert got.coverage == "COMPLETE"
    assert got.gaps == ()


def test_every_source_failing_skips_the_kind_entirely():
    got = resolve("cloud_resource", [
        extract("threw", VOLUMES, tool_ok=False),
        extract("threw", EIPS, tool_ok=False),
    ])

    assert got.coverage == "SKIPPED"
    assert got.subject_ids == ()


def test_one_unverified_source_makes_the_union_unverified():
    """A missing count could be anywhere in the union.

    Verification is not per-source once the ids are merged: if one list's total
    is unknown, the union's total is unknown, and calling it verified would
    claim a check that was never made.
    """
    verified = SubjectSource("cloud_resource", "volumes", "{region}/{volume_id}",
                             total_field="total_in_region")
    unverified = SubjectSource("cloud_resource", "eips", "{region}/{alloc_id}")

    got = resolve("cloud_resource", [
        extract(result(volumes=[{"volume_id": "vol-1"}], total_in_region=1),
                verified, tool_ok=True),
        extract(result(eips=[{"alloc_id": "eipalloc-1"}]), unverified, tool_ok=True),
    ])

    assert got.coverage == "COMPLETE"
    assert got.source_verified is False


def test_the_union_deduplicates_across_sources():
    """A resource reachable through two listings is one subject."""
    got = resolve("cloud_resource", [
        extract(result(volumes=[{"volume_id": "vol-1"}]), VOLUMES, tool_ok=True),
        extract(result(volumes=[{"volume_id": "vol-1"}]), VOLUMES, tool_ok=True),
    ])

    assert got.subject_ids == ("me-south-1/vol-1",)
