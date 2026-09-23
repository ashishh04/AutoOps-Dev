"""The authoring contract, derived from the models rather than described twice.

**The problem this exists to prevent.** A designer UI needs to know which node
types exist, which fields each one takes, which are required and what the
constraints are. The obvious way to give it that is a list in the frontend —
and this codebase already carries a scar from exactly that shape of decision.
Node lists have been hand-coupled across the backend before; the display name
and the graph ref were compared as though they were the same string and refused
every verdict in the platform for a day. A palette hardcoded in JavaScript is
the same bug waiting: the runtime gains a field, the console never offers it,
and nobody finds out until somebody asks why a workflow authored in the console
cannot do what one authored in the repo can.

So the palette is DERIVED. Everything below is read out of
:mod:`agent_runtime.workflows.spec` and the agent spec at call time. Add a node
type or a field there and the console offers it on the next page load. Delete
one and the console stops offering it. There is no second copy to update.

**What it deliberately does not do.** It does not describe layout, ordering or
wording beyond a short help string. That is design, it belongs in the console,
and a backend that dictates it makes every copy change a deploy.

**The one thing here that is hand-written, and why.** ``FIELDS`` maps each node
type to the field names that apply to it. Pydantic knows every field on
``Node``; it does not know that ``prompt`` is meaningless on an ``http`` node,
because ``Node`` is one flat model covering all eight types. That relationship
is real knowledge and it lives here rather than being guessed — with a test
asserting every name in it is a real field, so a rename breaks the build rather
than silently dropping a field from the palette.
"""

from __future__ import annotations

from typing import Any

from pydantic import ValidationError

from agent_runtime.app.extraction import SubjectSource
from agent_runtime.app.state import Phase
from agent_runtime.workflows.spec import InputType, Node, NodeType

#: Which of ``Node``'s fields apply to which node type.
#:
#: ``Node`` is one flat model for all eight types, so this is the only
#: information a schema reader cannot recover from the model itself. Every name
#: is asserted against the model in the tests — a rename fails the build rather
#: than quietly removing a control from the designer.
FIELDS: dict[NodeType, tuple[str, ...]] = {
    NodeType.START: ("title",),
    NodeType.LLM: ("title", "prompt", "model", "temperature", "max_tokens"),
    NodeType.PLATFORM: ("title", "source", "window_hours"),
    NodeType.JOB: ("title", "target", "target_id", "args"),
    NodeType.HTTP: ("title", "method", "url", "headers", "body"),
    NodeType.TEMPLATE: ("title", "template"),
    NodeType.CONDITION: ("title", "when", "equals"),
    NodeType.END: ("title", "outputs"),
}

#: What each node CONTRIBUTES to the scope, so the designer can offer
#: ``{{#node.field#}}`` references that resolve instead of ones that do not.
#:
#: A reference to a field a node never produces fails at run time with a message
#: nobody reads until an incident. Offering only the real ones turns that into a
#: dropdown.
CONTRIBUTES: dict[NodeType, tuple[str, ...]] = {
    NodeType.START: (),          # the start node contributes its declared inputs
    NodeType.LLM: ("text",),
    NodeType.PLATFORM: ("timeline", "summary", "incidents"),
    NodeType.JOB: ("output", "status"),
    NodeType.HTTP: ("body", "status"),
    NodeType.TEMPLATE: ("text",),
    NodeType.CONDITION: (),      # routes, produces nothing
    NodeType.END: (),
}

#: One line per node type, for the palette. Longer prose belongs in the model's
#: own docstrings, which a developer reads; this is what a provider reads while
#: choosing.
SUMMARY: dict[NodeType, str] = {
    NodeType.START: "Where the run begins, and the inputs the operator supplies.",
    NodeType.LLM: "Ask a model. Produces text.",
    NodeType.PLATFORM: (
        "Read the platform's own record — this project's activity, or the incidents "
        "open right now. The only evidence source that needs no customer credential."
    ),
    NodeType.JOB: (
        "Run an automation through the control plane, so credentials, approvals and "
        "the audit trail stay where they already are."
    ),
    NodeType.HTTP: "Call an external endpoint.",
    NodeType.TEMPLATE: "Reshape values from earlier nodes into one string.",
    NodeType.CONDITION: "Route on a value. Not an expression language, deliberately.",
    NodeType.END: "Where the run finishes, and what it hands back.",
}

