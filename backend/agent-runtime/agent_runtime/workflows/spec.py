"""What a workflow IS, on the wire.

A workflow is a directed graph of typed nodes plus a declared input form. It
carries no vendor's schema: this is our own contract, and it is deliberately
small enough to read in one sitting.

**Why this shape.** The three workflows this replaces were, in Dify's own DSL,
``start -> llm -> end`` — three nodes and two edges each. The engine behind
them shipped a full application platform, a second database and a second
credential store to run a prompt against a model. The shape below is what those
workflows actually needed, with room for the branching and HTTP steps the next
ones will.

**Variable references use ``{{#node.field#}}``.** Not ``{{Name}}`` — core-service
already uses that form to substitute a run's inputs into a JOB STEP before
execution, and a workflow body passing through that substitution would have its
own references eaten. The two syntaxes cannot collide, which is the point.
"""

from __future__ import annotations

from enum import StrEnum
from typing import Any, Literal

from pydantic import BaseModel, Field, field_validator, model_validator

#: Bumped when a field changes meaning rather than when one is added. Java
#: sends it back on every run so a definition written by a newer console cannot
#: be silently misread by an older runtime.
SPEC_VERSION = 1


class NodeType(StrEnum):
    """The node kinds this build can execute.

    Closed on purpose. An unknown type is refused at parse time rather than
    skipped — a workflow that quietly omits a step is one that reports success
    having not done the work, which is the failure this platform can least
    afford.
    """

    START = "start"
    LLM = "llm"
    #: Reads the WORKSPACE's own history — the automations that ran here, what
    #: they did, what failed, what is parked waiting for a human.
    #:
    #: The only evidence source in the platform that needs no customer
    #: credential, because the data is already the platform's. Every other node
    #: that gathers anything reaches a vendor and is therefore bounded by which
    #: vendor a customer runs; this one is the same for an AWS estate, an
    #: on-premises VMware one, or both at once.
    PLATFORM = "platform"
    #: Runs an AUTOMATION — a job, or another workflow — through core-service.
    #: This is the node that makes a workflow able to DO something rather than
    #: only decide something. Everything it can reach (scripts, PowerShell,
    #: SSH, Terraform, cloud accounts) is reached by asking the control plane,
    #: which keeps the credential resolution, the approvals gate and the audit
    #: trail exactly where they already were.
    JOB = "job"
    HTTP = "http"
    TEMPLATE = "template"
    CONDITION = "condition"
    END = "end"


class InputType(StrEnum):
    """The form controls a workflow's start node can ask for.

    Mirrors what ``NativeInputValidator`` on the Java side already validates, so
    the form a customer fills in and the form the runtime reads cannot drift.
    """

    TEXT = "text"
    PARAGRAPH = "paragraph"
    SELECT = "select"
    NUMBER = "number"
    BOOLEAN = "boolean"


class InputField(BaseModel):
    """One field of the workflow's published input form."""

    variable: str
    label: str
    type: InputType = InputType.TEXT
    required: bool = False
    default: Any = None
    options: list[str] = Field(default_factory=list)
    max_length: int | None = Field(default=None, alias="maxLength")
    hint: str | None = None

    model_config = {"populate_by_name": True}

    @field_validator("variable")
    @classmethod
    def _identifier(cls, value: str) -> str:
        # The variable becomes a reference name inside prompts. Anything that
        # is not an identifier makes `{{#start.x#}}` ambiguous to parse.
        if not value.isidentifier():
            raise ValueError(f"input variable {value!r} is not a valid identifier")
        return value

    @model_validator(mode="after")
    def _options_only_for_select(self) -> InputField:
        if self.type is InputType.SELECT and not self.options:
            raise ValueError(f"select input {self.variable!r} declares no options")
        return self


class PromptMessage(BaseModel):
    """One message of an LLM node's prompt template."""

    role: Literal["system", "user", "assistant"] = "user"
    text: str


