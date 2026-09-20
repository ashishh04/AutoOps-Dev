"""The `platform` node: the workspace reading its own history.

This is the node that makes AutoOps an agentic platform rather than a catalog
of vendor scripts, so what is pinned here is the property that makes it so:

* it needs **no customer credential** — the call is the control plane asking its
  own database, authorised by the internal token;
* it is scoped to **(tenant, project) together**, and refuses rather than
  guessing when either is absent. A timeline that silently widened to the whole
  customer would be a cross-project disclosure dressed as a feature;
* an **empty window is a finding**, not a failed lookup. "Nothing ran" is a real
  answer to "what happened here", and it has to read like one;
* a **truncated** window says so, because a capped list that reads as complete
  is how an investigation concludes that nothing else happened.
"""

from __future__ import annotations

import pytest

from agent_runtime.workflows.nodes import NodeContext, NodeFailed, run_platform
from agent_runtime.workflows.spec import Node


def node(**overrides) -> Node:
    return Node.model_validate({"id": "history", "type": "platform", **overrides})


def context(**overrides) -> NodeContext:
    base = {
        "scope": {},
        "model_factory": None,
        "callbacks": [],
        "tenant_id": "acme",
        "project_id": 9,
    }
    base.update(overrides)
    return NodeContext(**base)


class FakeResponse:
    def __init__(self, payload, status_code=200):
        self._payload = payload
        self.status_code = status_code

    def json(self):
        return self._payload


def install(monkeypatch, payload, status_code=200):
    """Captures the request the node makes and answers it."""
    seen = {}

    def fake_get(url, params=None, headers=None, timeout=None):
        seen["url"] = url
        seen["params"] = params
        seen["headers"] = headers
        return FakeResponse(payload, status_code)

    monkeypatch.setattr("agent_runtime.workflows.nodes.httpx.get", fake_get)
    return seen


TIMELINE = {
    "windowHours": 6,
    "since": "2026-09-19T08:00:00Z",
    "eventsTotal": 3,
    "runsTotal": 2,
    "runsFailed": 1,
    "runsSucceeded": 1,
    "runsInFlight": 0,
    "repeatedFailures": {},
    "truncated": False,
    "events": [
        {"at": "2026-09-19T09:00:00Z", "minutesAgo": 120, "kind": "RUN",
         "what": "workflow Nightly Backup", "outcome": "SUCCEEDED", "actor": "scheduler",
         "detail": "42s"},
        {"at": "2026-09-19T09:05:00Z", "minutesAgo": 115, "kind": "RUN",
         "what": "job Patch Tuesday", "outcome": "FAILED", "actor": "scheduler",
         "detail": "12s · connection refused"},
        {"at": "2026-09-19T09:06:00Z", "minutesAgo": 114, "kind": "APPROVAL",
         "what": "workflow EBS Reclaim", "outcome": "PENDING", "actor": "p.nair",
         "detail": "awaiting a decision"},
    ],
}


def test_it_asks_the_control_plane_scoped_to_tenant_and_project(monkeypatch):
    seen = install(monkeypatch, TIMELINE)

    run_platform(node(windowHours=6), context())

    assert seen["url"].endswith("/internal/platform/timeline")
    assert seen["params"] == {"tenantId": "acme", "projectId": 9, "windowHours": 6}
    # The internal token is the whole credential. No customer key is involved,
    # which is the reason this node works for every estate.
    assert "X-Internal-Token" in seen["headers"]


def test_the_timeline_is_rendered_oldest_first_as_a_narrative(monkeypatch):
    install(monkeypatch, TIMELINE)

    result = run_platform(node(), context())

    lines = result["timeline"].splitlines()
    assert len(lines) == 3
    assert "Nightly Backup" in lines[0], "oldest event must come first"
    assert "Patch Tuesday" in lines[1]
    assert "EBS Reclaim" in lines[2]
    assert "outcome=FAILED" in lines[1]
    assert "connection refused" in lines[1]


def test_the_summary_quotes_counts_the_control_plane_computed(monkeypatch):
    install(monkeypatch, TIMELINE)

    summary = run_platform(node(), context())["summary"]

    assert "runs_failed=1" in summary
    assert "runs_succeeded=1" in summary
    assert "automations_that_failed_more_than_once=none" in summary


def test_an_automation_failing_repeatedly_is_separated_from_a_one_off(monkeypatch):
    """One failure is an incident; the same one four times is a broken automation."""
    install(monkeypatch, dict(TIMELINE, repeatedFailures={"Patch Tuesday": 4}))

    summary = run_platform(node(), context())["summary"]

    assert "automations_that_failed_more_than_once=Patch Tuesday x4" in summary


def test_an_empty_window_is_a_finding_not_a_failed_lookup(monkeypatch):
    install(monkeypatch, dict(TIMELINE, events=[], eventsTotal=0, runsTotal=0))

    result = run_platform(node(windowHours=6), context())

    assert "No automation ran" in result["timeline"]
    # The window has to travel with the statement, or "nothing happened" is
    # unfalsifiable.
    assert "6 hours" in result["timeline"]


def test_a_truncated_window_never_reads_as_everything_that_happened(monkeypatch):
    install(monkeypatch, dict(TIMELINE, truncated=True))

    assert "truncated=yes" in run_platform(node(), context())["summary"]


@pytest.mark.parametrize(
    "missing, expected",
    [
        ({"tenant_id": None}, "no workspace to read"),
        ({"project_id": None}, "scoped to one project"),
    ],
)
def test_it_refuses_rather_than_widening_when_the_scope_is_incomplete(
    monkeypatch, missing, expected
):
    install(monkeypatch, TIMELINE)

    with pytest.raises(NodeFailed) as raised:
        run_platform(node(), context(**missing))

    assert expected in str(raised.value)


def test_a_refusal_from_the_control_plane_fails_the_node_with_its_status(monkeypatch):
    install(monkeypatch, {}, status_code=403)

    with pytest.raises(NodeFailed) as raised:
        run_platform(node(), context())

    assert "403" in str(raised.value)


@pytest.mark.parametrize("hours", [0, 169])
def test_a_window_outside_the_allowed_range_is_refused_at_parse_time(hours):
    """A window wide enough to make everything proximate makes correlation worthless."""
    with pytest.raises(ValueError):
        node(windowHours=hours)
