"""Extraction runs on the RAW tool result, proven rather than asserted.

``reduce._observe`` is called before ``reduce._absorb``, so the compacted copies
do not exist yet at the point subjects are read. That ordering IS the guarantee —
``extraction.extract`` also refuses content carrying the elision signature, but
that guard is a backstop for somebody moving the call later and **should never
fire in a correct build**.

The test below is the one that would catch a reorder. It feeds a result larger
than ``output_limit`` with subjects buried in the middle — exactly where
``_compact`` elides — and asserts they were seen anyway.
"""

from __future__ import annotations

import json

from agent_runtime.app import reduce as reduce_module
from agent_runtime.app.config import ELISION_SIGNATURE, settings
from agent_runtime.app.state import (
    AgentDescriptor,
    ReduceRequest,
    ToolResultsEvent,
    ToolResultWire,
    ToolSpecWire,
    Message,
    ToolCallWire,
)

REF = "aws.idle_resource_reclaimer"
TOOL_NAME = "workflow_501"
INVENTORY_REF = "RD-136-idle-resource-inventory"


def inventory(volumes: int) -> str:
    """The automation's human report followed by its JSON trailer.

    Rows carry the attributes RD-136 really emits, because the SIZE of the
    document is the whole point of the ordering test below. A bare list of ids
    is only ~28 characters each and 1,200 of them fit inside ``output_limit``
    comfortably — which would have made that test pass for the wrong reason.
    A real row runs 120-200 characters, and that is what pushes a normal estate
    over the limit.
    """
    document = {
        "region": "me-south-1",
        "unattached_volumes": [
            {
                "volume_id": f"vol-{n:012d}",
                "size_gb": 500,
                "type": "gp3",
                "az": "me-south-1a",
                "age_days": 240,
                "est_monthly_usd_list_price": 40.0,
                "tags": {"owner": "platform", "cost-centre": "intertec-ops"},
            }
            for n in range(volumes)
        ],
        "unassociated_eips": [{"allocation_id": "eipalloc-1"}],
        "stopped_instances": [{"instance_id": "i-1"}],
    }
    return "IDLE RESOURCE INVENTORY\nregion=me-south-1\n\nJSON " + json.dumps(document)


def request_with(content: str, *, ok: bool = True) -> tuple[ReduceRequest, object]:
    state = {
        "version": 1,
        "agent_ref": REF,
        "input": "reclaim idle volumes",
        "messages": [
            Message(role="assistant",
                    tool_calls=[ToolCallWire(id="call-1", name=TOOL_NAME, arguments={})])
            .model_dump()
        ],
    }
    request = ReduceRequest(
        agent=AgentDescriptor(ref=REF, version="1.0.0", model="gpt-4o", vendor="OPENAI"),
        tools=[ToolSpecWire(name=TOOL_NAME, description="inventory", ref=INVENTORY_REF)],
        state=state,
        event=ToolResultsEvent(
            results=[ToolResultWire(call_id="call-1", ok=ok, content=content)]
        ),
    )
    return request, state


def observed(content: str, *, ok: bool = True):
    request, _ = request_with(content, ok=ok)
    return reduce_module._apply(request, REF).extractions


# ------------------------------------------------------------- the wiring ---


def test_declared_sources_are_read_from_a_tool_result():
    records = observed(inventory(2))

    kinds = {r["subject_kind"] for r in records}
    assert kinds == {"cloud_resource"}
    assert len(records) == 3  # volumes, eips, stopped instances

    volumes = next(r for r in records if r["subject_ids"]
                   and r["subject_ids"][0].endswith("vol-000000000000"))
    assert volumes["subject_ids"] == [
        "me-south-1/vol-000000000000", "me-south-1/vol-000000000001"]


def test_a_tool_with_no_declaration_contributes_nothing():
    """The safe default: an undeclared tool narrows the scope, never widens it."""
    request, _ = request_with(inventory(2))
    request.tools[0].ref = "RD-142-unused-resource-cleanup"  # declared, no subjects

    assert reduce_module._apply(request, REF).extractions == []


def test_a_tool_whose_ref_never_arrived_contributes_nothing():
    """Agents rolled out before refs were carried still run, and enumerate nothing."""
    request, _ = request_with(inventory(2))
    request.tools[0].ref = None

    assert reduce_module._apply(request, REF).extractions == []


# ------------------------------------------------------ THE ordering proof ---


