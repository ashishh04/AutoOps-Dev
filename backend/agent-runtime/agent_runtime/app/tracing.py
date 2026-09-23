"""LangSmith tracing, and what happens when it is not there.

Every model call this service makes is traced with the phase it belongs to, the
prompt version that produced it, and the agent ref and version. That is what
turns "the agent said something odd on Tuesday" into a specific node, a specific
prompt revision and a specific set of observations.

**Tracing never fails a run.** If LangSmith is unconfigured, unreachable, or
throws while building a handler, this returns no callbacks and the run proceeds
untraced. An observability dependency that can take down the thing it observes
is worse than no observability — and this particular thing runs automations
against customer infrastructure.

**One trace per reduce, one THREAD per run.** A reduce is a single graph
traversal, so it is a single trace with each phase nested inside it. A run,
though, can span many reduces — it stops every time it wants a tool, and it can
sit on an approval for two days between them. LangSmith reassembles those into
one conversation from the ``session_id`` on the root run's metadata, which is
why that id is derived from the RUN id and not generated per call.

**What must never appear in a trace.** The tenant's model credentials arrive on
the descriptor and leave with the response; they are not ours to ship to a third
party. Nothing here reads ``agent.credentials`` — the metadata is assembled
field by field rather than dumped from the descriptor, and a test asserts that a
credential cannot reach the payload. ``langsmith_hide_io`` goes further for
customers who do not want their infrastructure data leaving the estate at all:
it keeps the shape of every run (phases, timings, token counts, errors) and
drops the message bodies.
"""

from __future__ import annotations

import logging
from typing import Any

from agent_runtime.app.config import settings
from agent_runtime.app.state import AgentDescriptor
from agent_runtime.graph.prompts import PROMPT_VERSION

log = logging.getLogger(__name__)


def thread_id(run_id: int | None) -> str | None:
    """The id that stitches a run's several reduces into one conversation.

    ``None`` for a run with no id — a replay or an eval — because a thread
    every unidentified run shares is worse than no thread at all.
    """
    return f"agent-run-{run_id}" if run_id else None


def handlers(
    agent: AgentDescriptor,
    *,
    run_id: int | None,
    tenant_id: str | None,
) -> tuple[list[Any], str | None]:
    """Callback handlers for one reduce, and the thread they will write to.

    Returned as a pair rather than stashed on a module global because this
    service is a reducer: two reduces for two different tenants can be in
    flight on the same process, and a handler held anywhere but the call frame
    would eventually be the wrong tenant's.
    """
    config = settings()
    if not config.langsmith_enabled:
        return [], None
    if not config.langsmith_api_key:
        # Warned rather than silently skipped: someone set the flag intending
        # to get traces, and the absence of a key is the reason they will not.
        log.warning("LangSmith tracing is enabled but LANGSMITH_API_KEY is unset; running untraced.")
        return [], None

    try:
        from langchain_core.tracers import LangChainTracer
        from langsmith import Client

        client = Client(
            api_key=config.langsmith_api_key,
            api_url=config.langsmith_endpoint,
            # Structure always, bodies only by consent. Passing True drops the
            # inputs and outputs of every run before they leave the process,
            # so a tenant who will not let infrastructure detail reach a SaaS
            # still gets phase timings, token counts and failures.
            hide_inputs=config.langsmith_hide_io,
            hide_outputs=config.langsmith_hide_io,
        )
        tracer = LangChainTracer(
            project_name=config.langsmith_project,
            client=client,
            tags=_tags(agent, tenant_id),
        )
        return [tracer], thread_id(run_id)
    except Exception as exc:  # noqa: BLE001 - see the module docstring
        log.warning("Tracing unavailable, running untraced: %s", exc)
        return [], None


def _tags(agent: AgentDescriptor, tenant_id: str | None) -> list[str]:
    """The facets worth filtering a trace list by.

    Every one of these is a question someone actually asks: which agent, which
    version of it, which prompt revision, which vendor, which customer. None is
    a secret, and the tenant appears as its ID alone — the identifier that
    names a customer, never anything observed inside their estate.

    The tenant belongs here rather than only in the metadata because one agent
    is authored once and delivered to every customer who buys it. "Is this
    version misbehaving everywhere, or only at one site?" is the first question
    asked of a regression, and a tag is the one facet the trace list filters on
    without a query.
    """
    return [
        # The ref when there is one, the catalog name when there is not. A
        # console-authored agent has no ref — every one of them resolves to the
        # same module — so falling straight through to 'unspecified' put all of
        # them in one undifferentiated bucket.
        f"agent:{agent.ref or agent.name or 'unspecified'}",
        f"agent_version:{agent.version or 'unversioned'}",
        f"prompts:{PROMPT_VERSION}",
        f"vendor:{agent.vendor.value}",
        # 'none' rather than omitted: a run with no tenant is an eval or a
        # replay, and being able to filter those OUT of a customer
        # investigation is worth as much as being able to filter one in.
        f"tenant:{tenant_id or 'none'}",
    ]


def graph_config(
    callbacks: list[Any],
    agent: AgentDescriptor,
    *,
    run_id: int | None,
    tenant_id: str | None,
) -> dict[str, Any]:
    """The config for the ONE call that carries the tracer: ``graph.invoke``.

    Attached here and nowhere else, and that is load-bearing rather than tidy.
    LangChain resolves a run's parent from the callback manager it inherits
    through a context variable, and a config that names ``callbacks``
    explicitly REPLACES that manager — so a tracer passed again on each model
    call turns every phase into its own top-level trace, and the sequence an
    operator wants to read is scattered across eight of them. Passing an empty
    list is worse still: it replaces the inherited manager with nothing and the
    call is not traced at all.

    :meth:`~agent_runtime.graph.context.RunContext.config` therefore carries
    the run name, the tags and the metadata for each phase, and deliberately
    carries no callbacks. ``tests/test_tracing.py`` pins both halves.
    """
    metadata: dict[str, Any] = {
        "agent_ref": agent.ref,
        "agent_version": agent.version,
        "prompt_version": PROMPT_VERSION,
        "model": agent.model,
        "vendor": agent.vendor.value,
        "run_id": run_id,
        "tenant_id": tenant_id,
    }
    session = thread_id(run_id)
    if session:
        # The key LangSmith groups threads by. Set only when there is a real
        # run to group.
        metadata["session_id"] = session

    return {
        "callbacks": callbacks,
        "run_name": f"{agent.ref or 'agent'}:reduce",
        "tags": _tags(agent, tenant_id),
        "metadata": metadata,
        # A reduce spans at most eight phases plus LangGraph's own channel
        # writes. The graph kit's own ceiling is what actually bounds this;
        # stating it here keeps a routing bug from hanging the request.
        "recursion_limit": 24,
    }
