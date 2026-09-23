"""What each node type actually does.

Every executor has the same shape: read the scope, do one thing, return the
fields it contributes. None of them mutate the scope — the engine merges what
they return, keyed by node id — so a node's blast radius is exactly its own
output and can be read off the canvas.

**No node here can reach a customer's infrastructure.** LLM calls go to the
tenant's own model vendor and HTTP goes where the workflow says, but nothing
executes a script, opens a shell or touches a cloud account. That stays on the
Java side, behind the approvals inbox and the audit trail — the same split the
agent runtime already keeps, and for the same reason.
"""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass
from typing import Any

import json

import httpx
from langchain_core.messages import AIMessage, HumanMessage, SystemMessage

from agent_runtime.app.config import settings
from agent_runtime.workflows import refs
from agent_runtime.workflows.spec import Node, NodeType

log = logging.getLogger(__name__)

#: A node's HTTP call is a workflow step, not a health check: a slow API is
#: normal. Bounded anyway, because a hung request would hold the whole run.
HTTP_TIMEOUT_SECONDS = 30.0

#: Response bodies land in a prompt and then in a run log. Past this they stop
#: being evidence and start being a denial of service against both.
HTTP_MAX_BODY_CHARS = 200_000

_ROLES = {"system": SystemMessage, "user": HumanMessage, "assistant": AIMessage}

#: Line separator for the rendered timeline. Named rather than inlined so the
#: rendering reads as a list of lines rather than as an escape sequence.
NEWLINE = chr(10)


class NodeFailed(RuntimeError):
    """A node that could not do its job. Carries the node id for the run log."""

    def __init__(self, node_id: str, message: str) -> None:
        super().__init__(message)
        self.node_id = node_id


@dataclass
class NodeContext:
    """Everything a node needs that is not in its own configuration."""

    scope: dict[str, dict[str, Any]]
    #: Built per call by the caller, so a rotated key takes effect immediately
    #: and no client outlives the credential it was made with.
    model_factory: Any
    callbacks: list[Any]
    #: Whose automations a `job` node may start. Sent on every dispatch and
    #: re-checked by core-service against the run it is asked to create — this
    #: service never decides who may run what.
    tenant_id: str | None = None
    #: Which project's history the `platform` node may read. Scoped with the
    #: tenant on every query — neither alone is the boundary.
    project_id: int | None = None
    #: Named on the run it starts, so run history shows that a workflow
    #: triggered it rather than a person.
    actor: str = "workflow"


def run_start(node: Node, context: NodeContext) -> dict[str, Any]:
    """The start node contributes the run's inputs, already in the scope.

    It executes so that the run log shows it and so a graph with a start node
    and nothing else is still a legal, traceable run.
    """
    return dict(context.scope.get(node.id, {}))


def run_llm(node: Node, context: NodeContext) -> dict[str, Any]:
    """One model call. Contributes ``text``, and ``prompt_tokens``/``completion_tokens``."""
    messages = []
    for message in node.prompt:
        text = refs.resolve(message.text, context.scope, where=node.id)
        messages.append(_ROLES[message.role](content=text or ""))

    model = context.model_factory(
        model=node.model,
        temperature=node.temperature,
        max_tokens=node.max_tokens,
    )
    try:
        reply = model.invoke(messages, config={"callbacks": context.callbacks})
    except Exception as exc:  # noqa: BLE001 - the vendor's error is the useful part
        raise NodeFailed(node.id, f"the model call failed: {exc}") from exc

    usage = getattr(reply, "usage_metadata", None) or {}
    return {
        "text": reply.content if isinstance(reply.content, str) else str(reply.content),
        "prompt_tokens": usage.get("input_tokens", 0),
        "completion_tokens": usage.get("output_tokens", 0),
    }