def test_subjects_in_the_elided_middle_are_still_seen():
    """**The test that catches a reorder.**

    ``_compact`` keeps the head and tail of an oversized result and removes the
    middle. A result padded past ``output_limit`` puts the JSON trailer — and
    every subject in it — squarely in what would be removed.

    If extraction ever runs after compaction, this fails: the trailer is gone,
    the document is unparseable, and the outcome becomes UNREADABLE. That is the
    loud failure the guard was built for, and this asserts the ordering that
    means the guard never has to fire.
    """
    content = inventory(1200)
    assert len(content) > settings().output_limit, (
        "the premise: a realistic 1200-volume inventory exceeds the compaction limit"
    )

    records = observed(content)

    assert [r["outcome"] for r in records] == ["ENUMERATED"] * 3
    volumes = next(r for r in records if len(r["subject_ids"]) > 1)
    assert len(volumes["subject_ids"]) == 1200
    assert "me-south-1/vol-000000000600" in volumes["subject_ids"]


def test_the_compacted_copy_really_would_have_lost_them():
    """The premise of the test above, pinned so it cannot quietly stop testing.

    If ``output_limit`` were raised past a realistic inventory, the previous
    test would pass for the wrong reason — nothing would have been elided at
    all, and it would be asserting that extraction can read an intact document.
    """
    from agent_runtime.app.state import AgentState

    content = inventory(1200)

    compacted = reduce_module._compact(
        AgentState(agent_ref=REF, input="x"),
        ToolResultWire(call_id="call-1", ok=True, content=content),
    )

    assert ELISION_SIGNATURE in compacted
    # The middle of the volume list is what goes, and with it any chance of
    # parsing the document at all — an elided JSON body is not JSON.
    assert "vol-000000000600" not in compacted


# ------------------------------------------------------- failure carries on ---


def test_a_failed_tool_is_recorded_as_a_gap_not_as_nothing():
    """So the kind degrades to PARTIAL rather than claiming COMPLETE."""
    records = observed("DescribeVolumes threw", ok=False)

    assert {r["outcome"] for r in records} == {"TOOL_FAILED"}
    assert all(r["subject_ids"] == [] for r in records)


# ----------------------------------------------- what the response carries ---


def test_the_declaration_is_static_and_not_built_from_results_so_far():
    """**The bug this shape exists to prevent.**

    ``aws.public_exposure_auditor`` runs the S3 audit, then security groups,
    then IAM. A declaration built from results seen so far would name only
    ``cloud_resource`` after the first two calls; the completion would name
    ``cloud_resource`` AND ``principal``; and ``RunScope.checkNarrows`` rejects a
    completion that introduces a kind the run never set out to cover. The run
    would fail, and the declaration would have been the thing that was wrong.

    Read from the agent's own tool declarations it is knowable at run start and
    does not move.
    """
    from agent_runtime import agents

    spec = agents.REGISTRY[REF].declared_subject_kinds()

    assert spec == ("cloud_resource",)
    # And it does not depend on anything having run.
    assert agents.REGISTRY[REF].declared_subject_kinds() == spec


# ------------------------------------------------ the rolled-out agent ---


RCA_REF = "aws.incident_rca_analyst"


def alarm_audit(alarms: int) -> str:
    """RD-210's real output shape: a human report then a JSON trailer."""
    document = {
        "region": "us-east-1",
        "window_start": "2026-09-20T10:00:00",
        "alarms": [
            {"alarm": f"cpu-high-{n}", "state": "OK", "resource": f"i-{n:04d}",
             "metric": "AWS/EC2/CPUUtilization", "minutes_in_state": 4000}
            for n in range(alarms)
        ],
        "clusters": [],
    }
    return "CLOUDWATCH ALARM STATE AUDIT\nregion=us-east-1\n\nJSON " + json.dumps(document)


def test_the_rca_analyst_enumerates_alarms_and_not_cloudtrail_events():
    """The agent that is actually rolled out, with the subject criterion applied.

    Alarms persist and can be re-examined next run, so they are subjects.
    CloudTrail events happened — nothing persists to be found again, and a
    coverage claim over them would mean nothing because absence next run is the
    normal outcome. RD-211 is therefore left undeclared on purpose.
    """
    from agent_runtime import agents

    spec = agents.REGISTRY[RCA_REF]
    assert spec.declared_subject_kinds() == ("alert_rule",)

    by_ref = {t.ref: t for t in spec.manifest.tools}
    assert len(by_ref["RD-210-cloudwatch-alarm-state-audit"].subjects) == 1
    assert by_ref["RD-211-cloudtrail-change-timeline"].subjects == ()


def test_alarm_ids_are_region_composed():
    """Two regions can each hold a "cpu-high"; they are different alarms."""
    from agent_runtime.app.extraction import Outcome, extract
    from agent_runtime import agents

    source = next(
        s for t in agents.REGISTRY[RCA_REF].manifest.tools for s in t.subjects
    )
    got = extract(alarm_audit(3), source, tool_ok=True)

    assert got.outcome is Outcome.ENUMERATED
    assert got.subject_ids == (
        "us-east-1/cpu-high-0", "us-east-1/cpu-high-1", "us-east-1/cpu-high-2")
    assert got.source_verified is False, "RD-210 reports no source total (sweep)"
