"""The workflow HTTP surface, through a real FastAPI client.

These go through the app rather than calling ``stream_run`` directly, because
the thing being pinned is the contract Java consumes: the auth gate, the NDJSON
framing, and the guarantee that every terminating path emits exactly one
``done``.
"""

from __future__ import annotations

import json

import pytest
from fastapi.testclient import TestClient

from agent_runtime.app.config import settings
from agent_runtime.app.main import app

TOKEN = settings().internal_token
AUTH = {"X-Internal-Token": TOKEN}

MODEL = {
    "model": "gpt-4o-mini",
    "vendor": "OPENAI",
    "credentials": {"apiKey": "sk-not-used-these-tests-never-call-out"},
}

LINEAR = {
    "name": "Incident Postmortem Writer",
    "inputs": [
        {"variable": "notes", "label": "Incident notes", "type": "paragraph",
         "required": True},
        {"variable": "severity", "label": "Severity", "type": "select",
         "options": ["P1", "P2"], "default": "P1"},
    ],
    "nodes": [
        {"id": "start", "type": "start"},
        {"id": "writer", "type": "llm", "title": "Postmortem Writer",
         "prompt": [{"role": "user", "text": "{{#start.notes#}} ({{#start.severity#}})"}]},
        {"id": "end", "type": "end",
         "outputs": [{"variable": "final_report", "from": "{{#writer.text#}}"}]},
    ],
    "edges": [{"source": "start", "target": "writer"},
              {"source": "writer", "target": "end"}],
}


@pytest.fixture
def client() -> TestClient:
    return TestClient(app)


def events(response) -> list[dict]:
    return [json.loads(line) for line in response.text.splitlines() if line.strip()]


def post_run(client: TestClient, definition: dict, inputs: dict | None = None):
    return client.post("/v1/workflows/run", headers=AUTH, json={
        "runId": 4242, "tenantId": "acme", "definition": definition,
        "inputs": inputs or {}, "model": MODEL,
    })


# ------------------------------------------------------------------- the gate --

def test_the_endpoints_are_internal_only(client: TestClient):
    # api-gateway does not route here at all; the only caller is core-service
    # holding the shared secret.
    assert client.post("/v1/workflows/run", json={}).status_code == 401
    assert client.post("/v1/workflows/inspect", json={}).status_code == 401


def test_health_publishes_the_definition_version(client: TestClient):
    # So a deployment can be checked against the definitions it is being sent
    # without running one.
    body = client.get("/health").json()
    assert body["workflow_spec_version"] >= 1


# ------------------------------------------------------------------- inspect --

def test_inspect_returns_the_form_without_running_anything(client: TestClient):
    body = client.post("/v1/workflows/inspect", headers=AUTH, json=LINEAR).json()

    assert body["valid"] is True
    assert body["nodeCount"] == 3
    assert [f["variable"] for f in body["inputs"]] == ["notes", "severity"]
    severity = body["inputs"][1]
    assert severity["options"] == ["P1", "P2"]
    assert severity["default"] == "P1"


def test_inspect_reports_a_broken_definition_rather_than_raising(client: TestClient):
    body = client.post("/v1/workflows/inspect", headers=AUTH,
                       json={"nodes": [{"id": "end", "type": "end"}]}).json()

    assert body["valid"] is False
    assert "start" in body["error"]
    assert body["inputs"] == []


# ----------------------------------------------------------------- the stream --

def test_a_broken_definition_still_emits_exactly_one_done(client: TestClient):
    # A stream that ends WITHOUT a done event means the process died. Java has
    # to be able to tell that apart from a failed run, because "it failed" and
    # "we do not know what happened to a run that may have had side effects"
    # call for different actions.
    response = post_run(client, {"nodes": [{"id": "end", "type": "end"}]})

    assert response.status_code == 200
    done = [e for e in events(response) if e["event"] == "done"]
    assert len(done) == 1
    assert done[0]["success"] is False
    assert "cannot run as defined" in done[0]["error"]


