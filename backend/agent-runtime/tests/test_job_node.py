"""The node that lets a workflow RUN something.

Before this existed the platform had two halves that could not reach each
other: workflows could think (llm) and call an arbitrary URL (http), while
everything AutoOps can actually perform — the script library, PowerShell, SSH,
Terraform, cloud accounts, the approvals gate — lived behind jobs and was
unreachable from a workflow. A postmortem writer could describe a full disk and
not free it.

These cases pin the contract with core-service and, more importantly, the three
refusals: an approval is not silently skipped, a failed automation is not
reported as a successful node, and a node that gives up waiting does not claim
to have cancelled anything.
"""

from __future__ import annotations

import pytest

from agent_runtime.workflows.engine import WorkflowFailed, run_workflow
from agent_runtime.workflows.nodes import NodeContext
from agent_runtime.workflows.spec import WorkflowSpec


class FakeResponse:
    def __init__(self, payload, status_code=200):
        self._payload = payload
        self.status_code = status_code
        self.text = str(payload)

    def json(self):
        return self._payload


def spec_with_job(**node_over):
    node = {"id": "patch", "type": "job", "title": "Patch the fleet",
            "target": "JOB", "targetId": 42}
    node.update(node_over)
    return {
        "nodes": [
            {"id": "start", "type": "start"},
            node,
            {"id": "end", "type": "end",
             "outputs": [{"variable": "result", "from": "{{#patch.status#}}"}]},
        ],
        "edges": [{"source": "start", "target": "patch"},
                  {"source": "patch", "target": "end"}],
    }


def context_factory(state):
    return NodeContext(scope=state.get("scope", {}), model_factory=None,
                       callbacks=[], tenant_id="acme", actor="workflow:7")


def run(spec_dict, inputs=None):
    return run_workflow(WorkflowSpec.model_validate(spec_dict), inputs or {}, context_factory)


@pytest.fixture(autouse=True)
def no_sleeping(monkeypatch):
    monkeypatch.setattr("agent_runtime.workflows.nodes.time.sleep", lambda _s: None)


# ------------------------------------------------------------------ the happy path --

def test_dispatches_the_automation_and_returns_its_outcome(monkeypatch):
    sent = {}

    def post(url, json=None, headers=None, timeout=None):  # noqa: A002, ARG001
        sent["url"], sent["body"] = url, json
        return FakeResponse({"mode": "RUN", "runId": 900, "targetName": "Patch"})

    def get(url, params=None, headers=None, timeout=None):  # noqa: ARG001
        return FakeResponse({"status": "SUCCEEDED", "terminal": True,
                             "targetName": "Patch", "log": "2 steps completed."})

    monkeypatch.setattr("agent_runtime.workflows.nodes.httpx.post", post)
    monkeypatch.setattr("agent_runtime.workflows.nodes.httpx.get", get)

    assert run(spec_with_job()) == {"result": "SUCCEEDED"}
    # Dispatched through the control plane — NOT executed here. This service
    # holds no credential and opens no shell.
    assert sent["url"].endswith("/internal/agent/dispatch")
    assert sent["body"]["tenantId"] == "acme"
    assert sent["body"]["targetType"] == "JOB"
    assert sent["body"]["targetId"] == 42


def test_passes_another_nodes_output_into_the_automation(monkeypatch):
    sent = {}
    monkeypatch.setattr("agent_runtime.workflows.nodes.httpx.post",
                        lambda url, json=None, **kw: (sent.update(body=json),  # noqa: A002
                                                      FakeResponse({"mode": "RUN", "runId": 1}))[1])
    monkeypatch.setattr("agent_runtime.workflows.nodes.httpx.get",
                        lambda url, **kw: FakeResponse(
                            {"status": "SUCCEEDED", "terminal": True, "log": ""}))

    run(spec_with_job(args={"Region": "{{#start.region#}}"}), {"region": "me-central-1"})

    # The whole point of a graph: a node's inputs can come from what ran before.
    assert sent["body"]["inputs"] == {"Region": "me-central-1"}


def test_the_output_is_readable_by_later_nodes(monkeypatch):
    monkeypatch.setattr("agent_runtime.workflows.nodes.httpx.post",
                        lambda url, **kw: FakeResponse({"mode": "RUN", "runId": 5}))
    monkeypatch.setattr("agent_runtime.workflows.nodes.httpx.get",
                        lambda url, **kw: FakeResponse(
                            {"status": "SUCCEEDED", "terminal": True, "log": "disk now 41% free"}))

    spec = spec_with_job()
    spec["nodes"][2]["outputs"] = [{"variable": "result", "from": "{{#patch.output#}}"}]
    assert run(spec) == {"result": "disk now 41% free"}


