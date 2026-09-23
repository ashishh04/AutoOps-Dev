"""The authoring contract must describe the runtime that will execute it.

Every test here exists to fail on DRIFT rather than on a bug in
``authoring.py`` itself. The module is a description of ``spec.py``; the way it
goes wrong is not by computing something incorrectly today but by still
describing yesterday's models tomorrow. A test that only checked the current
output against a hand-written expectation would be a third copy of the same
contract and would drift alongside it.

So the assertions are all of the same shape: take what the schema claims, and
put it to the thing that actually enforces it.
"""

from __future__ import annotations

import pytest
from pydantic import ValidationError

from agent_runtime.app import authoring
from agent_runtime.app.extraction import SubjectSource
from agent_runtime.app.state import Phase
from agent_runtime.workflows.spec import InputType, Node, NodeType


# ------------------------------------------------------- the node palette ---


def test_every_node_type_the_runtime_can_execute_is_offered():
    """A node type the runtime runs and the console cannot place is invisible.

    This is the failure the whole module exists to prevent, so it is asserted
    against the enum rather than a list: adding a member to ``NodeType`` and
    forgetting the console is now a red build instead of a feature nobody finds.
    """
    offered = {n["type"] for n in authoring.node_types()}
    assert offered == {t.value for t in NodeType}


def test_every_offered_field_is_a_real_field_on_the_node_model():
    """``FIELDS`` is the one hand-written map here, so it is the one that rots.

    A renamed field would otherwise disappear from the designer silently — the
    palette would simply stop offering a control, which looks like a design
    decision rather than a bug.
    """
    for node_type, names in authoring.FIELDS.items():
        for name in names:
            assert name in Node.model_fields, f"{node_type.value}.{name} is not on Node"


def test_every_node_field_is_offered_somewhere():
    """The other direction: a field on ``Node`` that no node type offers.

    A new field added to the model for a specific node type and never added to
    ``FIELDS`` is unreachable from the console — authored workflows silently
    lack a capability the repo-authored ones have, which is exactly the
    two-tier authoring split this work is meant to close.

    ``id`` and ``type`` are excluded because the canvas owns them: an id comes
    from the node's identity on the graph and a type from which palette entry
    was dragged, so neither is a form field.
    """
    offered = {name for names in authoring.FIELDS.values() for name in names}
    structural = {"id", "type"}
    missing = set(Node.model_fields) - offered - structural
    assert not missing, f"fields on Node that no node type offers: {sorted(missing)}"


def test_the_wire_name_is_reported_where_it_differs_from_the_python_name():
    """Several fields are aliased, and writing the Python name loses the value.

    ``maxTokens``, ``targetId`` and ``windowHours`` are what a definition
    actually carries. A designer that emitted ``max_tokens`` would produce a
    node that parses, validates, and quietly runs with the default — the worst
    available failure, because nothing reports it.
    """
    by_name = {
        f["name"]: f
        for n in authoring.node_types()
        for f in n["fields"]
    }
    assert by_name["max_tokens"]["wire_name"] == "maxTokens"
    assert by_name["target_id"]["wire_name"] == "targetId"
    assert by_name["window_hours"]["wire_name"] == "windowHours"
    # And where there is no alias, the two must agree rather than be guessed.
    assert by_name["url"]["wire_name"] == "url"


# -------------------------------------------------------- required fields ---


@pytest.mark.parametrize(
    "node_type,field",
    [
        (NodeType.LLM, "prompt"),
        (NodeType.JOB, "target_id"),
        (NodeType.HTTP, "url"),
        (NodeType.TEMPLATE, "template"),
        (NodeType.CONDITION, "when"),
    ],
)
def test_a_field_the_runtime_refuses_without_is_marked_required(node_type, field):
    """Marked required here AND refused there — both halves asserted.

    The first assertion alone would pass against a schema that marked
    everything required. The second alone would pass against one that marked
    nothing. Together they pin the two to each other, which is the only claim
    worth making.
    """
    offered = next(n for n in authoring.node_types() if n["type"] == node_type.value)
    marked = next(f for f in offered["fields"] if f["name"] == field)
    assert marked["required"] is True

    filled = {name: authoring._PROBE[name] for name in authoring.FIELDS[node_type]}
    filled.pop(field)
    with pytest.raises(ValidationError):
        Node(id="probe", type=node_type, **filled)


