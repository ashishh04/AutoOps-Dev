"""End-to-end reducer behaviour: the contract Java depends on."""

from __future__ import annotations

import pytest

from agent_runtime.app.reduce import reduce
from agent_runtime.app.state import (
    AgentDescriptor,
    Directive,
    Phase,
    ReduceRequest,
    StartEvent,
    ToolResultsEvent,
    ToolResultWire,
    ToolSpecWire,
    Vendor,
)
from agent_runtime import agents
from agent_runtime.agents.spec import AgentSpec, Manifest, ToolRef
from agent_runtime.app.extraction import SubjectSource
from agent_runtime.graph import kit
from agent_runtime.graph.phases import HypothesisOut, TriageOut
from tests import fakes

#: No SHIPPED agent uses the phase kit any more — the provider-authored ones
#: were withdrawn. The kit is still runtime code, so it is exercised through an
#: agent registered only for these tests. That keeps the coverage honest about
#: what it covers: the kit, not a product.
PHASED_REF = "test.phased"
PHASES = (Phase.TRIAGE, Phase.GATHER, Phase.HYPOTHESIZE, Phase.REPORT)


@pytest.fixture(autouse=True)
def phased_agent(monkeypatch):
    spec = AgentSpec(
        manifest=Manifest(
            ref=PHASED_REF, version="1.0.0", name="Phased test agent",
            description="Exercises the phase kit.", domain="Test",
            model="claude-sonnet-5",
            tools=[
                ToolRef(
                    "WORKFLOW", "RD-136-idle-resource-inventory", mutating=False,
                    subjects=(
                        SubjectSource("cloud_resource", "unattached_volumes",
                                      "{region}/{volume_id}"),
                    ),
                ),
            ],
        ),
        persona="You inspect one host and report what you find.",
        build_graph=lambda: kit.build(list(PHASES)),
        phases=PHASES,
    )
    monkeypatch.setitem(agents.REGISTRY, PHASED_REF, spec)
    return spec

HEALTH_CHECK = ToolSpecWire(
    name="workflow_3",
    description="Run the workflow \"Linux Server Health Check\".",
    input_schema={"type": "object", "properties": {"TargetHost": {"type": "string"}}},
    mutating=False,
)

DESTRUCTIVE = ToolSpecWire(
    name="job_9",
    description="Run the automation job \"Purge log archives\".",
    input_schema={"type": "object", "properties": {}},
    mutating=True,
)


def descriptor(ref: str = PHASED_REF) -> AgentDescriptor:
    return AgentDescriptor(
        ref=ref,
        version="1.0.0",
        model="claude-sonnet-5",
        vendor=Vendor.ANTHROPIC,
        credentials={"apiKey": "test-key"},
    )


def start(tools: list[ToolSpecWire], text: str = "Check app-prod-01.") -> ReduceRequest:
    return ReduceRequest(
        agent=descriptor(),
        tools=tools,
        state=None,
        event=StartEvent(input=text),
        run_id=41,
        tenant_id="t-1",
    )


def test_start_gathers_then_parks_for_java_to_dispatch(monkeypatch):
    """A run reaches its first boundary holding tool calls, and stops there.

    This is the whole reducer contract: the runtime decides, Java executes.
    Nothing here may dispatch a tool itself.
    """
    model = fakes.install(
        monkeypatch,
        fakes.ScriptedModel(
            script=[
                TriageOut(restated="Health check on app-prod-01.", can_proceed=True),
                fakes.reply(
                    "Collecting.",
                    [{"name": "workflow_3", "args": {"TargetHost": "app-prod-01"}, "id": "call-1"}],
                ),
            ]
        ),
    )

    response = reduce(start([HEALTH_CHECK]))

    assert response.directive is Directive.CALL_TOOLS
    assert [call.name for call in response.tool_calls] == ["workflow_3"]
    assert response.tool_calls[0].arguments == {"TargetHost": "app-prod-01"}
    assert response.phase is Phase.GATHER
    assert model.phases == ["TRIAGE", "GATHER"]

    # Two model calls in ONE reduce — triage straight into gather. Both must be
    # reported, and this is why: agent-service adds `model_calls` to the run's
    # step budget rather than incrementing by one. A budget that counted reduces
    # would let a phased agent make several times its allowance.
    assert response.model_calls == 2

    # Tokens are reported even mid-run; they are already billable. TRIAGE uses
    # structured output, whose tokens the naive `with_structured_output(...)
    # .invoke(...)` throws away along with the AIMessage — 20 of the 25 below
    # come from that call, and silently undercounting a billing surface is
    # worse than not reporting at all.
    assert response.usage.completion_tokens == 25
    assert response.usage.prompt_tokens == 50


