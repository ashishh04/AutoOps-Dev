"""The agents this build actually ships, checked as products rather than code.

``test_gate.py`` and ``test_toolbox.py`` prove the KIT behaves — with synthetic
agents assembled in the test. This file asks the next question, which is the one
a customer cares about: are the agents we are about to publish correct?

Three classes of fault are caught here that a kit test cannot see:

* **A tool ref that resolves to nothing.** An agent's allow-list names workflows
  by a stable key that rollout matches against the customer's delivered copies.
  A typo publishes cleanly, rolls out cleanly, and produces an agent with no
  working tool. ``publish.py`` refuses that set — but only if someone runs it,
  and only against a live runtime. This fails in a second, offline.
* **A read-only tool left unmarked.** ``mutating`` defaults to TRUE on every
  layer, deliberately. The consequence is that forgetting to mark an audit
  read-only makes it invisible to GATHER, and the agent quietly reports that it
  could not collect something.
* **A persona in a manifest.** The whole reason these agents are Python modules
  is that the prompts never leave the image.
"""

from __future__ import annotations

import json
import pathlib

import pytest

from agent_runtime import agents
from agent_runtime.agents.spec import AgentSpec
from agent_runtime.app.reduce import reduce
from agent_runtime.app.state import (
    AgentDescriptor,
    Directive,
    Phase,
    ReduceRequest,
    StartEvent,
    ToolSpecWire,
    Vendor,
)
from agent_runtime.graph.phases import TriageOut
from tests import fakes

WORKFLOWS = pathlib.Path(__file__).resolve().parents[2] / "agent-service" / "workflows"

#: The compatibility agent is a runtime fallback, not a product: it has no
#: tools of its own, no persona and is never published. Every assertion about
#: what a SHIPPED agent must look like would be wrong about it.
SHIPPED = [spec for ref, spec in agents.REGISTRY.items() if ref != "generic.single_phase"]


def ids(spec: AgentSpec) -> str:
    return spec.ref


@pytest.fixture(scope="module")
def published_refs() -> set[str]:
    """Every workflow ref the catalog can actually deliver.

    A workflow's ref IS its file stem — that is what ``publish.py`` stores in
    the published definition and what ``RolloutService.resolveTools`` matches
    against the customer's copies.
    """
    return {path.stem for path in WORKFLOWS.glob("*/*.json")}


def test_the_registry_is_not_empty():
    """An empty catalog passes every other test in this file silently."""
    assert SHIPPED, "this build ships no agents"


@pytest.mark.parametrize("spec", SHIPPED, ids=ids)
def test_every_tool_ref_resolves_to_a_published_workflow(spec, published_refs):
    for tool in spec.manifest.tools:
        assert tool.ref in published_refs, (
            f"{spec.ref} names tool {tool.ref!r}, which no published workflow provides. "
            f"A delivered agent whose allow-list resolves to nothing is worse than no "
            f"agent at all. Published: {sorted(published_refs)}"
        )


@pytest.mark.parametrize("spec", SHIPPED, ids=ids)
def test_every_tool_is_a_workflow(spec):
    """Jobs are the customer's own; a provider agent cannot know their ids.

    ``RolloutService.resolveTools`` refuses a non-WORKFLOW tool outright, so a
    JOB here fails at delivery for every tenant.
    """
    for tool in spec.manifest.tools:
        assert tool.type == "WORKFLOW", f"{spec.ref} names a {tool.type} tool: {tool.ref}"


