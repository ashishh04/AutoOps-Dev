"""The native workflow runtime.

The reference case at the bottom is the real ``Incident Postmortem Writer``,
ported node-for-node out of the Dify DSL export in the repo root. If it runs
here, the engine covers what the thing it replaces was actually being used for.
"""

from __future__ import annotations

import pytest

from agent_runtime.workflows import refs
from agent_runtime.workflows.engine import WorkflowFailed, run_workflow
from agent_runtime.workflows.nodes import NodeContext
from agent_runtime.workflows.spec import WorkflowSpec


class FakeReply:
    def __init__(self, text: str) -> None:
        self.content = text
        self.usage_metadata = {"input_tokens": 11, "output_tokens": 22}


class FakeModel:
    """Records what it was asked, so a test can assert on the rendered prompt."""

    def __init__(self, text: str = "a postmortem") -> None:
        self.text = text
        self.calls: list[list] = []

    def invoke(self, messages, config=None):  # noqa: ANN001, ARG002
        self.calls.append(messages)
        return FakeReply(self.text)


def context_factory(model: FakeModel):
    def build(state):
        return NodeContext(
            scope=state.get("scope", {}),
            model_factory=lambda **kwargs: model,  # noqa: ARG005
            callbacks=[],
        )

    return build


def run(spec_dict: dict, inputs: dict, model: FakeModel | None = None):
    model = model or FakeModel()
    spec = WorkflowSpec.model_validate(spec_dict)
    return run_workflow(spec, inputs, context_factory(model)), model


LINEAR = {
    "nodes": [
        {"id": "start", "type": "start"},
        {"id": "writer", "type": "llm", "title": "Writer",
         "prompt": [{"role": "user", "text": "Notes: {{#start.notes#}}"}]},
        {"id": "end", "type": "end",
         "outputs": [{"variable": "report", "from": "{{#writer.text#}}"}]},
    ],
    "edges": [{"source": "start", "target": "writer"},
              {"source": "writer", "target": "end"}],
}


# ------------------------------------------------------------------ running --

def test_runs_a_linear_graph_and_returns_declared_outputs():
    outputs, model = run(LINEAR, {"notes": "the database filled up"})

    assert outputs == {"report": "a postmortem"}
    # The input reached the prompt through the ordinary reference form, not a
    # second syntax reserved for "the form".
    assert model.calls[0][0].content == "Notes: the database filled up"


def test_reports_progress_for_every_node_in_order():
    seen = []
    spec = WorkflowSpec.model_validate(LINEAR)
    run_workflow(spec, {"notes": "x"}, context_factory(FakeModel()),
                 progress=lambda nid, title, done, ms, failed: seen.append((nid, done, failed)))

    # Each node reports twice: starting, then finished. This is the shape the
    # Dify bridge streamed, so core-service's live run screen is unchanged.
    assert seen == [
        ("start", False, False), ("start", True, False),
        ("writer", False, False), ("writer", True, False),
        ("end", False, False), ("end", True, False),
    ]


def test_a_condition_takes_only_the_matching_branch():
    spec = {
        "nodes": [
            {"id": "start", "type": "start"},
            {"id": "gate", "type": "condition",
             "when": "{{#start.sev#}}", "equals": "P1"},
            {"id": "urgent", "type": "template", "template": "PAGE"},
            {"id": "calm", "type": "template", "template": "TICKET"},
            {"id": "end", "type": "end",
             "outputs": [{"variable": "action", "from": "{{#urgent.text#}}"}]},
        ],
        "edges": [
            {"source": "start", "target": "gate"},
            {"source": "gate", "target": "urgent", "branch": "true"},
            {"source": "gate", "target": "calm", "branch": "false"},
            {"source": "urgent", "target": "end"},
        ],
    }
    outputs, _ = run(spec, {"sev": "P1"})
    assert outputs == {"action": "PAGE"}