#: Constraints a form should enforce before the runtime has to refuse.
#:
#: Duplicated nowhere: each is asserted in the tests against the validator that
#: actually enforces it, so a limit changed in one place fails in the other.
CONSTRAINTS: dict[str, dict[str, Any]] = {
    "window_hours": {
        "min": 1,
        "max": 168,
        "why": (
            "A model handed a month of history will find a correlation in it, because "
            "over a month something always happened before something else."
        ),
    },
    "source": {
        "options": ["timeline", "incidents"],
        "why": "Which platform record to read. Unknown sources are refused at parse time.",
    },
    "target": {"options": ["JOB", "WORKFLOW"]},
    "method": {"options": ["GET", "POST", "PUT", "PATCH", "DELETE"]},
}


#: A value each field will accept, used only to probe the validator below.
#:
#: Nothing here is a default or a suggestion — the console never sees these.
#: They exist so a node can be constructed with one field missing and the
#: runtime asked whether it minds.
_PROBE: dict[str, Any] = {
    "title": "Probe",
    "prompt": [{"role": "user", "text": "x"}],
    "model": "probe-model",
    "temperature": 0.2,
    "max_tokens": 256,
    "target": "JOB",
    "target_id": 1,
    "args": {},
    "source": "timeline",
    "window_hours": 24,
    "method": "GET",
    "url": "https://example.invalid/probe",
    "headers": {},
    "body": "x",
    "template": "x",
    "when": "{{#probe.text#}}",
    "equals": "x",
    "outputs": [],
}


def _is_required(node_type: NodeType, field: str) -> bool:
    """Whether leaving this field out makes the runtime refuse the node.

    **Asked rather than answered.** Pydantic says no field is required, because
    ``Node`` is one flat model covering eight types and a field mandatory on one
    must be optional on the others. The real rule lives in
    ``Node._required_fields_per_type`` — and writing it out a second time here
    is precisely the duplication this module exists to avoid, with the added
    hazard that the copy would be invisible when it went stale: the designer
    would stop marking ``url`` required and the only symptom would be a workflow
    that saves cleanly and fails on its first run.

    So this builds a node with every field of its type filled, removes one, and
    reports whether the runtime objects. The answer comes from the validator
    that will actually judge the saved definition, which means it cannot
    disagree with it.
    """
    filled = {name: _PROBE[name] for name in FIELDS[node_type]}
    filled.pop(field)
    try:
        Node(id="probe", type=node_type, **filled)
    except ValidationError:
        return True
    return False


def _python_type(annotation: Any) -> str:
    """A form-friendly name for a field's type.

    Coarse on purpose. The console needs to know "render a number box" rather
    than the exact union; anything finer would encode Pydantic's type algebra
    into a UI contract and break on the next model change.
    """
    text = str(annotation)
    if "bool" in text:
        return "boolean"
    if "int" in text and "str" in text:
        # e.g. window_hours: int | str — a literal OR a {{#start.Field#}} ref.
        return "number_or_reference"
    if "int" in text or "float" in text:
        return "number"
    if "dict" in text:
        return "map"
    if "list" in text:
        return "list"
    return "text"


def node_types() -> list[dict[str, Any]]:
    """Every node type this build can execute, with its real fields."""
    model_fields = Node.model_fields
    out: list[dict[str, Any]] = []

    for node_type in NodeType:
        fields: list[dict[str, Any]] = []
        for name in FIELDS[node_type]:
            info = model_fields[name]
            field: dict[str, Any] = {
                "name": name,
                # The wire name, which is what a definition actually carries.
                # Several differ from the Python name — windowHours, targetId,
                # maxLength — and a designer writing the Python name produces a
                # definition the runtime silently ignores.
                "wire_name": info.alias or name,
                "type": _python_type(info.annotation),
                # From the validator that will actually judge the saved
                # definition, not from Pydantic — see _is_required.
                "required": _is_required(node_type, name),
            }
            if name in CONSTRAINTS:
                field.update(CONSTRAINTS[name])
            fields.append(field)

        out.append({
            "type": node_type.value,
            "summary": SUMMARY[node_type],
            "fields": fields,
            "contributes": list(CONTRIBUTES[node_type]),
        })
    return out


