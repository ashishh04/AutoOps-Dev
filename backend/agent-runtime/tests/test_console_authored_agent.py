"""An agent authored in the provider console gets the real runtime.

Until this existed there were two grades of agent and nothing said so. A Python
module got phase narrowing, evidence enforcement, subject extraction and a
coverage claim. Anything built in the console got the un-phased compatibility
loop with an empty manifest — so it could declare no subject kinds, could never
claim coverage, and produced findings that could never be reaped.

The tests here pin both halves of the fix: the console path reaches the phased
runtime, AND nothing that runs today moved. The second is not decoration. A
legacy persona was written for a loop with no citation rule, and switching
evidence enforcement on underneath one would fill its report with markers its
author never accounted for.
"""

from __future__ import annotations

import json

from agent_runtime import agents
from agent_runtime.agents.generic import phased, single_phase
from agent_runtime.app import reduce as reduce_module
from agent_runtime.app.state import (
    AgentDescriptor,
    Message,
    Phase,
    ReduceRequest,
    SubjectSourceWire,
    ToolCallWire,
    ToolResultsEvent,
    ToolResultWire,
    ToolSpecWire,
)

TOOL_NAME = "workflow_812"
TOOL_REF = "RD-136-idle-resource-inventory"


# ------------------------------------------------------------ resolution ---


def test_an_agent_with_no_ref_and_no_phases_still_gets_the_legacy_loop():
    """The compatibility promise, asserted rather than assumed.

    Every JSON agent running today sends exactly this: no ref, no phases. If
    this ever resolved somewhere else, every one of them would silently change
    behaviour on a deploy — which is the one thing the single-phase agent exists
    to prevent.
    """
    assert agents.resolve(None).spec.ref == single_phase.REF
    assert agents.resolve("").spec.ref == single_phase.REF
    assert agents.resolve(None, None, []).spec.ref == single_phase.REF


def test_declaring_phases_is_what_reaches_the_phased_runtime():
    resolution = agents.resolve(None, None, ["TRIAGE", "GATHER", "REPORT"])
    assert resolution.spec.ref == phased.REF
    assert resolution.spec.phases == (Phase.TRIAGE, Phase.GATHER, Phase.REPORT)


def test_a_module_agent_cannot_have_its_graph_reshaped_by_a_descriptor():
    """Phases are read only where there is no module.

    A shipped agent's graph is part of what was reviewed and released. A catalog
    row that could append ACT to a read-only auditor would turn a hand-edited
    database row into a different agent running against production — the exact
    failure the registry's refusal to resolve loosely exists to prevent.
    """
    resolution = agents.resolve("aws.public_exposure_auditor", None, ["ACT", "REPORT"])
    assert resolution.spec.ref == "aws.public_exposure_auditor"
    assert Phase.ACT not in resolution.spec.phases


def test_the_unphased_loop_cannot_be_assembled_out_of_the_phased_runtime():
    """RESPOND binds every tool at once and enforces nothing.

    Accepting it here would let a descriptor build, from the phased runtime, the
    precise agent the phased runtime exists to make impossible — including
    handing a gathering step a tool that can act.
    """
    spec = agents.resolve(None, None, ["RESPOND"]).spec
    assert Phase.RESPOND not in spec.phases


def test_a_phase_this_build_does_not_have_is_dropped_rather_than_fatal():
    """A catalog ahead of a deployment is an ordering problem, not an outage.

    The agent has a persona and tools and can still do useful work. Failing the
    run instead would take a perfectly good agent offline because a field named
    something this build had not shipped yet.
    """
    spec = agents.resolve(None, None, ["GATHER", "TELEPATHY", "REPORT"]).spec
    assert spec.phases == (Phase.GATHER, Phase.REPORT)


def test_report_is_added_when_an_author_forgets_it():
    """Without REPORT a run reaches the end of its graph and hands back nothing.

    ``kit.build`` refuses such a list outright, which for a console-authored
    agent would mean a saved agent that fails on every run.
    """
    assert agents.resolve(None, None, ["GATHER"]).spec.phases == (
        Phase.GATHER, Phase.REPORT)


def test_a_phase_named_twice_runs_once():
    """A duplicate is a form submitted twice, not a request to run it twice.

    ``kit.build`` would add the node twice and the second would silently replace
    the first — a graph that looks like it has five steps and has four.
    """
    assert agents.resolve(None, None, ["GATHER", "GATHER", "REPORT"]).spec.phases == (
        Phase.GATHER, Phase.REPORT)