@pytest.mark.parametrize("spec", SHIPPED, ids=ids)
def test_a_tools_mutability_matches_what_its_workflow_actually_does(spec, published_refs):
    """The declaration is the only record of it, so it has to be right.

    Nothing in a workflow's own schema says whether running it changes
    anything — it is a list of steps. The author declares it, and the phase
    narrowing is built on that declaration. Here it is checked against the one
    signal the workflow does carry: a destructive automation declares the IAM
    permissions it needs, and a read-only one cannot ask for a Delete.
    """
    destructive_verbs = ("Delete", "Terminate", "Remove", "Release", "Put", "Create", "Modify")

    for tool in spec.manifest.tools:
        path = next(WORKFLOWS.glob(f"*/{tool.ref}.json"))
        document = json.loads(path.read_text(encoding="utf-8"))
        permissions = [
            permission
            for requirement in document.get("requires", [])
            for permission in requirement.get("permissions", [])
        ]
        writes = [
            permission for permission in permissions
            if permission.split(":", 1)[-1].startswith(destructive_verbs)
        ]
        if writes and not tool.mutating:
            pytest.fail(
                f"{spec.ref} marks {tool.ref} read-only, but that workflow asks for "
                f"{writes}. A phase gathering evidence would be shown a tool that writes."
            )
        if not writes and tool.mutating:
            pytest.fail(
                f"{spec.ref} marks {tool.ref} mutating, but it only asks for reads "
                f"({permissions}). GATHER will never be shown it, so the agent will "
                f"report that it could not collect something it actually can."
            )


@pytest.mark.parametrize("spec", SHIPPED, ids=ids)
def test_an_agent_that_can_change_things_declares_the_phases_that_control_it(spec):
    """PLAN/GATE/ACT are not optional decoration on a destructive agent.

    Without them the kit falls through from HYPOTHESIZE straight to REPORT and
    the mutating tool is never reachable — which is safe, and also means the
    agent silently cannot do the job it was published to do.
    """
    holds_mutating = any(tool.mutating for tool in spec.manifest.tools)
    declared = set(spec.phases)

    if holds_mutating:
        assert {Phase.PLAN, Phase.GATE, Phase.ACT} <= declared, (
            f"{spec.ref} holds a mutating tool but declares {sorted(p.value for p in declared)}"
        )
        assert spec.manifest.approval_required, (
            f"{spec.ref} can change a customer's estate and does not declare "
            f"approval_required. That flag drives the gate in core-service."
        )
        assert Phase.VERIFY in declared, (
            f"{spec.ref} changes state but never verifies it. An automation's exit "
            f"status is not evidence that anything moved."
        )
    else:
        assert not (declared & {Phase.PLAN, Phase.GATE, Phase.ACT}), (
            f"{spec.ref} declares acting phases with no mutating tool to act with"
        )
        assert not spec.manifest.approval_required


@pytest.mark.parametrize("spec", SHIPPED, ids=ids)
def test_every_agent_reports(spec):
    assert Phase.REPORT in spec.phases
    # Building is the real check: kit.build refuses a graph with no REPORT, and
    # a lambda that raises would otherwise only be discovered on a live run.
    assert spec.build_graph() is not None


@pytest.mark.parametrize("spec", SHIPPED, ids=ids)
def test_the_graph_is_built_fresh_every_time(spec):
    """A module-level compiled graph would accumulate state between runs."""
    assert spec.build_graph() is not spec.build_graph()


@pytest.mark.parametrize("spec", SHIPPED, ids=ids)
def test_the_published_manifest_carries_no_prompts(spec):
    published = json.dumps(spec.manifest.to_json())
    assert spec.persona, f"{spec.ref} has no persona — it is a product with nothing in it"
    # A distinctive run of words from the persona must not be anywhere in what
    # the customer's database receives.
    opening = spec.persona.strip().splitlines()[0]
    assert opening not in published
    for banned in ("persona", "instructions", "prompt"):
        assert banned not in published.lower()


@pytest.mark.parametrize("spec", SHIPPED, ids=ids)
def test_the_manifest_is_complete_enough_to_publish(spec):
    """The fields publish.py and the catalog row actually read."""
    manifest = spec.manifest
    assert manifest.name and manifest.description and manifest.domain
    assert manifest.model, "resolveForModel refuses a blank model rather than picking one"
    assert manifest.guardrails, (
        f"{manifest.ref} publishes no guardrails. They are the part the customer IS "
        f"shown — what the agent promises not to do."
    )
    assert manifest.task_id and manifest.task_id.startswith("RD-")
    assert manifest.blocked_by is None, f"{manifest.ref} is published while blocked"


def test_names_are_unique_because_publish_matches_on_them():
    """``publish.py`` decides update-vs-create by title, and the catalog has no
    unique key on it. Two agents sharing one would leapfrog each other."""
    names = [spec.manifest.name for spec in SHIPPED]
    assert len(names) == len(set(names)), f"duplicate agent names: {names}"