def test_a_validation_failure_never_echoes_the_definition(client: TestClient):
    """The run log is customer-visible; a provider's workflow design is not.

    ``str(ValidationError)`` embeds ``input_value=...``, which for a workflow is
    the WHOLE definition. That message lands in ``runs.log``. Under the
    provider-authored model a customer holds a reference and never the design,
    so this would leak it — quietly, in one error path, and only for workflows
    that were already broken, which is exactly when nobody is looking.
    """
    secret = "PROPRIETARY-PROMPT-DO-NOT-LEAK"
    response = post_run(client, {
        "nodes": [
            {"id": "writer", "type": "llm",
             "prompt": [{"role": "system", "text": secret}]},
            {"id": "end", "type": "end"},
        ],
    })

    done = events(response)[-1]
    assert done["success"] is False
    # The reason survives...
    assert "exactly one start node" in done["error"]
    # ...the design does not.
    assert secret not in done["error"]
    assert "input_value" not in done["error"]
    assert "errors.pydantic.dev" not in done["error"]


def test_inspect_does_not_echo_the_definition_either(client: TestClient):
    secret = "PROPRIETARY-PROMPT-DO-NOT-LEAK"
    body = client.post("/v1/workflows/inspect", headers=AUTH, json={
        "nodes": [{"id": "writer", "type": "llm",
                   "prompt": [{"role": "system", "text": secret}]}],
    }).json()

    assert body["valid"] is False
    assert secret not in body["error"]
    assert "input_value" not in body["error"]


def test_a_definition_from_a_newer_console_is_refused_by_name(client: TestClient):
    response = post_run(client, {**LINEAR, "version": 99})

    done = events(response)[-1]
    assert done["success"] is False
    assert "version 99" in done["error"]
    # Names the correct remedy: upgrading the runtime, not editing the workflow.
    assert "Upgrade the runtime" in done["error"]


def test_an_impossible_reference_is_caught_before_any_model_call(client: TestClient):
    # Finding this after three model calls costs tokens to learn nothing.
    broken = {
        "nodes": [
            {"id": "start", "type": "start"},
            {"id": "a", "type": "template", "template": "{{#later.text#}}"},
            {"id": "later", "type": "template", "template": "x"},
            {"id": "end", "type": "end", "outputs": []},
        ],
        "edges": [],
    }
    done = events(post_run(client, broken))[-1]

    assert done["success"] is False
    assert "not available" in done["error"]
    assert "later" in done["error"]


def test_a_failing_model_call_is_a_result_not_an_http_error(client: TestClient, monkeypatch):
    # 200 with success=false. A 5xx would make Java guess at what happened to a
    # run that may already have had side effects.
    def explode(*args, **kwargs):  # noqa: ANN002, ANN003, ARG001
        raise RuntimeError("402 insufficient balance")

    monkeypatch.setattr("agent_runtime.workflows.api.build_model", explode)

    response = post_run(client, LINEAR, {"notes": "db filled up", "severity": "P1"})

    assert response.status_code == 200
    done = events(response)[-1]
    assert done["event"] == "done"
    assert done["success"] is False


def test_a_successful_run_streams_a_node_event_per_node_then_done(
        client: TestClient, monkeypatch):
    class Reply:
        content = "## Summary\nThe database filled up."
        usage_metadata = {"input_tokens": 9, "output_tokens": 30}

    class Model:
        def invoke(self, messages, config=None):  # noqa: ANN001, ARG002
            Model.seen = messages
            return Reply()

    monkeypatch.setattr("agent_runtime.workflows.api.build_model",
                        lambda descriptor, max_tokens=None: Model())  # noqa: ARG005

    response = post_run(client, LINEAR, {"notes": "db filled up", "severity": "P1"})
    stream = events(response)

    nodes = [e for e in stream if e["event"] == "node"]
    # Two events per node — starting, then finished — which is the shape the
    # Dify bridge streamed and core-service's callback already consumes.
    assert [(e["nodeId"], e["finished"]) for e in nodes] == [
        ("start", False), ("start", True),
        ("writer", False), ("writer", True),
        ("end", False), ("end", True),
    ]
    assert all(e["failed"] is False for e in nodes)
    assert all(e["elapsedMs"] is not None for e in nodes if e["finished"])

    done = stream[-1]
    assert done == {"event": "done", "success": True, "totalNodes": 3,
                    "outputs": {"final_report": "## Summary\nThe database filled up."},
                    "traceId": done["traceId"]}
    # The inputs reached the prompt through the ordinary reference form.
    assert Model.seen[0].content == "db filled up (P1)"


def test_every_line_of_the_stream_is_standalone_json(client: TestClient):
    # NDJSON: one object per line, no framing library needed on either side.
    response = post_run(client, {"nodes": [{"id": "end", "type": "end"}]})

    assert response.headers["content-type"].startswith("application/x-ndjson")
    for line in response.text.splitlines():
        if line.strip():
            assert isinstance(json.loads(line), dict)