def run_job(node: Node, context: NodeContext) -> dict[str, Any]:
    """Runs an automation and waits for it. Contributes ``status``, ``output``, ``runId``.

    This is the node that joins the half of the platform that DECIDES to the
    half that DOES. Everything AutoOps can actually perform — the script
    library, PowerShell, SSH, Terraform, cloud accounts — is reachable from a
    workflow through here and nowhere else.

    **It dispatches; it does not execute.** The call goes to core-service's
    ``/internal/agent/dispatch``, which resolves credentials, applies the
    approvals gate and writes the audit row exactly as it does for a person
    pressing Run. Despite its path that endpoint was never agent-specific — it
    already took ``targetType: JOB | WORKFLOW``.

    **An approval FAILS this node rather than parking the run.** Parking a graph
    mid-flight means persisting where it got to, which means a checkpointer,
    which means a second answer to "what has this run already done" — and when
    the two disagree the cost is a destructive step performed twice. The message
    names the approval so an operator can decide and re-run. That is a
    deliberate first position: parking is the better experience, and it is a
    durability project rather than a node.
    """
    config = settings()
    if not context.tenant_id:
        raise NodeFailed(node.id, "no tenant on this run, so no automation can be started")

    args = {
        key: refs.resolve(str(value), context.scope, where=node.id)
        for key, value in node.args.items()
    }
    base = config.core_base_url.rstrip("/")
    headers = {"X-Internal-Token": config.core_internal_token}

    try:
        started = httpx.post(
            f"{base}/internal/agent/dispatch",
            json={
                "tenantId": context.tenant_id,
                "actor": context.actor,
                "targetType": node.target,
                "targetId": node.target_id,
                "inputs": args,
            },
            headers=headers,
            timeout=30.0,
        )
    except httpx.HTTPError as exc:
        raise NodeFailed(node.id, f"could not reach the control plane: {exc}") from exc

    if started.status_code >= 400:
        raise NodeFailed(node.id, _control_plane_error(started))

    dispatched = started.json()
    if dispatched.get("mode") == "APPROVAL":
        name = dispatched.get("targetName") or node.target_id
        raise NodeFailed(
            node.id,
            f"{name} needs a human approval (#{dispatched.get('approvalId')}) before it "
            f"can run. Approve it and start this workflow again — a workflow cannot "
            f"wait for a person mid-run.",
        )

    run_id = dispatched.get("runId")
    if not run_id:
        raise NodeFailed(node.id, "the control plane started nothing and did not say why")

    deadline = time.monotonic() + config.job_timeout_seconds
    while True:
        if time.monotonic() > deadline:
            # The run is NOT cancelled. It may well finish; this node simply
            # stops waiting, and says exactly that rather than implying the
            # automation was stopped.
            raise NodeFailed(
                node.id,
                f"run {run_id} had not finished after "
                f"{int(config.job_timeout_seconds)}s. It is still going — this node "
                f"stopped waiting, it did not cancel it.",
            )
        time.sleep(config.job_poll_interval_seconds)
        try:
            polled = httpx.get(
                f"{base}/internal/agent/runs/{run_id}",
                params={"tenantId": context.tenant_id},
                headers=headers,
                timeout=30.0,
            )
        except httpx.HTTPError as exc:
            # One unreachable poll is not a failed automation. Keep waiting —
            # the deadline above is what ends this, not a single lost packet.
            log.warning("Poll of run %s failed, retrying: %s", run_id, exc)
            continue
        if polled.status_code >= 400:
            raise NodeFailed(node.id, _control_plane_error(polled))

        state = polled.json()
        if not state.get("terminal"):
            continue
        status = state.get("status", "UNKNOWN")
        if status != "SUCCEEDED":
            # The automation's OWN error, not a wrapper around it: an operator
            # reading the workflow's log needs what the job actually said.
            raise NodeFailed(
                node.id,
                f"{state.get('targetName') or run_id} {status.lower()}: "
                f"{state.get('error') or 'no reason recorded'}",
            )
        return {"status": status, "output": state.get("log") or "", "runId": run_id}


def _control_plane_error(response: httpx.Response) -> str:
    """core-service's own message, not a status code an operator cannot act on."""
    try:
        body = response.json()
        return str(body.get("message") or body.get("error") or response.text)
    except Exception:  # noqa: BLE001
        return f"the control plane answered {response.status_code}"


def run_http(node: Node, context: NodeContext) -> dict[str, Any]:
    """One HTTP call. Contributes ``status``, ``body``.

    A non-2xx is returned rather than raised: an API answering 404 is often the
    *answer* a workflow is asking for, and a node that cannot observe it cannot
    branch on it. A transport failure — DNS, TLS, timeout — is a different
    thing and does fail the node.
    """
    url = refs.resolve(node.url, context.scope, where=node.id)
    body = refs.resolve(node.body, context.scope, where=node.id)
    headers = {
        key: refs.resolve(value, context.scope, where=node.id) or ""
        for key, value in node.headers.items()
    }
    try:
        response = httpx.request(
            node.method.upper(), url or "", headers=headers, content=body,
            timeout=HTTP_TIMEOUT_SECONDS, follow_redirects=True,
        )
    except httpx.HTTPError as exc:
        raise NodeFailed(node.id, f"could not reach {url}: {exc}") from exc

    text = response.text
    if len(text) > HTTP_MAX_BODY_CHARS:
        text = text[:HTTP_MAX_BODY_CHARS] + "\n… response truncated …"
    return {"status": response.status_code, "body": text}