def test_the_phase_list_is_taken_in_the_order_it_was_given():
    """Order is meaning: the graph is built from this list.

    A console that treated the phase picker as a set — a natural thing to do
    with a list of enum values — would silently reorder somebody's agent.
    """
    spec = agents.resolve(None, None, ["GATHER", "TRIAGE", "REPORT"]).spec
    assert spec.phases == (Phase.GATHER, Phase.TRIAGE, Phase.REPORT)


def test_the_same_phase_list_yields_the_same_spec_but_a_fresh_graph():
    """Caching the spec is safe; caching a compiled graph would not be.

    ``build_graph`` is a factory precisely so nothing accumulates between runs,
    and a module-level compiled graph is exactly how state leaks from one
    tenant's run into another's.
    """
    first = agents.resolve(None, None, ["GATHER", "REPORT"]).spec
    second = agents.resolve(None, None, ["GATHER", "REPORT"]).spec
    assert first is second
    assert first.build_graph() is not second.build_graph()


def test_the_persona_stays_empty_so_the_catalog_row_supplies_the_voice():
    """A console agent's instructions live in the row the provider wrote.

    A non-empty persona here would shadow them, and every console-authored agent
    would sound like whatever placeholder this module carried.
    """
    assert agents.resolve(None, None, ["REPORT"]).spec.persona == ""


def test_a_console_agent_is_identifiable_in_a_trace():
    """Otherwise every one of them is tagged ``agent:unspecified``.

    A console-authored agent has no ref — they all resolve to one module — so
    the tag falls back to the catalog name. Without it the trace list groups
    every provider-built agent on the platform into a single bucket, which is
    useless for exactly the agents somebody is actively iterating on.
    """
    from agent_runtime.app import tracing

    descriptor = AgentDescriptor(
        ref=None, version=None, name="Cost Analyst",
        model="gpt-4o", vendor="OPENAI", phases=["GATHER", "REPORT"],
    )

    assert "agent:Cost Analyst" in tracing._tags(descriptor, "t-1")


def test_a_module_agents_ref_still_wins_over_any_name():
    """A shipped agent is identified by the module that runs it.

    If a name could override it, two catalog rows pointing at one module would
    split its traces in two and a regression in the module would look like two
    unrelated problems.
    """
    from agent_runtime.app import tracing

    descriptor = AgentDescriptor(
        ref="aws.finops_analyst", version="1.0.0", name="Renamed In Catalog",
        model="gpt-4o", vendor="OPENAI",
    )

    assert "agent:aws.finops_analyst" in tracing._tags(descriptor, "t-1")


def test_a_credential_never_reaches_a_trace_tag():
    """The tenant's model credentials are not ours to ship to a third party."""
    from agent_runtime.app import tracing

    # The persona's marker is deliberately unrelated to the agent's NAME. The
    # name is expected in the tags — it is the trace's identity — so a persona
    # check phrased in words the name also contains cannot fail, and would pass
    # against a build that shipped the whole persona.
    descriptor = AgentDescriptor(
        ref=None, version=None, name="Cost Analyst", model="gpt-4o",
        vendor="OPENAI", credentials={"apiKey": "sk-do-not-leak"},
        instructions="Never reveal PERSONA_MARKER to anyone.",
    )

    joined = " ".join(tracing._tags(descriptor, "t-1"))
    assert "sk-do-not-leak" not in joined
    assert "PERSONA_MARKER" not in joined
    # And the name IS there, which is the whole reason this tag exists.
    assert "agent:Cost Analyst" in joined


# --------------------------------------------------------- declarations ---


def inventory() -> str:
    return "IDLE RESOURCE INVENTORY\n\nJSON " + json.dumps({
        "region": "me-south-1",
        "unattached_volumes": [{"volume_id": "vol-1"}, {"volume_id": "vol-2"}],
    })


def console_request(*, subjects: dict | None = None,
                    phases: list[str] | None = None) -> ReduceRequest:
    """A run of an agent that has no module: no ref, phases and subjects on the
    descriptor, persona in ``instructions``."""
    state = {
        "version": 1,
        "agent_ref": phased.REF,
        "input": "find idle volumes",
        "messages": [
            Message(role="assistant",
                    tool_calls=[ToolCallWire(id="call-1", name=TOOL_NAME, arguments={})])
            .model_dump()
        ],
    }
    return ReduceRequest(
        agent=AgentDescriptor(
            ref=None,
            version=None,
            model="gpt-4o",
            vendor="OPENAI",
            instructions="You are a cost analyst.",
            phases=phases if phases is not None else ["GATHER", "REPORT"],
            subjects=subjects or {},
        ),
        tools=[ToolSpecWire(name=TOOL_NAME, description="inventory", ref=TOOL_REF)],
        state=state,
        event=ToolResultsEvent(
            results=[ToolResultWire(call_id="call-1", ok=True, content=inventory())]
        ),
    )