def test_gather_is_never_offered_a_mutating_tool(monkeypatch):
    """The narrowing is structural: the tool is absent from the REQUEST.

    Asserting that it was never *called* would pass even if the model simply
    chose not to. What matters is that it was never offered.
    """
    model = fakes.install(
        monkeypatch,
        fakes.ScriptedModel(
            script=[
                TriageOut(restated="Check the host.", can_proceed=True),
                fakes.reply("Collecting.", [{"name": "workflow_3", "args": {}, "id": "c1"}]),
            ]
        ),
    )

    reduce(start([HEALTH_CHECK, DESTRUCTIVE]))

    assert len(model.bound) == 1, "GATHER should bind tools exactly once"
    assert model.bound[0].names == ["workflow_3"]
    assert "job_9" not in model.bound[0].names


def test_full_run_finishes_with_a_cited_report(monkeypatch):
    """Results come back, the ledger fills, and the report cites real ids."""
    fakes.install(
        monkeypatch,
        fakes.ScriptedModel(
            script=[
                TriageOut(restated="Check the host.", can_proceed=True),
                fakes.reply("Collecting.", [{"name": "workflow_3", "args": {}, "id": "c1"}]),
            ]
        ),
    )
    first = reduce(start([HEALTH_CHECK]))

    model = fakes.install(
        monkeypatch,
        fakes.ScriptedModel(
            script=[
                fakes.reply("I have what I need."),
                HypothesisOut(
                    findings=[
                        {"summary": "/var is at 94%.", "severity": "critical", "cites": [77]}
                    ],
                    need_more=False,
                ),
                fakes.reply("/var is at 94% against an 85% warning [e:77]."),
            ]
        ),
    )

    response = reduce(
        ReduceRequest(
            agent=descriptor(),
            tools=[HEALTH_CHECK],
            state=first.state,
            event=ToolResultsEvent(
                results=[
                    ToolResultWire(
                        call_id="c1", ok=True, evidence_id=77, content="/var 94% used"
                    )
                ]
            ),
            run_id=41,
        )
    )

    assert response.directive is Directive.FINISH
    assert response.phase is Phase.DONE
    assert response.citations == [77]
    assert response.uncited_claims == []
    assert "UNVERIFIED" not in (response.output or "")
    assert model.phases == ["GATHER", "HYPOTHESIZE", "REPORT"]


def test_uncited_report_is_repaired_then_flagged(monkeypatch):
    """One retry, then the report ships with its weak lines named.

    It must NOT fail the run: a flagged report during an incident is worth more
    than no report, and silently deleting the claim would hide the problem.
    """
    fakes.install(
        monkeypatch,
        fakes.ScriptedModel(
            script=[
                TriageOut(restated="Check it.", can_proceed=True),
                fakes.reply("Collecting.", [{"name": "workflow_3", "args": {}, "id": "c1"}]),
            ]
        ),
    )
    first = reduce(start([HEALTH_CHECK]))

    model = fakes.install(
        monkeypatch,
        fakes.ScriptedModel(
            script=[
                fakes.reply("Done."),
                HypothesisOut(findings=[], need_more=False),
                # Both drafts assert a figure with no observation behind it.
                fakes.reply("/var is at 94% and swap is exhausted."),
                fakes.reply("/var is at 94% and swap is exhausted."),
            ]
        ),
    )

    response = reduce(
        ReduceRequest(
            agent=descriptor(),
            tools=[HEALTH_CHECK],
            state=first.state,
            event=ToolResultsEvent(
                results=[ToolResultWire(call_id="c1", ok=True, evidence_id=5, content="ok")]
            ),
        )
    )

    assert response.directive is Directive.FINISH, "a flagged report still ships"
    assert "UNVERIFIED" in response.output
    assert response.uncited_claims
    # The repair attempt happened: REPORT was entered twice.
    assert model.phases.count("REPORT") == 2