def test_a_condition_arm_with_no_edge_ends_the_run():
    """A router that pages on P1 and does nothing otherwise is the common case.

    The first version mapped "__end__" in the edge dict but never RETURNED it
    from the router, so LangGraph raised `KeyError: 'false'` and failed the run.
    Every unit test declared both arms, so none of them noticed — a real run on
    severity=P3 did.
    """
    spec = {
        "nodes": [
            {"id": "start", "type": "start"},
            {"id": "gate", "type": "condition", "when": "{{#start.sev#}}", "equals": "P1"},
            {"id": "page", "type": "template", "template": "PAGE"},
            {"id": "end", "type": "end",
             "outputs": [{"variable": "action", "from": "{{#page.text#}}"}]},
        ],
        # Only the TRUE arm exists.
        "edges": [
            {"source": "start", "target": "gate"},
            {"source": "gate", "target": "page", "branch": "true"},
            {"source": "page", "target": "end"},
        ],
    }

    paged, _ = run(spec, {"sev": "P1"})
    assert paged == {"action": "PAGE"}

    # P3 takes an arm that goes nowhere: the run ENDS, cleanly, with no outputs.
    quiet, _ = run(spec, {"sev": "P3"})
    assert quiet == {}


def test_a_failing_node_stops_the_run():
    # Carrying on past a failed node produces a report built on a gap that the
    # outcome does not reveal.
    spec = {
        "nodes": [
            {"id": "start", "type": "start"},
            {"id": "writer", "type": "llm",
             "prompt": [{"role": "user", "text": "{{#start.notes#}}"}]},
            {"id": "end", "type": "end", "outputs": []},
        ],
        "edges": [{"source": "start", "target": "writer"},
                  {"source": "writer", "target": "end"}],
    }

    class Exploding(FakeModel):
        def invoke(self, messages, config=None):  # noqa: ANN001, ARG002
            raise RuntimeError("402 insufficient balance")

    with pytest.raises(WorkflowFailed, match="402 insufficient balance"):
        run(spec, {"notes": "x"}, Exploding())


# --------------------------------------------------------------- references --

def test_an_unresolved_reference_fails_rather_than_rendering_empty():
    # Dify substituted nothing, which is how a prompt becomes "Severity:\n" and
    # the model writes a confident report about an incident it was told nothing
    # about.
    spec = dict(LINEAR)
    spec["nodes"] = [
        {"id": "start", "type": "start"},
        {"id": "writer", "type": "llm",
         "prompt": [{"role": "user", "text": "{{#start.notez#}}"}]},
        {"id": "end", "type": "end", "outputs": []},
    ]
    with pytest.raises(WorkflowFailed) as failure:
        run(spec, {"notes": "typo in the reference"})
    assert "notez" in str(failure.value)
    assert "produced: notes" in str(failure.value)


def test_a_forward_reference_is_caught_without_running():
    spec = WorkflowSpec.model_validate({
        "nodes": [
            {"id": "start", "type": "start"},
            {"id": "a", "type": "template", "template": "{{#b.text#}}"},
            {"id": "b", "type": "template", "template": "later"},
            {"id": "end", "type": "end", "outputs": []},
        ],
        "edges": [],
    })
    problems = refs.validate_against(spec)
    assert any("reads {{#b.text#}} before" in p for p in problems)


def test_booleans_render_for_readers_that_are_not_python():
    scope = {"start": {"execute": True, "dry": False}}
    assert refs.resolve("{{#start.execute#}}/{{#start.dry#}}", scope, where="x") == "true/false"


# --------------------------------------------------------------- validation --