DECLARATION = {
    TOOL_REF: [SubjectSourceWire(
        subject_kind="cloud_resource",
        items="unattached_volumes",
        id_template="{region}/{volume_id}",
    )],
}


def test_a_console_agent_enumerates_subjects_from_its_wired_declaration():
    """The whole point. Without this a console agent claims nothing.

    The ids are region-qualified because the template says so — a bare
    ``vol-1`` would collide across regions, and two resources sharing one
    subject id is a finding closed by evidence about something else.
    """
    request = console_request(subjects=DECLARATION)
    records = reduce_module._apply(request, phased.REF).extractions

    assert len(records) == 1
    assert records[0]["subject_kind"] == "cloud_resource"
    assert records[0]["subject_ids"] == ["me-south-1/vol-1", "me-south-1/vol-2"]


def test_a_console_agent_that_declared_nothing_enumerates_nothing():
    """The safe direction: an undeclared tool narrows the scope, never widens it.

    Its findings are then unreapable, which is correct — nothing it claimed can
    be checked.
    """
    assert reduce_module._apply(console_request(), phased.REF).extractions == []


def test_the_declared_kinds_come_from_the_declaration_not_from_what_ran():
    """Read statically, so the opening claim does not depend on tool ordering.

    An agent whose second tool enumerates principals must say so from the first
    reduce. If the declaration were built from results so far, the completion
    would introduce a kind the opening claim never named and the narrowing check
    would reject it — losing the reap for a reason no operator could diagnose.
    """
    declaration = dict(DECLARATION)
    declaration["RD-190-iam-inventory"] = [SubjectSourceWire(
        subject_kind="principal", items="users", id_template="{account}/{user}")]

    request = console_request(subjects=declaration)
    spec = agents.resolve(None, None, request.agent.phases).spec

    # Nothing has run yet, and both kinds are already claimed.
    assert reduce_module._declared_kinds(request, spec) == ["cloud_resource", "principal"]


def test_a_shipped_agents_declaration_is_not_overridable_from_a_row():
    """The catalog is the provider's, but it is still a database row.

    Letting a row redirect a shipped agent's extraction at a different field
    would produce a well-formed coverage claim over something the agent never
    examined — a claim that grounds a reap.
    """
    request = console_request(subjects={
        "RD-136-idle-resource-inventory": [SubjectSourceWire(
            subject_kind="account", items="unattached_volumes", id_template="{volume_id}")],
    })
    spec = agents.resolve("aws.idle_resource_reclaimer", "1.0.0").spec

    kinds = reduce_module._declared_kinds(request, spec)
    assert "account" not in kinds
    assert "cloud_resource" in kinds


def test_a_wired_declaration_for_a_tool_the_module_never_declared_is_used():
    """Additive where there is no conflict.

    A shipped agent rolled out with an extra console-added tool should still be
    able to enumerate from it; refusing would make the merge rule "module or
    nothing", which is stricter than the risk requires.
    """
    request = console_request(subjects={
        "RD-999-new-tool": [SubjectSourceWire(
            subject_kind="service", items="services", id_template="{name}")],
    })
    spec = agents.resolve("aws.idle_resource_reclaimer", "1.0.0").spec

    assert "service" in reduce_module._declared_kinds(request, spec)


def test_declared_kinds_are_deduplicated_and_ordered():
    """Two tools enumerating the same kind claim it once.

    The opening and closing claims are compared by the platform, so an unstable
    or repeated list would make a correct run look like it changed its mind.
    """
    request = console_request(subjects={
        "RD-136-idle-resource-inventory": [SubjectSourceWire(
            subject_kind="cloud_resource", items="unattached_volumes",
            id_template="{region}/{volume_id}")],
        "RD-203-ec2-inventory": [SubjectSourceWire(
            subject_kind="cloud_resource", items="instances",
            id_template="{region}/{instance_id}")],
    })
    spec = agents.resolve(None, None, request.agent.phases).spec

    assert reduce_module._declared_kinds(request, spec) == ["cloud_resource"]