def test_a_field_with_a_usable_default_is_not_marked_required():
    """``window_hours`` and ``source`` default to something meaningful.

    Marking them required would make the designer demand a decision the
    runtime already has a considered answer for, and every workflow in the repo
    that omits them would be un-editable in the console that is supposed to be
    able to open them.
    """
    platform = next(
        n for n in authoring.node_types() if n["type"] == NodeType.PLATFORM.value
    )
    required = {f["name"] for f in platform["fields"] if f["required"]}
    assert required == set()
    # And the runtime really does accept a bare platform node.
    assert Node(id="p", type=NodeType.PLATFORM).window_hours == 24


def test_the_probe_covers_every_offered_field():
    """A field with no probe value would raise KeyError inside ``_is_required``.

    Cheap to assert and it fails at the moment a field is added to ``FIELDS``
    rather than the first time the endpoint is called.
    """
    offered = {name for names in authoring.FIELDS.values() for name in names}
    assert offered <= set(authoring._PROBE)


# ------------------------------------------------------------ constraints ---


def test_the_published_window_bounds_are_the_ones_the_runtime_enforces():
    """The range is asserted by probing both edges and both sides of them.

    Publishing 1-168 while the validator enforces something else would put a
    form control in front of an operator that accepts values the save will
    reject — or worse, refuses values that are legal, which reads as the
    platform being arbitrary.
    """
    bounds = authoring.CONSTRAINTS["window_hours"]
    low, high = bounds["min"], bounds["max"]

    assert Node(id="p", type=NodeType.PLATFORM, windowHours=low).window_hours == low
    assert Node(id="p", type=NodeType.PLATFORM, windowHours=high).window_hours == high
    with pytest.raises(ValidationError):
        Node(id="p", type=NodeType.PLATFORM, windowHours=low - 1)
    with pytest.raises(ValidationError):
        Node(id="p", type=NodeType.PLATFORM, windowHours=high + 1)


def test_a_reference_is_accepted_where_a_number_is_accepted():
    """The window is the knob an operator most often needs to set per run.

    If the designer rendered it as a plain number box the workflow would be
    stuck with whatever was decided at authoring time. The type is published as
    ``number_or_reference`` so the console offers both, and the runtime really
    does take the reference form.
    """
    node = Node(id="p", type=NodeType.PLATFORM, windowHours="{{#start.Hours#}}")
    assert node.window_hours == "{{#start.Hours#}}"

    platform = next(
        n for n in authoring.node_types() if n["type"] == NodeType.PLATFORM.value
    )
    window = next(f for f in platform["fields"] if f["name"] == "window_hours")
    assert window["type"] == "number_or_reference"


def test_every_published_source_is_accepted_and_an_unpublished_one_is_not():
    """Both halves again: the list is complete AND it is closed."""
    options = authoring.CONSTRAINTS["source"]["options"]
    for source in options:
        assert Node(id="p", type=NodeType.PLATFORM, source=source).source == source
    with pytest.raises(ValidationError):
        Node(id="p", type=NodeType.PLATFORM, source="prometheus")


def test_every_published_target_is_accepted():
    for target in authoring.CONSTRAINTS["target"]["options"]:
        assert Node(id="j", type=NodeType.JOB, target=target, targetId=1).target == target


def test_every_input_type_the_form_renderer_knows_is_published():
    offered = authoring.schema()["workflow"]["input_types"]
    assert set(offered) == {t.value for t in InputType}


