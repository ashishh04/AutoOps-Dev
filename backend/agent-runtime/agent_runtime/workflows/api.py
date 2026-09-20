"""The HTTP surface for workflow runs.

One route, streaming NDJSON. Java's only other option would be to block on a
single response for the whole run, and these runs take minutes — the Dify
bridge already learned that lesson the expensive way: without per-node progress
a twenty-minute workflow shows a motionless spinner and an operator reasonably
concludes it has hung.

**The event shape is deliberately the one the Dify bridge already streamed** —
``(nodeId, title, finished, elapsedMs, failed)``. core-service's progress
callback, the line-by-line run log and the live run screen all consume that
tuple today, so swapping the engine underneath costs no change above it.

NDJSON rather than SSE: one JSON object per line needs no framing library on
either side, and Java's ``BufferedReader.lines()`` consumes it directly.
"""

from __future__ import annotations

import json
import logging
from typing import Any, Iterator

from pydantic import BaseModel, Field

from agent_runtime.app.models import build_model
from agent_runtime.app.state import AgentDescriptor
from agent_runtime.workflows import refs
from agent_runtime.workflows.engine import WorkflowFailed, run_workflow
from agent_runtime.workflows.nodes import NodeContext
from agent_runtime.workflows.spec import SPEC_VERSION, WorkflowSpec

log = logging.getLogger(__name__)


class WorkflowRunRequest(BaseModel):
    """One workflow run.

    ``model`` reuses :class:`AgentDescriptor` rather than declaring a near-copy:
    it already carries exactly (model, vendor, credentials, params), it is the
    shape agent-service and core-service both build, and :func:`build_model`
    consumes it. Its ``ref``/``instructions`` fields are unused here and stay
    empty — a second almost-identical descriptor would be one more place for
    the credential key names to drift from Java's ``ModelVendor``.
    """

    run_id: int | None = Field(default=None, alias="runId")
    tenant_id: str | None = Field(default=None, alias="tenantId")
    #: Which project this run belongs to. Required by the `platform` node,
    #: whose whole question — "what happened here" — is about a workspace
    #: rather than a whole customer.
    project_id: int | None = Field(default=None, alias="projectId")
    definition: dict[str, Any]
    inputs: dict[str, Any] = Field(default_factory=dict)
    model: AgentDescriptor

    model_config = {"populate_by_name": True, "protected_namespaces": ()}


def readable(error: Exception) -> str:
    """A validation failure as prose, with the offending value stripped out.

    ``str(ValidationError)`` embeds ``input_value=...`` — for a workflow, that
    is **the entire definition**. This message travels into ``runs.log``, which
    a customer can read, and a provider-authored workflow is meant to be sealed:
    the customer holds a reference, never the design. Echoing the definition
    into a run log because a node was misconfigured would undo that in one
    error path, quietly, and only for workflows that were broken — which is
    exactly when nobody is looking.

    It also strips pydantic's docs URL and type codes, which tell an operator
    reading a run log nothing they can act on.
    """
    errors = getattr(error, "errors", None)
    if not callable(errors):
        return str(error)
    messages: list[str] = []
    for item in errors():
        location = ".".join(str(part) for part in item.get("loc", ()) if part != "__root__")
        message = item.get("msg", "").removeprefix("Value error, ")
        messages.append(f"{location}: {message}" if location else message)
    return "; ".join(dict.fromkeys(messages)) or str(error)


def _line(payload: dict[str, Any]) -> str:
    return json.dumps(payload, ensure_ascii=False, default=str) + "\n"