def _run_incidents(node: Node, context: NodeContext, config: Any) -> dict[str, Any]:
    """The ``incidents`` source: what is open right now. Contributes ``incidents``.

    Same plane as the timeline — the platform's own record, no vendor
    credential, scoped by the service that answers rather than by the caller —
    but a different service, because alert-service sits in front of the incident
    engine and the console is otherwise its only consumer.

    No window. "What is open" is a question about now; a 24-hour filter on it
    would silently hide the incident that has been burning since Tuesday, which
    is exactly the one a routing decision is most needed for.
    """
    if not context.project_id:
        raise NodeFailed(
            node.id,
            "no project on this run. Incident visibility is resolved from the monitoring "
            "sources a project owns, so without one the answer would be empty rather than "
            "wrong — which reads like a quiet estate.",
        )

    base = config.alert_base_url.rstrip("/")
    try:
        response = httpx.get(
            f"{base}/internal/incidents",
            params={"tenantId": context.tenant_id, "projectId": context.project_id},
            headers={"X-Internal-Token": config.core_internal_token},
            timeout=HTTP_TIMEOUT_SECONDS,
        )
    except httpx.HTTPError as exc:
        raise NodeFailed(node.id, f"could not reach alert-service: {exc}") from exc

    if response.status_code != 200:
        raise NodeFailed(
            node.id,
            f"alert-service answered {response.status_code} for the incident list",
        )

    body = response.json()
    rows = body.get("incidents") or []

    # Rendered as text for the same reason the timeline is: the consumer is a
    # model reading a narrative, and nested JSON costs tokens and reads worse.
    # The machine-readable trailer is kept so a scope can be enumerated from it.
    lines = [
        f"OPEN INCIDENTS ({body.get('incident_count', len(rows))})",
        f"tenant={body.get('tenant_id')} project={body.get('project_id')}",
    ]
    if body.get("truncated"):
        # Said out loud. A list silently cut is a coverage claim over things
        # nobody was shown — see TRUNCATION.md.
        lines.append("TRUNCATED=true the engine returned at least as many as were asked for")
    lines.append("")
    for row in rows:
        lines.append(
            "INCIDENT id={id} severity={severity} status={status} assignee={assignee} "
            "services={services} alerts={alerts} started={started}".format(
                id=row.get("id"),
                severity=row.get("severity"),
                status=row.get("status"),
                assignee=row.get("assignee") or "unassigned",
                services=",".join(row.get("services") or []) or "-",
                alerts=row.get("alertCount"),
                started=row.get("startedAt"),
            )
        )
    lines.append("")
    lines.append("JSON " + json.dumps(body, default=str))

    return {"incidents": "\n".join(lines)}


def run_platform(node: Node, context: NodeContext) -> dict[str, Any]:
    """Reads the WORKSPACE's own history. Contributes ``timeline``, ``summary``.

    **The node that makes this an agentic platform rather than a script
    catalog.** Every other gathering node reaches a vendor: boto3 for AWS, Graph
    for Microsoft 365. Those answer questions about that vendor, and an estate
    running three of them needs three agents that cannot see each other's
    evidence.

    This one reads what AutoOps already holds — the automations that ran in this
    project, what they did, which failed and whether they failed together, and
    which changes are parked waiting for a human. That evidence is the same
    shape whether the customer runs AWS, on-premises VMware, or both, because
    all of it was automated through one control plane.

    **It needs no customer credential**, which is why it can exist at all. There
    is no cloud account to connect and no key to rotate: the call is
    core-service asking its own database on behalf of the tenant this run
    belongs to. The internal token authorises the question; it does not widen
    the answer, which stays scoped to (tenant, project) on every query.

    Rendered as text rather than handed over as JSON. The consumer is a model
    reading a narrative, and a wall of nested objects costs tokens and reads
    worse than the same facts one event per line.
    """
    config = settings()
    if not context.tenant_id:
        raise NodeFailed(node.id, "no tenant on this run, so there is no workspace to read")
    if node.source == "incidents":
        return _run_incidents(node, context, config)
    if not context.project_id:
        raise NodeFailed(
            node.id,
            "no project on this run. The timeline is scoped to one project, because "
            "'what happened here' is a question about a workspace and not about a tenant.",
        )

    window = node.window_hours
    if isinstance(window, str):
        window = refs.resolve(window, context.scope, where=node.id)
    try:
        window = int(window)
    except (TypeError, ValueError):
        raise NodeFailed(node.id, f"window {window!r} is not a number of hours") from None
    if not 1 <= window <= 168:
        # Re-checked here because a referenced window is only knowable now. The
        # ceiling is an honesty guard: a model handed a month of history will
        # find a correlation in it, because in a month something always
        # happened before something else.
        raise NodeFailed(node.id, f"a {window}h window is outside the allowed 1-168")

    base = config.core_base_url.rstrip("/")
    try:
        response = httpx.get(
            f"{base}/internal/platform/timeline",
            params={
                "tenantId": context.tenant_id,
                "projectId": context.project_id,
                "windowHours": window,
            },
            headers={"X-Internal-Token": config.core_internal_token},
            timeout=HTTP_TIMEOUT_SECONDS,
        )
    except httpx.HTTPError as exc:
        raise NodeFailed(node.id, f"could not reach the control plane: {exc}") from exc

    if response.status_code != 200:
        raise NodeFailed(
            node.id,
            f"the control plane refused the timeline request: HTTP {response.status_code}",
        )

    payload = response.json()
    return {"timeline": _render_timeline(payload), "summary": _render_summary(payload)}


