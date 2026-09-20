"""Assembling phases into a runnable graph.

**We use LangGraph for routing, not for durability.** Its checkpointers exist
to keep a graph alive across an interruption; ours is already kept alive by a
MySQL row that Java writes after every step, inside the same transaction as the
audit trail and the approval that caused the interruption. Adding a second
persistence layer would mean two answers to "what has this run already done",
and the one that is wrong is the one that executes a destructive tool twice.

So the graph is compiled fresh on every reduce, runs from the phase the state
says it is in, and stops at the first boundary. What LangGraph gives us is the
part worth having: declarative routing, a topology that can be rendered, and
per-node callbacks already shaped like the phases an operator sees.

An agent declares only the phases it needs. A read-only agent has no PLAN,
GATE, ACT or VERIFY, and :func:`_resolve` walks past anything absent rather
than requiring each agent to restate the whole flow.
"""

from __future__ import annotations

from langgraph.graph import END, START, StateGraph

from agent_runtime.app.state import Phase
from agent_runtime.graph import phases
from agent_runtime.graph.phases import GraphState

#: The order phases fall through in when an agent has not declared one of them.
#: This is the canonical shape of an investigation, and every agent is a subset
#: of it — which is what makes runs comparable across agents in the eval
#: harness.
ORDER: list[Phase] = [
    Phase.TRIAGE,
    Phase.GATHER,
    Phase.HYPOTHESIZE,
    Phase.PLAN,
    Phase.GATE,
    Phase.ACT,
    Phase.VERIFY,
    Phase.REPORT,
]

NODES = {
    Phase.TRIAGE: phases.triage,
    Phase.GATHER: phases.gather,
    Phase.HYPOTHESIZE: phases.hypothesize,
    Phase.PLAN: phases.plan,
    Phase.GATE: phases.gate,
    Phase.ACT: phases.act,
    Phase.VERIFY: phases.verify,
    Phase.REPORT: phases.report,
}

#: A ceiling on node transitions within ONE reduce call, not across a run.
#: Every phase either advances or parks, so reaching this means a routing bug
#: rather than a slow investigation — and a bug should surface as an error, not
#: as a request that never returns.
RECURSION_LIMIT = 24


class UnknownPhase(ValueError):
    """A state naming a phase the agent's graph does not contain."""


def _resolve(phase: Phase, available: set[Phase]) -> Phase | None:
    """The node that should run for a phase, skipping ones this agent omits.

    Walks FORWARD through :data:`ORDER` only. A phase earlier in the order is
    never substituted: routing backwards to fill a gap would send a run that
    finished acting back to gathering, which looks like diligence and is
    actually a loop.
    """
    if phase is Phase.DONE:
        return None
    if phase in available:
        return phase
    try:
        start = ORDER.index(phase)
    except ValueError as exc:  # pragma: no cover - Phase is closed
        raise UnknownPhase(str(phase)) from exc
    for candidate in ORDER[start + 1 :]:
        if candidate in available:
            return candidate
    return None


def _advance(state, available: set[Phase]) -> str:
    """Resolves the next node AND records it on the state.

    Writing it back matters for honesty rather than for routing. A node sets
    ``phase`` to where it thinks the run goes next — HYPOTHESIZE says PLAN —
    without knowing which phases this particular agent declared. On a read-only
    agent that PLAN is skipped, and if the run then failed, the operator would
    be looking at a run view claiming it stopped in a phase the agent does not
    have. The phase an operator reads is now always a node that really ran.
    """
    target = _resolve(state.phase, available)
    if target is None:
        return END
    state.phase = target
    return target.value


def build(declared: list[Phase]):
    """Compiles an agent's phases into a graph that stops at the next boundary."""
    available = set(declared)
    if Phase.REPORT not in available:
        # Without it a run reaches its end with nothing to hand the operator.
        # Cheaper to require it here than to discover it on a live run.
        raise ValueError("Every agent must declare Phase.REPORT — it is what the operator reads.")

    graph: StateGraph = StateGraph(GraphState)
    for phase in declared:
        graph.add_node(phase.value, NODES[phase])

    def route(graph_state: GraphState) -> str:
        state = graph_state["state"]
        # A node that captured tool calls has reached the boundary: the graph
        # ends and Java takes over. This is the ONLY way a tool ever runs.
        if state.pending_tool_calls:
            return END
        return _advance(state, available)

    def entry(graph_state: GraphState) -> str:
        return _advance(graph_state["state"], available)

    destinations = {phase.value: phase.value for phase in declared} | {END: END}
    graph.add_conditional_edges(START, entry, destinations)
    for phase in declared:
        graph.add_conditional_edges(phase.value, route, destinations)

    return graph.compile()