def test_fabricated_citation_is_caught(monkeypatch):
    """An id the run never issued is caught exactly, not heuristically."""
    fakes.install(
        monkeypatch,
        fakes.ScriptedModel(
            script=[
                TriageOut(restated="Check it.", can_proceed=True),
                fakes.reply("Collecting.", [{"name": "workflow_3", "args": {}, "id": "c1"}]),
            ]
        ),
    )
    first = reduce(start([HEALTH_CHECK]))

    fakes.install(
        monkeypatch,
        fakes.ScriptedModel(
            script=[
                fakes.reply("Done."),
                HypothesisOut(findings=[], need_more=False),
                fakes.reply("Disk is fine [e:999]."),
                fakes.reply("Disk is fine [e:999]."),
            ]
        ),
    )

    response = reduce(
        ReduceRequest(
            agent=descriptor(),
            tools=[HEALTH_CHECK],
            state=first.state,
            event=ToolResultsEvent(
                results=[ToolResultWire(call_id="c1", ok=True, evidence_id=5, content="ok")]
            ),
        )
    )

    assert "[e:999]" in response.output
    assert "UNVERIFIED" in response.output
    assert "[e:999]" in response.uncited_claims


def test_triage_can_refuse_a_request_the_tools_cannot_answer(monkeypatch):
    """Stopping at triage is a good outcome, and it is not a failure."""
    fakes.install(
        monkeypatch,
        fakes.ScriptedModel(
            script=[
                TriageOut(
                    restated="Restart the database.",
                    can_proceed=False,
                    reason="This agent is read-only; restarting needs the service-restart automation.",
                ),
                fakes.reply("I cannot do that: this agent only inspects."),
            ]
        ),
    )

    response = reduce(start([HEALTH_CHECK], text="Restart the database."))

    assert response.directive is Directive.FINISH
    assert response.phase is Phase.DONE


def test_unknown_agent_ref_is_refused_by_name(monkeypatch):
    """A ref this build lacks fails the run; it never resolves to something near."""
    response = reduce(
        ReduceRequest(
            agent=descriptor(ref="aws.does_not_exist"),
            tools=[],
            state=None,
            event=StartEvent(input="go"),
        )
    )

    assert response.directive is Directive.FAIL
    assert "aws.does_not_exist" in response.error
    assert "generic.single_phase" in response.error


def test_start_against_existing_state_is_refused(monkeypatch):
    """Restarting in place would discard tool calls that already executed."""
    fakes.install(
        monkeypatch,
        fakes.ScriptedModel(
            script=[
                TriageOut(restated="Check it.", can_proceed=True),
                fakes.reply("Collecting.", [{"name": "workflow_3", "args": {}, "id": "c1"}]),
            ]
        ),
    )
    first = reduce(start([HEALTH_CHECK]))

    response = reduce(
        ReduceRequest(
            agent=descriptor(),
            tools=[HEALTH_CHECK],
            state=first.state,
            event=StartEvent(input="go again"),
        )
    )

    assert response.directive is Directive.FAIL
    assert "already has saved state" in response.error


def test_stale_state_version_refuses_rather_than_guessing(monkeypatch):
    """A transcript this build cannot read stops the run instead of resuming it."""
    response = reduce(
        ReduceRequest(
            agent=descriptor(),
            tools=[HEALTH_CHECK],
            state={"version": 99, "agent_ref": PHASED_REF, "input": "x"},
            event=ToolResultsEvent(results=[]),
        )
    )

    assert response.directive is Directive.FAIL
    assert "cannot be resumed safely" in response.error