def agent_schema() -> dict[str, Any]:
    """What a console-authored agent has to be able to say.

    The gap this closes is specific. The console can already author a persona, a
    model and a tool allow-list — and cannot express the three things that make
    an agent's findings trustworthy: which phases it runs, whether each tool
    changes anything, and where its tools' output names the subjects it
    examined. An agent missing the last one produces findings that can never be
    reaped, because nothing it claims is checkable.
    """
    return {
        # Ordered, and the console must PRESERVE that order: a graph is built
        # from the list, so [GATHER, TRIAGE] is a different agent from
        # [TRIAGE, GATHER], not the same set written differently.
        #
        # Two members of the enum are excluded, for different reasons. DONE is a
        # terminal marker rather than a phase anybody authors. RESPOND is the
        # un-phased legacy loop that sees every tool at once and enforces
        # nothing — offering it in a builder would let somebody produce, in two
        # clicks, exactly the agent the phase kit exists to make impossible.
        "phases": [
            {"value": phase.value, "ordinal": ordinal}
            for ordinal, phase in enumerate(
                p for p in Phase if p not in (Phase.DONE, Phase.RESPOND)
            )
        ],
        "tool": {
            "fields": [
                {"name": "type", "type": "text", "options": ["WORKFLOW", "JOB"],
                 "required": True},
                {"name": "ref", "type": "text", "required": True,
                 "why": "The catalog name, not the delivered id. An id is tenant-local."},
                {"name": "mutating", "type": "boolean", "required": True, "default": True,
                 "why": (
                     "Whether running this changes anything. Decides which phases may see "
                     "it — a gathering phase is never shown a tool that can act. Defaults "
                     "to TRUE so an unmarked tool goes unused and is noticed, rather than "
                     "reaching the phase that must not see it."
                 )},
            ],
        },
        "subject_source": {
            "why": (
                "Where this tool's output names the things the run examined. Without it "
                "the agent's findings cannot be reaped, because nothing it claims to have "
                "covered is checkable."
            ),
            "fields": [
                {"name": "subject_kind", "type": "text", "required": True,
                 "options": ["cloud_resource", "service", "alert_rule", "principal",
                             "account"],
                 "why": (
                     "What these ids identify. A subject is something that can still exist "
                     "on the next run and be re-examined — an alarm rule qualifies, a log "
                     "event does not."
                 )},
                {"name": "items", "type": "text", "required": True,
                 "why": (
                     "Dotted path to the list in the tool's JSON output. One declaration "
                     "per list: a tool returning three lists needs three."
                 )},
                {"name": "id_template", "type": "text", "required": True,
                 "example": "{region}/{volume_id}",
                 "why": (
                     "How to build the id. A TEMPLATE because resource ids are not globally "
                     "unique — vol- and sg- are region-scoped, an IAM user is "
                     "account-scoped — and a bare id collapses two regions' resources into "
                     "one subject. Placeholders resolve against the item first, then the "
                     "document."
                 )},
                {"name": "total_field", "type": "text", "required": False,
                 "why": (
                     "Dotted path to the count the SOURCE reported. Omit it rather than "
                     "pointing it at anything derived from the list — a total that equals "
                     "the list by construction agrees with it in every case including the "
                     "broken ones, so it reads as verification while providing none."
                 )},
                {"name": "truncated_field", "type": "text", "required": False,
                 "why": (
                     "Dotted path to a flag the tool sets when it shortened its own output. "
                     "The only signal that exists for truncation upstream of this service."
                 )},
            ],
        },
        # Named so the console can show which fields a SubjectSource really has
        # rather than trusting the list above to stay in step.
        "subject_source_fields": [f.name for f in SubjectSource.__dataclass_fields__.values()],
    }


def schema() -> dict[str, Any]:
    """The whole authoring contract, as one document."""
    return {
        "workflow": {
            "node_types": node_types(),
            "input_types": [t.value for t in InputType],
            "reference_syntax": "{{#nodeId.field#}}",
        },
        "agent": agent_schema(),
    }
