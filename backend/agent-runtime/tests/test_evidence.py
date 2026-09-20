"""The evidence ledger and its two checks — one exact, one heuristic."""

from __future__ import annotations

import pytest

from agent_runtime.app import evidence
from agent_runtime.app.reduce import _compact
from agent_runtime.app.state import AgentState, Message, Phase, ToolResultWire


def state_with(*results: ToolResultWire) -> AgentState:
    state = AgentState(agent_ref="x", input="check the host")
    state.messages.append(
        Message(
            role="assistant",
            tool_calls=[
                {"id": result.call_id, "name": "workflow_3", "arguments": {}} for result in results
            ],
        )
    )
    state.messages.append(Message(role="tool_results", tool_results=list(results)))
    return state


def test_results_without_a_step_row_never_enter_the_ledger():
    """A citation that looks checkable and is not is worse than no citation."""
    state = state_with(
        ToolResultWire(call_id="a", evidence_id=7, content="disk 94%"),
        ToolResultWire(call_id="b", evidence_id=None, content="something happened"),
    )

    evidence.record(state, state.messages[-1].tool_results, Phase.GATHER)

    assert state.cited() == {7}


def test_failures_are_recorded_so_they_can_be_cited():
    """"The check failed" is an observation. A host you cannot reach is the finding."""
    state = state_with(ToolResultWire(call_id="a", ok=False, evidence_id=9, content="ssh timeout"))

    entries = evidence.record(state, state.messages[-1].tool_results, Phase.GATHER)

    assert [entry.ok for entry in entries] == [False]
    assert "FAILED" in evidence.render(state)


def test_the_same_result_is_not_filed_twice():
    """Resume paths can re-present results; the ledger must stay idempotent."""
    state = state_with(ToolResultWire(call_id="a", evidence_id=7, content="x"))
    results = state.messages[-1].tool_results

    evidence.record(state, results, Phase.GATHER)
    evidence.record(state, results, Phase.GATHER)

    assert len(state.ledger) == 1


@pytest.mark.parametrize(
    "text",
    [
        "/var is at 94%.",
        "Load average is 12.4 across the run.",
        "The host is at 3.2GB used.",
        "| /var | 94% | critical |",
        "- /home is at 84% of capacity",
        "10.0.0.4 was unreachable.",
    ],
)
def test_claims_about_the_estate_need_a_citation(text):
    assert evidence.uncited_claims(text) == [text.lstrip("- ")]


@pytest.mark.parametrize(
    "text",
    [
        "/var is at 94% [e:12].",
        "Swap was not checked, because IncludeSwap was false.",
        "I recommend expanding the volume.",
        "The cause could not be determined from this data.",
        "## Disk",
        "```\n/var 94%\n```",
        "| --- | --- |",
        # Caught by the RD-079 golden case: the auditor was flagging the exact
        # sentence the agent's own instructions ask it to write. Being told to
        # cite this pushes the model to delete the caveat or fake a citation,
        # and both are worse than the caveat.
        "The check does not break /var usage down by directory, so the cause needs a follow-up.",
        "Swap was not collected, so its 0% figure cannot be confirmed.",
        "The 94% figure could not be attributed to a single directory.",
        "This needs further investigation on the 10.0.0.4 host.",
    ],
)
def test_honest_and_non_factual_lines_are_left_alone(text):
    """Hedges, recommendations and headings need no evidence.

    Demanding it would push the model back towards asserting something, which
    is the failure this whole mechanism exists to prevent.
    """
    assert evidence.uncited_claims(text) == []


def test_a_fabricated_id_is_caught_exactly():
    result = evidence.audit("Disk is fine [e:99] and /tmp is at 12% [e:4].", allowed={4})

    assert result.unknown == [99]
    assert result.citations == [99, 4]
    assert not result.clean


def test_a_fully_cited_report_is_clean():
    result = evidence.audit("/var is at 94% [e:4] against an 85% warning [e:4].", allowed={4})

    assert result.clean
    assert result.citations == [4]


def test_the_banner_names_what_it_could_not_support():
    result = evidence.audit("/var is at 94%. Swap is exhausted [e:88].", allowed={1})

    banner = evidence.banner(result)

    assert "UNVERIFIED" in banner
    assert "[e:88]" in banner
    assert "/var is at 94%." in banner


# ------------------------------------------------- Factor 9: compaction ---


def test_a_huge_result_keeps_its_head_and_tail():
    """The interesting parts of a log are the command and the failure."""
    state = AgentState(agent_ref="x", input="y")
    content = "START-OF-LOG\n" + ("noise\n" * 5000) + "END-OF-LOG: permission denied"

    compacted = _compact(state, ToolResultWire(call_id="a", ok=False, content=content))

    assert "START-OF-LOG" in compacted
    assert "END-OF-LOG: permission denied" in compacted
    assert __import__('agent_runtime.app.config', fromlist=['x']).ELISION_SIGNATURE in compacted
    assert len(compacted) < len(content) / 4


def test_a_repeated_failure_says_so():
    """Nothing else in the context distinguishes attempt three from attempt one."""
    state = AgentState(agent_ref="x", input="y")
    error = "ssh: connect to host app-prod-01 port 22: Connection timed out"
    state.messages.append(
        Message(
            role="tool_results",
            tool_results=[ToolResultWire(call_id="a", ok=False, content=error)],
        )
    )

    compacted = _compact(state, ToolResultWire(call_id="b", ok=False, content=error))

    assert "occurred 2 times" in compacted
    assert "Repeating the call will not change it" in compacted


def test_a_successful_result_is_never_annotated():
    state = AgentState(agent_ref="x", input="y")

    compacted = _compact(state, ToolResultWire(call_id="a", ok=True, content="all good"))

    assert compacted == "all good"


def test_errors_differing_only_by_timestamp_count_as_the_same_one():
    """Whole-string comparison would call every retry a new problem."""
    state = AgentState(agent_ref="x", input="y")
    state.messages.append(
        Message(
            role="tool_results",
            tool_results=[
                ToolResultWire(call_id="a", ok=False, content="Connection refused\nat 10:04:01")
            ],
        )
    )

    compacted = _compact(
        state, ToolResultWire(call_id="b", ok=False, content="Connection refused\nat 10:09:47")
    )

    assert "occurred 2 times" in compacted


def test_a_comma_negated_hedge_is_not_flagged_as_an_uncited_claim():
    """The heuristic must not penalise the hedge the personas ask for.

    Found by the activity correlator's golden case: its persona instructs it to
    "report proximity as a sequence and explicitly not as a cause", and the
    auditor then flagged exactly that sentence — because "shows" makes it
    claim-shaped and the negation uses a comma rather than "is not".

    An agent trained by its own evidence rule to stop hedging is the failure
    this module exists to prevent, so the exemption covers both constructions.
    """
    hedge = (
        "That is a sequence, not a cause - this record shows what our automation did, "
        "not what patching changed on the host."
    )
    assert evidence.uncited_claims(hedge) == []


def test_the_exemption_does_not_swallow_an_ordinary_figure():
    """The other direction, so the clause above cannot quietly disable the rule."""
    assert evidence.uncited_claims("The disk shows 94% used on /var.")
