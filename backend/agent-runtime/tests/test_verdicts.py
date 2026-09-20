"""Findings as verdicts something downstream can deduplicate.

Every agent here already reached structured conclusions — ``HYPOTHESIZE``
emits them with a severity and the evidence they rest on — and every one was
discarded. Only the prose reached the operator, which is adequate for a run
somebody watched and useless for one on a schedule: a nightly agent with no
memory re-files the same finding every night and nobody can dismiss anything,
because there is no thing to dismiss.

What is pinned here is the part that makes a finding addressable, and the part
that admits when it is not:

* the same problem found twice produces the **same key**;
* two different problems from one run do **not** collide;
* a finding with no subject is marked ``stable_key: false`` rather than being
  given a key hashed from prose, which would look stable, dedupe nothing, and
  re-file every night;
* the requested autonomy is derived from the tools the agent HELD, never from
  anything it said about itself.
"""

from __future__ import annotations

from agent_runtime.app import verdicts
from agent_runtime.app.state import AgentState, Evidence, Finding, Phase, PlannedAction

WINDOW = "2026-09-19"


def state_with(*findings: Finding, ledger=(), planned=(), uncited=()) -> AgentState:
    state = AgentState(agent_ref="aws.idle_resource_reclaimer", input="clean up")
    state.findings = list(findings)
    state.ledger = list(ledger)
    state.planned = list(planned)
    state.uncited_claims = list(uncited)
    return state


def finding(**overrides) -> Finding:
    base = {
        "summary": "vol-0a1b2c3d4e5f67890 has been unattached for 412 days.",
        "severity": "warning",
        "cites": [],
        "subject_kind": "cloud_resource",
        "subject_id": "vol-0a1b2c3d4e5f67890",
        "finding_type": "idle_resource",
    }
    base.update(overrides)
    return Finding(**base)


def emit(state: AgentState, **overrides):
    args = {
        "agent_ref": "aws.idle_resource_reclaimer",
        "agent_version": "1.0.0",
        "run_id": 77,
        "tenant_id": "acme",
        "window": WINDOW,
        "has_mutating": True,
    }
    args.update(overrides)
    return verdicts.verdicts(state, **args)


# ------------------------------------------------------ idempotency ---


def test_the_same_problem_found_twice_produces_the_same_key():
    """The whole point. Tomorrow's run must recognise today's finding."""
    first = emit(state_with(finding()))[0]
    # A later run words it differently and grades it more seriously; it is the
    # same volume with the same problem.
    second = emit(state_with(finding(
        summary="Volume vol-0a1b2c3d4e5f67890 is still idle and now 413 days old.",
        severity="critical",
    )))[0]

    assert first["idempotency_key"] == second["idempotency_key"]
    assert first["stable_key"] is True


def test_two_different_subjects_do_not_collide():
    pair = emit(state_with(
        finding(),
        finding(subject_id="vol-0b2c3d4e5f6789012"),
    ))
    assert pair[0]["idempotency_key"] != pair[1]["idempotency_key"]


def test_two_different_finding_types_on_one_subject_do_not_collide():
    """A volume can be both idle and unencrypted, and they are two findings."""
    pair = emit(state_with(
        finding(),
        finding(finding_type="unencrypted_volume"),
    ))
    assert pair[0]["idempotency_key"] != pair[1]["idempotency_key"]


def test_the_same_finding_in_a_later_window_is_a_new_key():
    """Windows are part of the key, so a recurrence is visible as a recurrence."""
    today = emit(state_with(finding()))[0]
    tomorrow = emit(state_with(finding()), window="2026-09-20")[0]
    assert today["idempotency_key"] != tomorrow["idempotency_key"]


def test_a_finding_with_no_subject_is_marked_unstable_rather_than_given_a_fake_key():
    """Hashing prose manufactures a key that dedupes nothing.

    It would look stable, survive review, and quietly re-file every night. The
    honest answer is to say the key cannot be trusted and let the caller decide
    whether to store it.
    """
    verdict = emit(state_with(finding(subject_kind="", subject_id="", finding_type="")))[0]

    assert verdict["stable_key"] is False
    assert verdict["idempotency_key"], "a key is still emitted, it is just not trusted"
    assert verdict["subject"]["kind"] == "unknown"
    assert verdict["finding"]["type"] == "unclassified"