def test_reducer_is_deterministic_for_the_same_input(monkeypatch):
    """Same state, same event, same script — same directive and same phases.

    This is what makes the eval harness possible at all: replay is re-POSTing
    the input, with no fixtures and no mocking of infrastructure.
    """
    runs = []
    for _ in range(2):
        fakes.install(
            monkeypatch,
            fakes.ScriptedModel(
                script=[
                    TriageOut(restated="Check the host.", can_proceed=True),
                    fakes.reply("Collecting.", [{"name": "workflow_3", "args": {}, "id": "c1"}]),
                ]
            ),
        )
        response = reduce(start([HEALTH_CHECK]))
        runs.append((response.directive, response.phase, [c.name for c in response.tool_calls]))

    assert runs[0] == runs[1]


def test_legacy_json_agent_keeps_every_tool_and_its_own_persona(monkeypatch):
    """The compatibility path must not narrow, and must use the tenant's persona."""
    model = fakes.install(
        monkeypatch,
        fakes.ScriptedModel(script=[fakes.reply("All good, nothing to do.")]),
    )

    response = reduce(
        ReduceRequest(
            agent=AgentDescriptor(
                ref="generic.single_phase",
                model="gpt-4o",
                vendor=Vendor.OPENAI,
                credentials={"apiKey": "k"},
                instructions="You are a legacy agent with your own voice.",
            ),
            tools=[HEALTH_CHECK, DESTRUCTIVE],
            state=None,
            event=StartEvent(input="do the thing"),
        )
    )

    assert response.directive is Directive.FINISH
    assert model.bound[0].names == ["workflow_3", "job_9"], "legacy agents see every tool"
    system = model.seen[0][0].content
    assert "You are a legacy agent with your own voice." in system
    # No evidence rule on the legacy path: its prompts were never written for
    # citations, and switching one on would change its output.
    assert "EVIDENCE RULE" not in system


@pytest.mark.parametrize("vendor", [Vendor.HUAWEI])
def test_unrunnable_vendor_fails_with_a_message_naming_it(monkeypatch, vendor):
    """Huawei has a Java adapter and no LangChain one. Refuse, do not substitute."""
    response = reduce(
        ReduceRequest(
            agent=AgentDescriptor(
                ref="generic.single_phase",
                model="m",
                vendor=vendor,
                credentials={},
            ),
            tools=[],
            state=None,
            event=StartEvent(input="go"),
        )
    )

    assert response.directive is Directive.FAIL
    assert "HUAWEI" in response.error


def test_a_json_persona_agent_sends_no_ref_and_still_runs(monkeypatch):
    """The ordinary customer-built agent: no graph_ref, so `ref` is null.

    agent-service sends null for every agent whose `graph_ref` column is unset.
    Declaring `ref` required rejected those with a 422 before the registry —
    which already maps None onto the single-phase agent — ever saw them. A real
    run against a Dify-backed workflow is what surfaced it.
    """
    model = fakes.install(
        monkeypatch, fakes.ScriptedModel(script=[fakes.reply("Report delivered.")])
    )

    response = reduce(
        ReduceRequest(
            agent=AgentDescriptor(
                ref=None, model="deepseek.v3.2", vendor=Vendor.BEDROCK,
                credentials={"accessId": "a", "secret": "b", "region": "us-east-1"},
                instructions="You are a research analyst.",
            ),
            tools=[HEALTH_CHECK],
            state=None,
            event=StartEvent(input="Research agentic AI tools."),
        )
    )

    assert response.directive is Directive.FINISH
    assert response.phase is Phase.RESPOND or response.phase is Phase.DONE
    # It resolved to the compatibility agent and used the tenant's own persona.
    assert "You are a research analyst." in model.seen[0][0].content