class Node(BaseModel):
    """A single step.

    One model rather than a discriminated union of six: the fields are few, the
    validator below enforces which are required per type, and a flat shape is
    what the console's canvas serialises most naturally.
    """

    id: str
    type: NodeType
    title: str | None = None

    #: LLM
    prompt: list[PromptMessage] = Field(default_factory=list)
    #: Overrides the run's default model. Absent means "the workflow's model".
    model: str | None = None
    temperature: float | None = None
    max_tokens: int | None = Field(default=None, alias="maxTokens")

    #: JOB — what to run, and what to pass it.
    #: `target` is JOB or WORKFLOW; `targetId` is the row in that table. Both
    #: are ids rather than names on purpose: a name is a thing a customer
    #: renames, and a workflow that silently stops running the automation it
    #: was built against is worse than one that fails on a missing id.
    target: Literal["JOB", "WORKFLOW"] = "JOB"
    target_id: int | None = Field(default=None, alias="targetId")
    #: Values for the target's own input form. References resolve first, so a
    #: node can pass another node's output into the automation it starts.
    args: dict[str, Any] = Field(default_factory=dict)

    #: PLATFORM — how far back to read. Hours, because the question this node
    #: answers is always "what happened around the time of X", and a window
    #: wide enough to make everything proximate makes correlation worthless.
    #: An int, or a ``{{#start.Field#}}`` reference resolved at run time — the
    #: window is the single most important knob for correlation, so it has to
    #: be answerable by the operator asking the question rather than fixed when
    #: the workflow was authored.
    window_hours: int | str = Field(default=24, alias="windowHours")

    #: WHICH platform record to read. Defaults to the activity timeline, so
    #: every workflow authored before this existed keeps its meaning.
    #:
    #: ``timeline``   what ran in this project and what it returned (core)
    #: ``incidents``  what is open right now (alert-service)
    #:
    #: A source rather than a new node type because the two are the same shape
    #: of thing — the platform's own record, no vendor credential, scoped to
    #: (tenant, project) by the service that answers. A second node type would
    #: duplicate the window handling, the scoping and the failure messages.
    source: str = "timeline"

    #: HTTP
    method: str = "GET"
    url: str | None = None
    headers: dict[str, str] = Field(default_factory=dict)
    body: str | None = None

    #: TEMPLATE — a string built from references, for reshaping between nodes.
    template: str | None = None

    #: CONDITION — evaluated against the scope; `when` is a reference that must
    #: equal `equals`. Deliberately not an expression language: a workflow that
    #: can run arbitrary code is a workflow whose blast radius cannot be read
    #: off the canvas.
    when: str | None = None
    equals: str | None = None

    #: END
    outputs: list[dict[str, Any]] = Field(default_factory=list)

    model_config = {"populate_by_name": True}

    @field_validator("id")
    @classmethod
    def _identifier(cls, value: str) -> str:
        if not value.isidentifier():
            raise ValueError(f"node id {value!r} is not a valid identifier")
        return value

    @model_validator(mode="after")
    def _required_fields_per_type(self) -> Node:
        if self.type is NodeType.LLM and not self.prompt:
            raise ValueError(f"llm node {self.id!r} has no prompt")
        if self.type is NodeType.JOB and not self.target_id:
            raise ValueError(f"job node {self.id!r} names no automation to run")
        if self.type is NodeType.HTTP and not self.url:
            raise ValueError(f"http node {self.id!r} has no url")
        if self.type is NodeType.TEMPLATE and self.template is None:
            raise ValueError(f"template node {self.id!r} has no template")
        if self.type is NodeType.CONDITION and not self.when:
            raise ValueError(f"condition node {self.id!r} has no `when`")
        if self.type is NodeType.PLATFORM and self.source not in ("timeline", "incidents"):
            raise ValueError(
                f"platform node {self.id!r} asks for source {self.source!r}; "
                f"supported: timeline, incidents"
            )
        if (
            self.type is NodeType.PLATFORM
            and self.source == "timeline"
            and isinstance(self.window_hours, int)
            and not 1 <= self.window_hours <= 168
        ):
            # A literal is checked here, at save time. A reference can only be
            # checked once it resolves, which run_platform does.
            raise ValueError(
                f"platform node {self.id!r} asks for a {self.window_hours}h window, "
                f"which is outside 1-168"
            )
        return self

    @property
    def label(self) -> str:
        """What an operator sees in the run log."""
        return self.title or self.id


class Edge(BaseModel):
    """A transition. ``branch`` selects which arm of a condition it belongs to."""

    source: str
    target: str
    #: "true" / "false" on the two arms of a condition; None elsewhere.
    branch: str | None = None


class WorkflowSpec(BaseModel):
    """A whole workflow, validated.

    Validation is structural and happens once, here, before anything executes.
    A graph that cannot run — a missing start, an edge to nowhere, a cycle — is
    a definition error and belongs in the author's face at save time, not as a
    half-finished run at 3am.
    """

    version: int = SPEC_VERSION
    name: str | None = None
    description: str | None = None
    inputs: list[InputField] = Field(default_factory=list)
    nodes: list[Node]
    edges: list[Edge] = Field(default_factory=list)

    @model_validator(mode="after")
    def _graph_is_runnable(self) -> WorkflowSpec:
        ids = [node.id for node in self.nodes]
        duplicates = {i for i in ids if ids.count(i) > 1}
        if duplicates:
            raise ValueError(f"duplicate node ids: {sorted(duplicates)}")

        starts = [n for n in self.nodes if n.type is NodeType.START]
        if len(starts) != 1:
            raise ValueError(f"a workflow needs exactly one start node, found {len(starts)}")
        if not any(n.type is NodeType.END for n in self.nodes):
            raise ValueError("a workflow needs at least one end node")

        known = set(ids)
        for edge in self.edges:
            missing = {edge.source, edge.target} - known
            if missing:
                raise ValueError(f"edge references unknown node(s): {sorted(missing)}")

        self._reject_cycles()
        return self

    def _reject_cycles(self) -> None:
        """No loops, in this version.

        Refused rather than bounded by a recursion limit. A limit turns a cycle
        into a run that burns tokens for a while and then fails with a message
        about recursion — which tells the author nothing about their graph. This
        names the nodes involved, at save time.
        """
        outgoing: dict[str, list[str]] = {n.id: [] for n in self.nodes}
        for edge in self.edges:
            outgoing[edge.source].append(edge.target)

        WHITE, GREY, BLACK = 0, 1, 2
        colour = dict.fromkeys(outgoing, WHITE)

        def visit(node: str, path: list[str]) -> None:
            colour[node] = GREY
            for nxt in outgoing[node]:
                if colour[nxt] is GREY:
                    cycle = path[path.index(nxt):] if nxt in path else [nxt]
                    raise ValueError(f"the graph contains a cycle: {' -> '.join([*cycle, nxt])}")
                if colour[nxt] is WHITE:
                    visit(nxt, [*path, nxt])
            colour[node] = BLACK

        for node in list(outgoing):
            if colour[node] is WHITE:
                visit(node, [node])

    @property
    def start(self) -> Node:
        return next(n for n in self.nodes if n.type is NodeType.START)

    def node(self, node_id: str) -> Node:
        return next(n for n in self.nodes if n.id == node_id)

    def successors(self, node_id: str, branch: str | None = None) -> list[str]:
        """Targets reachable from a node, optionally along one condition arm."""
        return [
            e.target
            for e in self.edges
            if e.source == node_id and (branch is None or e.branch in (None, branch))
        ]