def _render_timeline(payload: dict[str, Any]) -> str:
    """The events, one per line, oldest first.

    Oldest first is the opposite of every list in the console and correct here:
    this is read as a narrative, and a cause precedes its effect. A model given
    newest-first has to reverse it mentally before it can reason about order,
    and sometimes does not.
    """
    events = payload.get("events") or []
    if not events:
        # The useful form of nothing. "No events" reads as a failed lookup;
        # naming the window makes it a finding.
        return (
            f"No automation ran and no approval was raised in this project in the last "
            f"{payload.get('windowHours', '?')} hours."
        )

    lines = []
    for event in events:
        detail = event.get("detail") or ""
        lines.append(
            "at=%s minutes_ago=%s kind=%s what=%s outcome=%s by=%s%s"
            % (
                event.get("at"),
                event.get("minutesAgo"),
                event.get("kind"),
                event.get("what"),
                event.get("outcome"),
                event.get("actor"),
                f" detail={detail}" if detail else "",
            )
        )
    return NEWLINE.join(lines)


def _render_summary(payload: dict[str, Any]) -> str:
    """Counts the automation computed, so nothing downstream has to tally."""
    repeats = payload.get("repeatedFailures") or {}
    lines = [
        "window_hours=%s since=%s" % (payload.get("windowHours"), payload.get("since")),
        "events_total=%s runs_total=%s" % (payload.get("eventsTotal"), payload.get("runsTotal")),
        "runs_failed=%s runs_succeeded=%s runs_in_flight=%s"
        % (payload.get("runsFailed"), payload.get("runsSucceeded"), payload.get("runsInFlight")),
    ]
    if repeats:
        # The distinction that carries most of triage: one failure is an
        # incident, the same automation failing four times is a broken
        # automation.
        lines.append(
            "automations_that_failed_more_than_once=%s"
            % ", ".join(f"{name} x{count}" for name, count in repeats.items())
        )
    else:
        lines.append("automations_that_failed_more_than_once=none")
    if payload.get("truncated"):
        lines.append(
            "truncated=yes (the window holds more than this call returned; narrow it "
            "before reading this as everything that happened)"
        )
    return NEWLINE.join(lines)

def run_template(node: Node, context: NodeContext) -> dict[str, Any]:
    """String assembly between nodes. Contributes ``text``."""
    return {"text": refs.resolve(node.template, context.scope, where=node.id) or ""}


def run_condition(node: Node, context: NodeContext) -> dict[str, Any]:
    """A branch. Contributes ``result`` — the string "true" or "false".

    Equality against a literal, and nothing more. An expression language here
    would be the single feature that makes a workflow's behaviour unreadable
    from its canvas, and every branch anyone has actually needed is this.
    """
    left = refs.resolve(node.when, context.scope, where=node.id)
    matched = (left or "") == (node.equals or "")
    return {"result": "true" if matched else "false", "value": left}


def run_end(node: Node, context: NodeContext) -> dict[str, Any]:
    """Collects the run's declared outputs by reference."""
    outputs: dict[str, Any] = {}
    for declared in node.outputs:
        name = declared.get("variable")
        if not name:
            continue
        source = declared.get("from") or declared.get("value")
        outputs[name] = refs.resolve(str(source), context.scope, where=node.id)
    return outputs


EXECUTORS = {
    NodeType.START: run_start,
    NodeType.LLM: run_llm,
    NodeType.JOB: run_job,
    NodeType.HTTP: run_http,
    NodeType.PLATFORM: run_platform,
    NodeType.TEMPLATE: run_template,
    NodeType.CONDITION: run_condition,
    NodeType.END: run_end,
}