#: The namespace each domain's refs live under. An explicit map rather than a
#: transform of the domain name: "Microsoft 365" would mangle into
#: "microsoft_365.", and a ref is something people type into a database row.
NAMESPACES = {
    "AutoOps": "autoops",
    "AWS": "aws",
    "Azure": "azure",
    "Microsoft 365": "m365",
    "Linux": "linux",
    "Windows Server": "windows",
    "Active Directory": "ad",
    "Security": "security",
}


def test_refs_are_namespaced_by_domain():
    for spec in SHIPPED:
        domain = spec.manifest.domain
        assert domain in NAMESPACES, (
            f"{spec.ref} is in domain {domain!r}, which has no declared ref namespace. "
            f"Add one to NAMESPACES rather than inventing a prefix per agent."
        )
        assert spec.ref.startswith(NAMESPACES[domain] + "."), (
            f"{spec.ref} should start with {NAMESPACES[domain]}. for domain {domain!r}"
        )


# ----------------------------------------------- the narrowing, for real ---


def test_the_reclaimers_gather_phase_is_never_offered_the_delete_tool(monkeypatch):
    """The structural claim, made against the SHIPPED agent.

    ``test_gate.py`` proves the kit narrows, using an agent built in the test.
    This proves the agent we are actually publishing narrows — that its phase
    list, its tool refs and its mutability declarations combine into a GATHER
    that cannot see the deletion. The assertion reads ``bound``, which records
    what was OFFERED, so it fails even if the model never tried to call it.
    """
    from agent_runtime.agents.aws import idle_resource_reclaimer

    model = fakes.install(monkeypatch, fakes.ScriptedModel(script=[
        TriageOut(
            restated="Reclaim idle disks.",
            observations_needed=["the inventory"],
            can_proceed=True,
        ),
        fakes.reply("Taking the inventory.", tool_calls=[
            {"name": "workflow_51", "args": {"Region": "us-east-1"}, "id": "c1"}
        ]),
    ]))

    response = reduce(ReduceRequest(
        agent=AgentDescriptor(
            ref=idle_resource_reclaimer.REF,
            version=idle_resource_reclaimer.VERSION,
            model="claude-sonnet-5",
            vendor=Vendor.ANTHROPIC,
            credentials={"apiKey": "offline"},
        ),
        tools=[
            ToolSpecWire(name="workflow_51", description="Idle resource inventory.",
                         mutating=False),
            ToolSpecWire(name="workflow_52", description="Delete unattached volumes.",
                         mutating=True),
        ],
        event=StartEvent(input="Reclaim idle disks in us-east-1."),
    ))

    assert response.directive is Directive.CALL_TOOLS
    assert response.phase is Phase.GATHER
    assert [call.name for call in response.tool_calls] == ["workflow_51"]

    # TRIAGE binds nothing at all; GATHER binds exactly the read-only tool.
    assert model.bound, "the gather phase bound no tools"
    for binding in model.bound:
        assert "workflow_52" not in binding.names, (
            f"the deletion tool was OFFERED to a phase that only gathers evidence: "
            f"{binding.names}"
        )


def test_a_read_only_agent_binds_its_whole_toolbox(monkeypatch):
    """The other half: narrowing must not hide a tool the agent needs.

    A regression that marked every tool mutating would pass the test above and
    leave the auditors with nothing to call.
    """
    from agent_runtime.agents.aws import public_exposure_auditor

    model = fakes.install(monkeypatch, fakes.ScriptedModel(script=[
        TriageOut(restated="Audit exposure.", observations_needed=["three audits"],
                  can_proceed=True),
        fakes.reply("Collecting all three.", tool_calls=[
            {"name": "workflow_31", "args": {}, "id": "a"},
            {"name": "workflow_32", "args": {}, "id": "b"},
            {"name": "workflow_33", "args": {}, "id": "c"},
        ]),
    ]))

    response = reduce(ReduceRequest(
        agent=AgentDescriptor(
            ref=public_exposure_auditor.REF,
            version=public_exposure_auditor.VERSION,
            model="claude-sonnet-5",
            vendor=Vendor.ANTHROPIC,
            credentials={"apiKey": "offline"},
        ),
        tools=[
            ToolSpecWire(name=f"workflow_3{n}", description=f"Audit {n}.", mutating=False)
            for n in (1, 2, 3)
        ],
        event=StartEvent(input="Audit our exposure."),
    ))

    assert [call.name for call in response.tool_calls] == [
        "workflow_31", "workflow_32", "workflow_33"
    ]
    assert model.bound[-1].names == ["workflow_31", "workflow_32", "workflow_33"]