def stream_run(request: WorkflowRunRequest) -> Iterator[str]:
    """Execute a workflow, yielding one NDJSON line per event.

    Every terminating path emits exactly one ``done`` event. A stream that ends
    without one means the process died, and Java can tell those apart — which
    matters, because "the run failed" and "we do not know what happened to the
    run" call for different actions on a workflow that may already have had
    side effects.
    """
    try:
        spec = WorkflowSpec.model_validate(request.definition)
    except Exception as exc:  # noqa: BLE001 - reduced to prose by readable()
        # A definition error, not a run failure. Reported as a failed run
        # because that is what the caller asked for, but named clearly so the
        # author knows to edit the workflow rather than retry it.
        yield _line({"event": "done", "success": False,
                     "error": f"This workflow cannot run as defined: {readable(exc)}"})
        return

    if spec.version > SPEC_VERSION:
        yield _line({"event": "done", "success": False,
                     "error": f"This workflow was written for definition version "
                              f"{spec.version}; this runtime understands {SPEC_VERSION}. "
                              f"Upgrade the runtime rather than downgrading the workflow."})
        return

    # Caught before anything executes: a reference that cannot resolve is an
    # authoring mistake, and finding it after three model calls costs tokens to
    # learn nothing.
    problems = refs.validate_against(spec)
    if problems:
        yield _line({"event": "done", "success": False,
                     "error": "This workflow reads values that are not available: "
                              + "; ".join(problems)})
        return

    # No tracing — see reduce.py. `traceId` is still emitted on the `done`
    # event because core-service reads it, and is always null.
    handlers: list = []
    trace_id = None

    def context_factory(state):
        def model_factory(model: str | None = None, temperature: float | None = None,
                          max_tokens: int | None = None):
            # Built per node, never cached: a cached client outlives the key it
            # was made with and survives a rotation the tenant believes took
            # effect immediately.
            descriptor = request.model
            if model or temperature is not None:
                params = dict(descriptor.params)
                if temperature is not None:
                    params["temperature"] = temperature
                descriptor = descriptor.model_copy(
                    update={"model": model or descriptor.model, "params": params},
                )
            return build_model(descriptor, max_tokens=max_tokens)

        return NodeContext(
            scope=state.get("scope", {}),
            model_factory=model_factory,
            callbacks=handlers,
            # Carried so a `job` node can ask the control plane to run an
            # automation. This service still decides nothing about who may run
            # what — core-service re-checks the tenant on the run it creates.
            tenant_id=request.tenant_id,
            project_id=request.project_id,
            actor=f"workflow:{request.run_id}" if request.run_id else "workflow",
        )

    events: list[dict[str, Any]] = []

    def progress(node_id: str, title: str, finished: bool,
                 elapsed_ms: int | None, failed: bool) -> None:
        events.append({"event": "node", "nodeId": node_id, "title": title,
                       "finished": finished, "elapsedMs": elapsed_ms, "failed": failed})

    # The engine is synchronous, so events are drained after it returns rather
    # than truly live. That is a known limit and a deliberate first step: the
    # contract above is already the streaming one, so making the engine yield
    # as it goes is a change here and nowhere else.
    try:
        outputs = run_workflow(spec, request.inputs, context_factory, progress)
        for event in events:
            yield _line(event)
        yield _line({"event": "done", "success": True, "outputs": outputs,
                     "totalNodes": len(spec.nodes), "traceId": trace_id})
    except WorkflowFailed as exc:
        for event in events:
            yield _line(event)
        yield _line({"event": "done", "success": False, "error": str(exc),
                     "totalNodes": len(spec.nodes), "traceId": trace_id})
    except Exception as exc:  # noqa: BLE001 - never leak a stack into a run log
        log.exception("Workflow run %s crashed", request.run_id)
        for event in events:
            yield _line(event)
        yield _line({"event": "done", "success": False,
                     "error": f"The workflow runtime failed: {exc}"})


def inspect(definition: dict[str, Any]) -> dict[str, Any]:
    """Validate a definition and report its form, without running it.

    This is what replaces reading a Dify app's ``/v1/parameters`` over the
    network on every list and every run. The form now travels *with* the
    definition, so it cannot desynchronise from the variables the workflow
    actually reads — which was the failure mode that made the remote lookup
    necessary in the first place.
    """
    try:
        spec = WorkflowSpec.model_validate(definition)
    except Exception as exc:  # noqa: BLE001
        return {"valid": False, "error": readable(exc), "inputs": []}

    return {
        "valid": True,
        "name": spec.name,
        "description": spec.description,
        "nodeCount": len(spec.nodes),
        "problems": refs.validate_against(spec),
        "inputs": [
            {
                "variable": field.variable,
                "label": field.label,
                "type": field.type.value,
                "required": field.required,
                "default": field.default,
                "options": field.options,
                "maxLength": field.max_length,
                "hint": field.hint,
            }
            for field in spec.inputs
        ],
    }
