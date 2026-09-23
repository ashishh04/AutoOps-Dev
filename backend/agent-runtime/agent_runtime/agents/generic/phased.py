"""The agent a provider builds in the console, running the real runtime.

**What this closes.** Until now an agent had two ways to exist and they were not
equal. A Python module got phase narrowing, evidence enforcement, subject
extraction and a coverage claim — everything that makes a finding trustworthy
enough to act on. Anything authored in the console got
:mod:`~agent_runtime.agents.generic.single_phase`: one node, every tool bound at
once, no narrowing, no citations, and an empty manifest, which meant it could
declare no subject kinds and therefore could never claim coverage. Its findings
could never be reaped, because nothing it claimed was checkable.

So the honest answer to "can I build an agent from the console" was "you can
build a lesser one, and nothing tells you so."

**Why the gap could be closed at all.** Ten of the eleven shipped agents build
their graph with exactly ``kit.build(list(PHASES))``. Not one contains custom
Python. What distinguishes them is a persona, a model, an allow-list, a phase
list and a set of subject declarations — every one of which is data, and every
one of which a form can collect. The Python module was never the thing that made
those agents work; it was just where the data happened to live.

**What is deliberately NOT changed.** An agent that declares no phases still
resolves to the compatibility loop, byte for byte as before. The phased runtime
is opted into by declaring phases, never switched on underneath a persona
written for the old loop — a legacy prompt has no citation rule, and turning
evidence enforcement on under it would fill its report with ``[e:..]`` markers
its author never accounted for.

**The one thing this agent cannot have.** A name of its own in the registry.
Its ref is fixed and its identity — persona, tools, subjects — arrives per run
on the descriptor. That is a real consequence: LangSmith traces and any
per-agent metric keyed on the runtime ref will group every console-authored
agent together. The distinguishing name is the catalog entry's, which
agent-service holds, and that is the right place to look at them separately.
"""

from __future__ import annotations

from functools import lru_cache

from agent_runtime.agents.spec import AgentSpec, Manifest
from agent_runtime.app.state import Phase
from agent_runtime.graph import kit

REF = "generic.phased"

#: What a console agent gets when it declares nothing usable.
#:
#: Not a suggestion and not a default offered in the UI — the console publishes
#: the real phase list and an author picks from it. This exists for the
#: descriptor that names phases this build does not have, which is what a
#: catalog ahead of a deployment looks like. Refusing the run outright would be
#: the alternative, and it is worse: the agent has a persona and tools and can
#: do useful work, and REPORT is the one phase whose absence would leave the
#: operator with nothing at all.
FALLBACK: tuple[Phase, ...] = (Phase.GATHER, Phase.REPORT)


def phases_from(declared: list[str] | tuple[str, ...]) -> tuple[Phase, ...]:
    """The declared phase names, as phases this build can actually run.

    Unknown names are DROPPED rather than refused. A catalog entry naming a
    phase a deployment does not have is the ordinary shape of being one release
    behind, and failing the run would turn a rollout-ordering problem into an
    outage for an agent that would otherwise work. What is dropped is visible:
    the response reports the phases the run actually visited.

    ``REPORT`` is appended when missing because :func:`kit.build` requires it —
    it is what the operator reads, and a graph without it runs to the end and
    hands back nothing.
    """
    seen: list[Phase] = []
    for name in declared or ():
        try:
            phase = Phase(str(name).strip().upper())
        except ValueError:
            continue
        # A phase named twice is a form that was submitted twice, not a request
        # to run it twice — kit.build would add the node twice and the second
        # would silently replace the first.
        if phase in seen:
            continue
        # Neither is an authoring choice: DONE is a terminal marker, and RESPOND
        # is the un-phased loop this module exists to replace. Accepting RESPOND
        # here would let a descriptor assemble, out of the phased runtime, the
        # exact un-narrowed agent the phased runtime exists to prevent.
        if phase in (Phase.DONE, Phase.RESPOND):
            continue
        seen.append(phase)

    if not seen:
        return FALLBACK
    if Phase.REPORT not in seen:
        seen.append(Phase.REPORT)
    return tuple(seen)


@lru_cache(maxsize=64)
def spec_for(phases: tuple[Phase, ...]) -> AgentSpec:
    """An :class:`AgentSpec` for one phase list.

    Cached, and safe to cache: ``build_graph`` is a FACTORY, so each run still
    compiles its own graph and nothing is shared between runs but the phase
    tuple itself. Caching the spec rather than the graph is the whole point —
    a module-level compiled graph is precisely how state leaks between runs.

    The persona is empty, which is what makes the descriptor's ``instructions``
    get injected: a console-authored agent's voice lives in the catalog row the
    provider wrote, not in this image.
    """
    return AgentSpec(
        manifest=Manifest(
            ref=REF,
            version="1.0.0",
            name="Console-authored agent",
            description=(
                "A phased agent whose persona, tools, phases and subject declarations "
                "arrive per run from the catalog rather than from a module in this image."
            ),
            domain="Generic",
            # Always overridden by the agent's own row. Present so the manifest
            # is complete; nothing publishes this agent to the catalog, because
            # it is a runtime shape rather than a product.
            model="anthropic.claude-sonnet-5",
            guardrails=[
                "Tool access is the agent's own allow-list, enforced by agent-service.",
                "Phase narrowing and evidence enforcement are on, as for any phased agent.",
            ],
        ),
        persona="",
        build_graph=lambda: kit.build(list(phases)),
        phases=phases,
    )
