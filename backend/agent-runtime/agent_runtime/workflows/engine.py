"""Compiling a :class:`WorkflowSpec` into a LangGraph graph, and running it.

**LangGraph is used for routing, not for durability** — the same call the agent
runtime already made, and for the same reason: the run's real record is a MySQL
row core-service writes as each node finishes. A checkpointer here would be a
second answer to "what has this run already done", and when the two disagree
the cost is a side effect performed twice.

What LangGraph is genuinely buying: declarative conditional edges, a topology
that can be rendered, and per-node callbacks already shaped like the nodes
an operator sees on the canvas.
"""

from __future__ import annotations

import logging
import time
from typing import Any, Callable, TypedDict

from langgraph.graph import END, START, StateGraph

from agent_runtime.workflows import refs
from agent_runtime.workflows.nodes import EXECUTORS, NodeContext, NodeFailed
from agent_runtime.workflows.spec import NodeType, WorkflowSpec

log = logging.getLogger(__name__)

#: A ceiling on node transitions in one run. The spec rejects cycles outright,
#: so reaching this means a routing bug rather than a long workflow.
RECURSION_LIMIT = 200


class RunState(TypedDict, total=False):
    """What flows along the graph.

    ``scope`` is the only thing nodes read; everything else is bookkeeping the
    caller streams out.
    """

    scope: dict[str, dict[str, Any]]
    outputs: dict[str, Any]
    #: Branch taken by the most recent condition, read by its conditional edge.
    branch: str | None
    failure: str | None


#: (node_id, title, finished, elapsed_ms, failed) — deliberately the same shape
#: the Dify bridge streamed, so core-service's progress callback and the live
#: run screen are unchanged by the swap.
ProgressFn = Callable[[str, str, bool, int | None, bool], None]


class WorkflowFailed(RuntimeError):
    """A run that stopped. The message is what an operator reads."""


def compile_graph(spec: WorkflowSpec, context_factory: Callable[[RunState], NodeContext],
                  progress: ProgressFn | None = None):
    """Build the LangGraph for ``spec``.

    Every node becomes a graph node that executes, records its output under its
    own id, and reports progress. Conditions additionally get a conditional
    edge keyed on the branch they just chose.
    """
    graph = StateGraph(RunState)

    for node in spec.nodes:
        graph.add_node(node.id, _make_runner(spec, node, context_factory, progress))

    graph.add_edge(START, spec.start.id)

    for node in spec.nodes:
        if node.type is NodeType.END:
            graph.add_edge(node.id, END)
            continue
        if node.type is NodeType.CONDITION:
            arms = {
                edge.branch or "true": edge.target
                for edge in spec.edges
                if edge.source == node.id
            }
            if not arms:
                raise ValueError(f"condition node {node.id!r} has no outgoing edges")

            # A condition whose chosen arm has no edge ENDS the run. "the false
            # branch goes nowhere" is a legitimate design — a router that pages
            # on P1 and does nothing otherwise is the common case, not an
            # oversight.
            #
            # The mapping alone does not achieve that: LangGraph looks the
            # ROUTER'S RETURN VALUE up in this dict, so an unmapped arm raises
            # `KeyError: 'false'` and fails the run. Putting "__end__" in the
            # dict is useless unless the router actually returns it — which the
            # first version did not, and which no unit test caught because they
            # all declared both arms. A real run on severity=P3 found it.
            def route(state: RunState, _arms: dict[str, str] = arms) -> str:
                branch = state.get("branch") or "true"
                return branch if branch in _arms else "__end__"

            graph.add_conditional_edges(node.id, route, {**arms, "__end__": END})
            continue
        targets = spec.successors(node.id)
        if not targets:
            graph.add_edge(node.id, END)
        for target in targets:
            graph.add_edge(node.id, target)

    return graph.compile()


def _make_runner(spec: WorkflowSpec, node, context_factory, progress: ProgressFn | None):
    """One graph node: execute, merge output into the scope, report progress."""

    def run(state: RunState) -> RunState:
        started = time.monotonic()
        if progress:
            progress(node.id, node.label, False, None, False)

        context = context_factory(state)
        try:
            produced = EXECUTORS[node.type](node, context)
        except (NodeFailed, refs.UnresolvedReference) as exc:
            elapsed = int((time.monotonic() - started) * 1000)
            if progress:
                progress(node.id, node.label, True, elapsed, True)
            # Raised rather than recorded-and-continued. A workflow that carries
            # on past a failed node produces a report built on a gap nobody can
            # see from the outcome.
            raise WorkflowFailed(f"{node.label}: {exc}") from exc

        elapsed = int((time.monotonic() - started) * 1000)
        if progress:
            progress(node.id, node.label, True, elapsed, False)

        scope = {**state.get("scope", {}), node.id: produced}
        nxt: RunState = {"scope": scope}
        if node.type is NodeType.CONDITION:
            nxt["branch"] = produced.get("result")
        if node.type is NodeType.END:
            nxt["outputs"] = produced
        return nxt

    return run


def run_workflow(spec: WorkflowSpec, inputs: dict[str, Any],
                 context_factory: Callable[[RunState], NodeContext],
                 progress: ProgressFn | None = None) -> dict[str, Any]:
    """Execute ``spec`` and return its declared outputs.

    The start node's id keys the inputs, so a prompt reads
    ``{{#start.incident_notes#}}`` — the same reference form as any other node,
    rather than a second special syntax for "the form".
    """
    initial: RunState = {"scope": {spec.start.id: dict(inputs)}, "outputs": {}}
    compiled = compile_graph(spec, context_factory, progress)
    final = compiled.invoke(initial, config={"recursion_limit": RECURSION_LIMIT})
    return final.get("outputs", {})
