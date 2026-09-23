"""The HTTP surface: three routes, none of them public.

api-gateway does not route to this service. Its only caller is agent-service,
over the compose network, holding the shared secret — the same arrangement the
Java services already use between themselves. There is no tenant auth here
because there is no tenant here: this service never decides who may run what.
It is handed a resolved agent, a resolved toolbox and a decrypted key, and its
job is to think.

``/v1/reduce`` is synchronous and can take as long as a model call takes.
That is deliberate: the *run* is asynchronous — Java queues it, drives it on its
own executor and persists after every step — so this call only ever spans one
boundary, never a whole investigation.
"""

from __future__ import annotations

import logging

from fastapi import Depends, FastAPI, Header, HTTPException, status
from fastapi.responses import JSONResponse, StreamingResponse

from agent_runtime import agents
from agent_runtime.app.authoring import schema as authoring_schema
from agent_runtime.app.config import settings
from agent_runtime.app.models import is_runnable
from agent_runtime.app.reduce import reduce
from agent_runtime.app.state import STATE_VERSION, ReduceRequest, ReduceResponse, Vendor
from agent_runtime.graph.prompts import PROMPT_VERSION
from agent_runtime.workflows import SPEC_VERSION
from agent_runtime.workflows.api import WorkflowRunRequest, inspect, stream_run

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)-5s %(name)s : %(message)s",
)
log = logging.getLogger(__name__)

app = FastAPI(
    title="AutoOps agent runtime",
    version="0.1.0",
    description="The reasoning half of AutoOps agents: a stateless (state, event) -> state' reducer.",
)


def require_internal_token(x_internal_token: str = Header(default="")) -> None:
    """The one gate on this service.

    Compared in full rather than short-circuited on the first differing byte;
    the timing signal on a shared secret is small but there is no reason to
    leak it.
    """
    import hmac

    expected = settings().internal_token
    if not hmac.compare_digest(x_internal_token or "", expected):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="This endpoint is internal to AutoOps.",
        )


@app.get("/health")
def health() -> dict:
    """Unauthenticated, because compose's healthcheck has no secret.

    Reports what this build IS — which agents, which prompts, which state
    version — because the single most useful thing during an incident is
    knowing whether the deployment is the one you think it is.
    """
    return {
        "status": "UP",
        "state_version": STATE_VERSION,
        "prompt_version": PROMPT_VERSION,
        "workflow_spec_version": SPEC_VERSION,
        "agents": sorted(agents.REGISTRY),
    }


@app.get("/v1/agents", dependencies=[Depends(require_internal_token)])
def catalog() -> dict:
    """Every agent's PUBLIC manifest.

    Personas and prompts are absent by construction — :meth:`Manifest.to_json`
    cannot emit them. This is what the build step writes to ``manifest.json``
    and what ``publish.py`` sends to the catalog, so the thing that ships and
    the thing this endpoint serves cannot drift.
    """
    return {"agents": agents.catalog()}


@app.get("/v1/vendors", dependencies=[Depends(require_internal_token)])
def vendors() -> dict:
    """Which vendors this runtime can actually serve.

    agent-service reads this to decide whether an agent goes to the phased
    runtime or stays on the legacy Java loop — Huawei has a Java adapter and no
    LangChain one. Published rather than hard-coded on the Java side so the two
    cannot disagree after a change here.
    """
    return {vendor.value: is_runnable(vendor) for vendor in Vendor}


@app.post(
    "/v1/reduce",
    response_model=ReduceResponse,
    dependencies=[Depends(require_internal_token)],
)
def reduce_endpoint(request: ReduceRequest) -> ReduceResponse:
    """Advance one run by one boundary.

    Returns 200 even when the run failed. A failure is a *result* — it carries
    the state, the tokens already spent and a message for the operator — and
    turning it into a 5xx would make Java's client guess at what happened to a
    run that may have already executed automations. Only a malformed request
    is an HTTP error.
    """
    response = reduce(request)
    log.info(
        "run=%s agent=%s phase=%s -> %s (%d tool call(s), %d+%d tokens)",
        request.run_id,
        request.agent.ref,
        response.phase.value,
        response.directive.value,
        len(response.tool_calls),
        response.usage.prompt_tokens,
        response.usage.completion_tokens,
    )
    return response


@app.get("/v1/authoring/schema", dependencies=[Depends(require_internal_token)])
def authoring_schema_endpoint() -> dict:
    """What a designer is allowed to build, straight out of the models.

    The console needs a palette: which node types exist, which fields each one
    takes, which are mandatory, what the legal values are. The cheap way to
    supply that is a constant in the frontend — and a constant is a second copy
    of a contract that changes here. It goes stale silently: the runtime gains a
    node type, the console never offers it, and the first person to notice is
    whoever asks why a workflow authored in the console cannot do what one
    authored in the repo can.

    So it is served from the same models that execute the definition. The
    required flags in particular are not asserted but PROBED — built by asking
    ``Node``'s own validator whether it minds a field being absent — which means
    the designer's red asterisk and the runtime's refusal can never disagree.

    Cacheable and boring: it depends on nothing but this build, so a console can
    fetch it once per page load without thinking about it.
    """
    return authoring_schema()


@app.post("/v1/workflows/run", dependencies=[Depends(require_internal_token)])
def run_workflow_endpoint(request: WorkflowRunRequest) -> StreamingResponse:
    """Execute a workflow, streaming one NDJSON event per node.

    Replaces the Dify bridge. The event shape is the one core-service's
    progress callback already consumes, so the live run screen and the
    line-by-line run log are unchanged by the swap.

    Always 200, even for a run that fails: a failure is a *result* that Java
    has to record against a run which may already have had side effects, and a
    5xx would make the client guess at what happened. Only a malformed request
    is an HTTP error.
    """
    return StreamingResponse(stream_run(request), media_type="application/x-ndjson")


@app.post("/v1/workflows/inspect", dependencies=[Depends(require_internal_token)])
def inspect_workflow_endpoint(definition: dict) -> dict:
    """Validate a definition and report its input form, without running it.

    This is what replaces reading a Dify app's ``/v1/parameters`` over the
    network on every list and every run. The form travels WITH the definition
    now, so it cannot desynchronise from the variables the workflow reads.
    """
    return inspect(definition)


@app.exception_handler(Exception)
async def unhandled(_request, exc: Exception) -> JSONResponse:  # pragma: no cover
    """Last resort. Never leaks an internal detail into a customer-visible run."""
    log.exception("Unhandled error in agent-runtime", exc_info=exc)
    return JSONResponse(
        status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
        content={"error": "agent_runtime_error", "message": "The agent runtime failed."},
    )