def test_a_parked_run_declares_its_kinds_but_claims_no_coverage(monkeypatch):
    """**A field present when it should not be is the easier accident.**

    A non-terminal reduce carries ``declared_subject_kinds`` — a property of the
    agent, true from the first call — and must carry NO ``subject_scope``.

    The reason is the outage case. A run parked on its first tool has examined
    an unknown fraction of what it set out to, and publishing that fraction as
    coverage is exactly how one agent's bad night reaps a backlog. Absence here
    is the safe state, and absence is easy to lose by accident: moving the
    coverage fold above the CALL_TOOLS branch would populate it on every
    boundary and nothing would fail until something reaped.
    """
    fakes.install(
        monkeypatch,
        fakes.ScriptedModel(
            script=[
                TriageOut(restated="Health check on app-prod-01.", can_proceed=True),
                fakes.reply(
                    "Collecting.",
                    [{"name": "workflow_3", "args": {"TargetHost": "app-prod-01"},
                      "id": "call-1"}],
                ),
            ]
        ),
    )

    response = reduce(start([HEALTH_CHECK]))

    assert response.directive is Directive.CALL_TOOLS
    assert response.declared_subject_kinds == ["cloud_resource"]
    assert response.subject_scope == []
    assert response.subject_ids == {}


def test_an_agent_whose_every_tool_is_mutating_fails_instead_of_narrating(monkeypatch):
    """The failure that shipped a scratchpad to a customer.

    GATHER is read-only, so an agent granted nothing but mutating tools has a
    gathering phase that can see none of them. It routes onward, HYPOTHESIZE
    asks for data that never arrives, and REPORT — instructed to report, handed
    no observations — writes a paragraph about the tools it intended to call.

    That paragraph used to be returned as a SUCCEEDED run's deliverable. It is
    now a failure that names the cause, and the narration does not survive into
    the output.
    """
    fakes.install(
        monkeypatch,
        fakes.ScriptedModel(
            script=[
                TriageOut(restated="Check it.", can_proceed=True),
                HypothesisOut(findings=[], need_more=False),
                fakes.reply("I will execute both required automations in one turn."),
            ]
        ),
    )

    response = reduce(start([DESTRUCTIVE]))

    assert response.directive is Directive.FAIL
    assert "could not collect any evidence" in response.error
    # The cause is named precisely enough to act on without opening a trace.
    assert "job_9" in response.error
    assert '"mutating": false' in response.error
    # And the model's narration is not what the customer is handed.
    assert "I will execute both required automations" not in (response.output or "")


def test_a_run_whose_every_tool_call_was_rejected_fails_with_the_reason(monkeypatch):
    """The second shape of the same customer-facing failure.

    Here the tools ARE visible to GATHER and the model does call them — they
    are simply rejected every time, which is what happens when a model spells
    an argument in its own casing rather than the schema's. It cannot recover
    from that by retrying, because the next attempt is spelled identically, so
    the run burns its rounds and REPORT is asked to write up an investigation
    that never happened.
    """
    fakes.install(
        monkeypatch,
        fakes.ScriptedModel(
            script=[
                TriageOut(restated="Check it.", can_proceed=True),
                fakes.reply("Collecting.", [{"name": "workflow_3", "args": {}, "id": "c1"}]),
            ]
        ),
    )
    first = reduce(start([HEALTH_CHECK]))

    fakes.install(
        monkeypatch,
        fakes.ScriptedModel(
            script=[
                fakes.reply("Nothing usable came back."),
                HypothesisOut(findings=[], need_more=False),
                fakes.reply("I will collect the CloudWatch alarm state."),
            ]
        ),
    )
    response = reduce(
        ReduceRequest(
            agent=descriptor(),
            tools=[HEALTH_CHECK],
            state=first.state,
            event=ToolResultsEvent(
                results=[
                    ToolResultWire(
                        call_id="c1",
                        ok=False,
                        content="'region' is not an input this workflow accepts",
                    )
                ]
            ),
        )
    )

    assert response.directive is Directive.FAIL
    assert "all 1 of its tool calls were rejected" in response.error
    # The wall it hit is named, so nobody has to open a trace to find out.
    assert "not an input this workflow accepts" in response.error
    assert "I will collect the CloudWatch alarm state" not in (response.output or "")
