"""The native workflow runtime: a node graph compiled onto LangGraph.

This is the replacement for the Dify bridge. The workflows it took over were,
in Dify's own DSL, ``start -> llm -> end`` — three nodes and two edges — and
running them cost a second application platform, a second Postgres and a second
place where a tenant's model credentials live.

Layout:

``spec.py``    the wire contract: nodes, edges, the input form, and the
               structural validation that refuses an unrunnable graph at save
               time rather than at 3am
``refs.py``    ``{{#node.field#}}`` resolution; an unresolved reference is an
               error, never an empty string
``nodes.py``   one executor per node type; none of them can reach a customer's
               infrastructure
``engine.py``  compilation onto LangGraph and the run loop
"""

from agent_runtime.workflows.engine import WorkflowFailed, run_workflow
from agent_runtime.workflows.spec import SPEC_VERSION, WorkflowSpec

__all__ = ["SPEC_VERSION", "WorkflowFailed", "WorkflowSpec", "run_workflow"]