# ------------------------------------------- the three supplied specs ---


def test_the_finops_analyst_holds_no_tool_that_can_act():
    """The spec calls this "the agent most likely to cause an outage".

    The guardrail that actually delivers on that is not a sentence in a prompt —
    it is holding no destructive tool. Acting on what it finds belongs to
    aws.idle_resource_reclaimer, which has a human approval gate.
    """
    from agent_runtime import agents

    spec = agents.REGISTRY["aws.finops_analyst"]
    assert all(not t.mutating for t in spec.manifest.tools)


def test_the_finops_analyst_enumerates_waste_but_not_services_or_events():
    """A service is not a subject; a CloudTrail event is not a subject.

    The resources under a service are subjects and come from the inventory. An
    event happened and does not persist to be re-examined, so a coverage claim
    over one would mean nothing.
    """
    from agent_runtime import agents

    by_ref = {t.ref: t for t in agents.REGISTRY["aws.finops_analyst"].manifest.tools}

    assert len(by_ref["RD-136-idle-resource-inventory"].subjects) == 3
    assert by_ref["RD-141-cost-explorer-service-delta"].subjects == ()
    assert by_ref["RD-211-cloudtrail-change-timeline"].subjects == ()


def test_the_alert_quality_analyst_may_not_recommend_reducing_coverage():
    """Recall is not observable from alarm state.

    The spec's own warning: without detection_source on every incident, such an
    agent "will confidently recommend deleting the only rule that would have
    caught the next outage". This platform has no incidents at all, so the
    recommendation vocabulary is truncated to changes that cannot reduce what
    gets noticed.
    """
    from agent_runtime import agents

    persona = agents.REGISTRY["aws.alert_quality_analyst"].persona
    guardrails = " ".join(agents.REGISTRY["aws.alert_quality_analyst"].manifest.guardrails)

    for forbidden in ("retire", "demote_to_ticket", "merge_with_sibling"):
        assert forbidden in persona, f"{forbidden} must be named as forbidden"
    assert "may never recommend retire" in guardrails.lower()


def test_the_alert_quality_analyst_emits_no_quality_score():
    """A score is trivially improved by silencing everything.

    The spec requires it always be paired with the detection-gap count for the
    same service. That count does not exist here, so the score does not either —
    a single number with no counterweight is the target the spec says never to
    give a team.
    """
    from agent_runtime import agents

    spec = agents.REGISTRY["aws.alert_quality_analyst"]
    assert "NO QUALITY SCORE" in spec.persona
    assert any("no quality score" in g.lower() for g in spec.manifest.guardrails)


def test_the_escalation_router_never_names_a_person_or_leaves_an_incident_unrouted():
    """The spec's non-negotiable, and the guess that would break it.

    Never silently drop a page: unresolvable ownership must produce a loud
    DEFAULT_FALLBACK decision, not silence. And the tempting shortcut —
    inferring a team from a service name — is a guess dressed as a lookup that
    pages people who have never heard of the service.
    """
    from agent_runtime import agents

    spec = agents.REGISTRY["autoops.escalation_router"]
    guardrails = " ".join(spec.manifest.guardrails).lower()

    assert "never leaves an incident unrouted" in guardrails
    assert "never names an on-call person" in guardrails
    assert "DEFAULT_FALLBACK" in spec.persona
    # It must hold nothing that can page, assign or acknowledge.
    assert all(not t.mutating for t in spec.manifest.tools)


def test_the_escalation_router_claims_no_coverage_over_incidents():
    """An incident closes; it is not a durable subject a later run re-examines.

    The alarm rules behind it are subjects, and those belong to the alert
    quality analyst.
    """
    from agent_runtime import agents

    spec = agents.REGISTRY["autoops.escalation_router"]
    assert spec.declared_subject_kinds() == ()