@pytest.mark.parametrize(
    ("broken", "message"),
    [
        ({"nodes": [{"id": "end", "type": "end"}]}, "exactly one start"),
        ({"nodes": [{"id": "start", "type": "start"}]}, "at least one end"),
        (
            {"nodes": [{"id": "start", "type": "start"}, {"id": "end", "type": "end"}],
             "edges": [{"source": "start", "target": "ghost"}]},
            "unknown node",
        ),
        (
            {"nodes": [{"id": "start", "type": "start"},
                       {"id": "a", "type": "template", "template": "x"},
                       {"id": "b", "type": "template", "template": "y"},
                       {"id": "end", "type": "end"}],
             "edges": [{"source": "a", "target": "b"}, {"source": "b", "target": "a"}]},
            "cycle",
        ),
        (
            {"nodes": [{"id": "start", "type": "start"},
                       {"id": "w", "type": "llm"},
                       {"id": "end", "type": "end"}]},
            "no prompt",
        ),
    ],
)
def test_an_unrunnable_graph_is_refused_at_parse_time(broken, message):
    # At save time, naming the problem — not at 3am as a half-finished run.
    with pytest.raises(ValueError, match=message):
        WorkflowSpec.model_validate(broken)


def test_an_unknown_node_type_is_refused_rather_than_skipped():
    # A workflow that quietly omits a step reports success having not done the
    # work, which is the failure this platform can least afford.
    with pytest.raises(ValueError):
        WorkflowSpec.model_validate({
            "nodes": [{"id": "start", "type": "start"},
                      {"id": "x", "type": "quantum"},
                      {"id": "end", "type": "end"}],
        })


# ------------------------------------------------------- the ported workflow --

#: `Incident_Postmortem_Writer.yml`, node for node. Four inputs, one LLM call,
#: one declared output — which is all the Dify app ever was.
POSTMORTEM = {
    "name": "Incident Postmortem Writer",
    "inputs": [
        {"variable": "incident_notes", "label": "Incident notes or timeline",
         "type": "paragraph", "required": True, "maxLength": 8000},
        {"variable": "severity", "label": "Severity", "type": "select",
         "required": True, "default": "P1", "options": ["P1", "P2", "P3", "P4"]},
        {"variable": "audience", "label": "Written for", "type": "select",
         "required": True, "default": "Internal engineering",
         "options": ["Internal engineering", "Customer-facing"]},
        {"variable": "service_name", "label": "Affected service", "type": "text"},
    ],
    "nodes": [
        {"id": "start", "type": "start", "title": "Start"},
        {"id": "writer", "type": "llm", "title": "Postmortem Writer",
         "temperature": 0.2,
         "prompt": [
             {"role": "system", "text": "You write post-incident reviews."},
             {"role": "user", "text": (
                 "Severity: {{#start.severity#}}\n"
                 "Affected service: {{#start.service_name#}}\n"
                 "Written for: {{#start.audience#}}\n\n"
                 "Incident notes:\n{{#start.incident_notes#}}"
             )},
         ]},
        {"id": "end", "type": "end", "title": "End",
         "outputs": [{"variable": "final_report", "from": "{{#writer.text#}}"}]},
    ],
    "edges": [{"source": "start", "target": "writer"},
              {"source": "writer", "target": "end"}],
}


def test_the_ported_dify_workflow_runs_here():
    model = FakeModel("## Summary\nThe payments API was down for 41 minutes.")
    outputs, _ = run(POSTMORTEM, {
        "incident_notes": "14:02 alerts fired. 14:43 recovered.",
        "severity": "P1",
        "audience": "Internal engineering",
        "service_name": "Payments API",
    }, model)

    assert outputs["final_report"].startswith("## Summary")
    system, user = model.calls[0]
    assert system.content == "You write post-incident reviews."
    assert "Severity: P1" in user.content
    assert "Affected service: Payments API" in user.content
    assert "14:02 alerts fired" in user.content


def test_the_ported_workflow_declares_the_same_form():
    spec = WorkflowSpec.model_validate(POSTMORTEM)
    assert [i.variable for i in spec.inputs] == [
        "incident_notes", "severity", "audience", "service_name",
    ]
    severity = next(i for i in spec.inputs if i.variable == "severity")
    assert severity.options == ["P1", "P2", "P3", "P4"]
    assert severity.required is True