def test_a_subject_kind_without_an_id_is_still_unstable():
    """Naming the KIND of thing is not naming the thing."""
    verdict = emit(state_with(finding(subject_id="", finding_type="idle_resource")))[0]
    assert verdict["stable_key"] is False
    # The kind it did give is reported rather than overwritten.
    assert verdict["subject"]["kind"] == "cloud_resource"


def test_an_unstable_key_still_collapses_a_replay_of_the_same_run():
    """The one guarantee prose can honestly make."""
    unsubjected = finding(subject_kind="", subject_id="", finding_type="")
    assert (
        emit(state_with(unsubjected))[0]["idempotency_key"]
        == emit(state_with(unsubjected))[0]["idempotency_key"]
    )


# --------------------------------------------------------- autonomy ---


def test_an_agent_with_no_mutating_tool_can_only_ever_ask_to_suggest():
    """Derived from what it HELD, not from what it says.

    A tier read off the agent's own claim is the first thing that would mislead
    a policy engine reading this record.
    """
    verdict = emit(state_with(finding()), has_mutating=False)[0]

    assert verdict["autonomy"]["requested_tier"] == verdicts.SUGGEST
    assert verdict["autonomy"]["tier_source"] == "no_mutating_tool"


def test_a_gated_plan_asks_for_approval_not_for_unattended_action():
    planned = PlannedAction(tool="workflow_52", intent="delete three volumes")
    verdict = emit(state_with(finding(), planned=[planned]))[0]

    assert verdict["autonomy"]["requested_tier"] == verdicts.ACT_WITH_APPROVAL
    assert "put to a human" in verdict["autonomy"]["detail"]


def test_holding_a_destructive_tool_and_proposing_nothing_is_still_only_suggest():
    verdict = emit(state_with(finding()))[0]
    assert verdict["autonomy"]["requested_tier"] == verdicts.SUGGEST
    assert verdict["autonomy"]["tier_source"] == "no_action_proposed"


# --------------------------------------------------------- evidence ---


def ledger_entry(evidence_id: int, ok: bool = True) -> Evidence:
    return Evidence(
        evidence_id=evidence_id, tool="workflow_51", ok=ok,
        excerpt="UNATTACHED_VOLUME volume_id=vol-0a1b2c3d4e5f67890 age_days=412",
        digest="abc", phase=Phase.GATHER,
    )


def test_evidence_travels_with_the_verdict_so_it_survives_the_run():
    state = state_with(finding(cites=[101]), ledger=[ledger_entry(101)])
    verdict = emit(state)[0]

    assert len(verdict["evidence"]) == 1
    assert verdict["evidence"][0]["source"]["step_id"] == 101
    assert "vol-0a1b2c3d4e5f67890" in verdict["evidence"][0]["value"]


def test_a_citation_the_run_never_issued_is_dropped_not_carried():
    """Re-checked here because a stored verdict outlives the ledger it cites."""
    state = state_with(finding(cites=[101, 999]), ledger=[ledger_entry(101)])
    assert [e["source"]["step_id"] for e in emit(state)[0]["evidence"]] == [101]


def test_a_failed_collection_is_carried_as_evidence_and_marked_failed():
    """'The check failed' is an observation, and a finding may rest on it."""
    state = state_with(finding(cites=[101]), ledger=[ledger_entry(101, ok=False)])
    assert emit(state)[0]["evidence"][0]["ok"] is False


# --------------------------------------------------------- fallback ---


def test_an_unsubstantiated_report_marks_every_verdict_it_emitted():
    """Not only in the prose, which nothing downstream parses."""
    state = state_with(finding(), uncited=["disk was at 94%"])
    verdict = emit(state)[0]

    assert verdict["fallback"]["is_fallback"] is True
    assert verdict["fallback"]["reason"] == "partial_data"


def test_a_clean_report_is_not_a_fallback():
    assert emit(state_with(finding()))[0]["fallback"]["is_fallback"] is False


def test_severity_unknown_is_not_promoted_to_the_middle_of_the_scale():
    """An ungraded finding must not sort into the middle of a backlog."""
    assert emit(state_with(finding(severity="unknown")))[0]["finding"]["severity"] == "info"
    assert emit(state_with(finding(severity="critical")))[0]["finding"]["severity"] == "critical"


def test_a_run_with_several_conclusions_emits_several_verdicts():
    """One record per run forces a customer to dismiss a whole report to
    silence the single line in it they have already dealt with."""
    emitted = emit(state_with(finding(), finding(subject_id="vol-0b2"), finding(subject_id="vol-0c3")))
    assert len(emitted) == 3
    assert [v["ordinal"] for v in emitted] == [0, 1, 2]