# ----------------------------------------------------------------- agents ---


def test_the_legacy_unphased_loop_is_not_offered_as_a_phase():
    """RESPOND sees every tool at once and enforces nothing.

    It exists to reproduce the pre-runtime Java loop for agents authored before
    phases existed. Offering it in a builder would let somebody produce, in two
    clicks, precisely the agent the phase kit was built to make impossible —
    including handing a gathering step a tool that can act.
    """
    offered = {p["value"] for p in authoring.agent_schema()["phases"]}
    assert Phase.RESPOND.value not in offered
    assert Phase.DONE.value not in offered
    # Everything else is a real authoring choice and must be there.
    assert offered == {
        p.value for p in Phase if p not in (Phase.RESPOND, Phase.DONE)
    }


def test_phases_are_published_in_the_order_the_graph_runs_them():
    """Order is meaning here, not presentation.

    A graph is built from the list, so ``[GATHER, TRIAGE]`` is a different agent
    from ``[TRIAGE, GATHER]``. If the console treated the palette as a set — a
    natural thing to do with a list of enum values — it would silently reorder
    somebody's agent.
    """
    published = authoring.agent_schema()["phases"]
    assert [p["ordinal"] for p in published] == list(range(len(published)))
    assert [p["value"] for p in published][:2] == [Phase.TRIAGE.value, Phase.GATHER.value]


def test_the_subject_source_form_offers_exactly_the_dataclass_fields():
    """A missing field here is a finding that can never be reaped.

    ``id_template`` is the one that matters: omit it from the form and every
    console-authored agent produces bare resource ids, which collide across
    regions — two different resources sharing one subject id is a finding
    closed by evidence about something else.
    """
    published = {f["name"] for f in authoring.agent_schema()["subject_source"]["fields"]}
    real = set(SubjectSource.__dataclass_fields__)
    assert published == real


def test_the_optional_subject_source_fields_are_the_ones_with_defaults():
    """Required-ness taken from the dataclass, not from an opinion.

    ``total_field`` in particular must stay optional: the guidance is to OMIT it
    rather than point it at anything derived from the list, and a form that
    demanded it would push every author into supplying a number that agrees
    with the list by construction — verification that verifies nothing.
    """
    fields = {f["name"]: f for f in authoring.agent_schema()["subject_source"]["fields"]}
    for name, spec in SubjectSource.__dataclass_fields__.items():
        import dataclasses

        has_default = spec.default is not dataclasses.MISSING
        assert fields[name]["required"] is not has_default, name


def test_mutating_defaults_to_true_in_the_form():
    """The safe direction of a mistake.

    An unmarked tool defaulting to non-mutating would be shown to a gathering
    phase, which is the failure ``delivered-agents-lost-mutating`` already cost
    this platform once — GATHER went blind and the agent shipped its own
    scratchpad as the report. Defaulting to mutating makes an unmarked tool go
    unused and get noticed.
    """
    tool = {f["name"]: f for f in authoring.agent_schema()["tool"]["fields"]}
    assert tool["mutating"]["default"] is True
    assert tool["mutating"]["required"] is True


# --------------------------------------------------------------- the whole --


def test_the_schema_is_json_serialisable():
    """It crosses two service boundaries before a browser sees it.

    An enum member or a dataclass leaking in would serialise here and fail in
    Java's client, a service away from the cause.
    """
    import json

    text = json.dumps(authoring.schema())
    assert json.loads(text) == authoring.schema()


def test_no_persona_or_prompt_leaks_into_the_authoring_schema():
    """This endpoint is read by a console and could end up in a browser cache.

    Agent personas must never be serialised to a customer, and a schema that
    started including example prompts would be an unremarkable-looking way for
    one to escape.
    """
    import json

    text = json.dumps(authoring.schema()).lower()
    for forbidden in ("you are ", "persona", "system_prompt"):
        assert forbidden not in text