def test_waits_for_a_run_that_is_still_going(monkeypatch):
    polls = iter([
        FakeResponse({"status": "RUNNING", "terminal": False}),
        FakeResponse({"status": "RUNNING", "terminal": False}),
        FakeResponse({"status": "SUCCEEDED", "terminal": True, "log": "ok"}),
    ])
    monkeypatch.setattr("agent_runtime.workflows.nodes.httpx.post",
                        lambda url, **kw: FakeResponse({"mode": "RUN", "runId": 3}))
    monkeypatch.setattr("agent_runtime.workflows.nodes.httpx.get", lambda url, **kw: next(polls))

    assert run(spec_with_job()) == {"result": "SUCCEEDED"}


# ---------------------------------------------------------------- the three refusals --

def test_an_approval_stops_the_workflow_and_names_it(monkeypatch):
    """Parking mid-graph would need a checkpointer — a second answer to "what
    has this run already done". When those two disagree the cost is a
    destructive step performed twice. Refusing is the deliberate position."""
    monkeypatch.setattr("agent_runtime.workflows.nodes.httpx.post",
                        lambda url, **kw: FakeResponse(
                            {"mode": "APPROVAL", "approvalId": 77, "targetName": "Prod Deploy"}))

    with pytest.raises(WorkflowFailed) as failure:
        run(spec_with_job())
    message = str(failure.value)
    assert "Prod Deploy" in message
    assert "#77" in message
    # Tells the operator what to DO, not just that something went wrong.
    assert "Approve it" in message


def test_a_failed_automation_fails_the_node_with_its_own_error(monkeypatch):
    # Not "the job node failed" — what the JOB said. That is what an operator
    # reading the workflow log needs.
    monkeypatch.setattr("agent_runtime.workflows.nodes.httpx.post",
                        lambda url, **kw: FakeResponse({"mode": "RUN", "runId": 8}))
    monkeypatch.setattr("agent_runtime.workflows.nodes.httpx.get",
                        lambda url, **kw: FakeResponse(
                            {"status": "FAILED", "terminal": True, "targetName": "Patch",
                             "error": "PowerShell script exited with code 1"}))

    with pytest.raises(WorkflowFailed, match="exited with code 1"):
        run(spec_with_job())


def test_giving_up_waiting_does_not_claim_to_have_cancelled_anything(monkeypatch):
    # The run may well still finish. Saying "cancelled" would be a claim we
    # cannot support, about an automation that may be halfway through a change.
    monkeypatch.setattr("agent_runtime.workflows.nodes.settings",
                        lambda: _timeout_settings())
    monkeypatch.setattr("agent_runtime.workflows.nodes.httpx.post",
                        lambda url, **kw: FakeResponse({"mode": "RUN", "runId": 9}))
    monkeypatch.setattr("agent_runtime.workflows.nodes.httpx.get",
                        lambda url, **kw: FakeResponse({"status": "RUNNING", "terminal": False}))

    with pytest.raises(WorkflowFailed) as failure:
        run(spec_with_job())
    message = str(failure.value)
    assert "stopped waiting, it did not cancel it" in message


def _timeout_settings():
    from agent_runtime.app.config import settings
    real = settings()
    return real.model_copy(update={"job_timeout_seconds": -1.0})


# ----------------------------------------------------------------------- resilience --

def test_one_lost_poll_does_not_fail_a_running_automation(monkeypatch):
    import httpx as real_httpx
    calls = {"n": 0}

    def get(url, **kw):  # noqa: ARG001
        calls["n"] += 1
        if calls["n"] == 1:
            raise real_httpx.ConnectError("connection reset")
        return FakeResponse({"status": "SUCCEEDED", "terminal": True, "log": "ok"})

    monkeypatch.setattr("agent_runtime.workflows.nodes.httpx.post",
                        lambda url, **kw: FakeResponse({"mode": "RUN", "runId": 4}))
    monkeypatch.setattr("agent_runtime.workflows.nodes.httpx.get", get)

    assert run(spec_with_job()) == {"result": "SUCCEEDED"}


def test_a_control_plane_error_surfaces_its_message(monkeypatch):
    monkeypatch.setattr("agent_runtime.workflows.nodes.httpx.post",
                        lambda url, **kw: FakeResponse(
                            {"error": "job_not_found", "message": "No such job"}, 404))

    with pytest.raises(WorkflowFailed, match="No such job"):
        run(spec_with_job())


# ---------------------------------------------------------------------- validation --

def test_a_job_node_naming_no_automation_is_refused_at_parse_time():
    with pytest.raises(ValueError, match="names no automation"):
        WorkflowSpec.model_validate(spec_with_job(targetId=None))


def test_a_workflow_can_run_another_workflow():
    # targetType is JOB or WORKFLOW; core-service already dispatched both long
    # before this node existed.
    spec = WorkflowSpec.model_validate(spec_with_job(target="WORKFLOW", targetId=9225))
    assert spec.node("patch").target == "WORKFLOW"
