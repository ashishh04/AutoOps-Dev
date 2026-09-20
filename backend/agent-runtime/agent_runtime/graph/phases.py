"""The phase kit: the nodes an agent composes into a graph.

Each node does one job with one prompt and one narrowed toolbox, then hands
control back. None of them loop internally, and none of them call a tool
themselves — reaching a tool call is a *boundary*: the node records what the
model asked for and the graph ends, so Java can dispatch it through the
machinery that knows about approvals, audit rows and tenant scoping.

That is what keeps this a reducer. A node is a pure-ish function of
``(state, context)``: it may talk to a model, but it never talks to the
customer's infrastructure and never persists anything. Run the same state
through it twice and you get the same shape of answer.

**On context.** Every node assembles its own view rather than inheriting a
growing transcript. GATHER needs the real message history, because tool calls
and their results have to stay paired for the vendor to accept the turn — but
it only needs *its own* turns. HYPOTHESIZE and REPORT never see raw transcript
at all; they see the evidence ledger, which is the same information with the
provenance attached and the noise removed. A report node that can see raw log
output will paraphrase it; one that can only see cited observations cannot.
"""

from __future__ import annotations

from typing import Any, Literal, TypedDict

from langchain_core.messages import AIMessage, HumanMessage, SystemMessage, ToolMessage
from pydantic import BaseModel, Field

from agent_runtime.app import evidence
from agent_runtime.app.state import (
    AgentState,
    Finding,
    Message,
    Phase,
    PlannedAction,
    ToolCallWire,
)
from agent_runtime.graph import prompts
from agent_runtime.graph.context import RunContext

#: How many times GATHER may be re-entered before HYPOTHESIZE has to conclude
#: with what it has. Bounded here rather than on Java's step budget: a graph
#: that oscillates would burn the run's whole allowance and report "out of
#: steps", when the useful answer is "I could not narrow this down".
MAX_GATHER_ROUNDS = 3


class GraphState(TypedDict):
    """What flows between nodes. ``ctx`` is per-call and never serialised."""

    state: AgentState
    ctx: RunContext


# ------------------------------------------------------- structured out ---


class TriageOut(BaseModel):
    """The routing decision, not a piece of analysis."""

    restated: str = Field(description="The request in one sentence.")
    observations_needed: list[str] = Field(
        default_factory=list, description="What must be observed to answer it."
    )
    can_proceed: bool = Field(
        description="False when the available tools cannot answer this request at all."
    )
    reason: str = Field(
        default="", description="When can_proceed is false, what is missing and what would do it."
    )


class FindingOut(BaseModel):
    """One conclusion drawn from the observations, and what it rests on.

    ``subject_*`` and ``finding_type`` are what make a finding survive across
    runs: they are hashed into an idempotency key, so tomorrow's run recognises
    today's finding instead of filing it again. Optional, because a conclusion
    about the estate as a whole genuinely has no single subject — and a model
    forced to invent one would produce a key that is stable and wrong.
    """

    summary: str
    severity: Literal["info", "warning", "critical", "unknown"] = "info"
    cites: list[int] = Field(default_factory=list)
    subject_kind: str = Field(
        default="",
        description="What the finding is about: cloud_resource, service, alert_rule, "
                    "principal, account. Empty when it is about the estate as a whole.",
    )
    subject_id: str = Field(
        default="",
        description="The identifier of that thing, exactly as the observation gave it — "
                    "vol-0a1b2c3d, an ARN, a UPN, a bucket name. Never a description.",
    )
    finding_type: str = Field(
        default="",
        description="A short stable slug for this KIND of finding, reused verbatim across "
                    "runs: idle_resource, public_bucket, stale_credential, repeated_failure.",
    )


class HypothesisOut(BaseModel):
    """The findings drawn from the evidence, and whether more is needed.

    The docstring is not decoration: LangChain turns it into the tool
    description Bedrock's Converse API requires, and Converse rejects an empty
    one outright. OpenAI and Anthropic accept it, so a missing docstring here
    fails on exactly one vendor and passes every local test.
    """

    findings: list[FindingOut] = Field(default_factory=list)
    need_more: bool = Field(
        default=False,
        description="True ONLY if a specific further read-only observation would change a conclusion.",
    )
    collect: str = Field(default="", description="What to collect, when need_more is true.")


class ActionOut(BaseModel):
    """One state-changing step, with its blast radius and its way back."""

    tool: str
    arguments: dict[str, Any] = Field(default_factory=dict)
    intent: str = ""
    blast_radius: str = ""
    rollback: str = ""
    cites: list[int] = Field(default_factory=list)


class PlanOut(BaseModel):
    """The actions proposed for a human to approve, or the case for none."""

    actions: list[ActionOut] = Field(default_factory=list)
    do_nothing_reason: str = Field(
        default="", description="When no action is proposed, why that is the right answer."
    )


# ------------------------------------------------------------ plumbing ---


def _system(ctx: RunContext, phase_prompt: str, *, evidence_rule: bool = False) -> SystemMessage:
    """Preamble, then the agent's own persona, then the phase's job.

    Order is deliberate. The preamble states what is true about the runtime and
    must not be overridable by a persona. The persona is the product. The phase
    prompt comes last because it is the instruction actually being followed
    right now, and recency is the cheapest emphasis there is.
    """
    parts = [prompts.RUNTIME_PREAMBLE]
    if ctx.persona.strip():
        parts.append("---\n\n" + ctx.persona.strip())
    parts.append("---\n\n" + phase_prompt)
    if evidence_rule:
        parts.append("---\n\n" + prompts.EVIDENCE_RULE)
    if ctx.toolbox.unavailable:
        # Named rather than omitted silently. An agent whose only relevant tool
        # was deleted should say "I cannot do that any more", not quietly do
        # something else and report success.
        listed = "\n".join(f"- {reason}" for reason in ctx.toolbox.unavailable)
        parts.append("---\n\nUnavailable in this run:\n" + listed)
    return SystemMessage("\n\n".join(parts))


def _gather_history(state: AgentState) -> list[Any]:
    """GATHER's own turns, converted for the vendor.

    Only the assistant/tool pairs matter here — the earlier phases' prose would
    just re-argue conclusions the model is not being asked to revisit, and a
    tool call whose result is missing makes every vendor reject the turn.
    """
    history: list[Any] = []
    for message in state.messages:
        if message.role == "assistant" and message.tool_calls:
            history.append(
                AIMessage(
                    content=message.text or "",
                    tool_calls=[
                        {"name": call.name, "args": call.arguments, "id": call.id}
                        for call in message.tool_calls
                    ],
                )
            )
        elif message.role == "tool_results":
            for result in message.tool_results:
                history.append(
                    ToolMessage(
                        content=result.content or "(no output)",
                        tool_call_id=result.call_id,
                        status="error" if not result.ok else "success",
                    )
                )
    return history


def _capture(state: AgentState, reply: AIMessage) -> list[ToolCallWire]:
    """Records an assistant turn and returns the tool calls it asked for."""
    calls = [
        ToolCallWire(id=call["id"], name=call["name"], arguments=call.get("args") or {})
        for call in (reply.tool_calls or [])
    ]
    state.messages.append(
        Message(role="assistant", text=_text_of(reply), tool_calls=calls)
    )
    return calls


def _text_of(reply: AIMessage) -> str:
    """Flattens a reply's content, which is a list of blocks on some vendors."""
    content = reply.content
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts = [
            block.get("text", "")
            for block in content
            if isinstance(block, dict) and block.get("type") == "text"
        ]
        return "\n".join(part for part in parts if part)
    return str(content or "")


def _park(state: AgentState, calls: list[ToolCallWire]) -> GraphStateUpdate:
    """Ends the graph holding tool calls for Java to dispatch."""
    state.pending_tool_calls = calls
    return {"state": state}


GraphStateUpdate = dict[str, Any]


# --------------------------------------------------------------- nodes ---


def triage(graph_state: GraphState) -> GraphStateUpdate:
    """Decides the shape of the work before any of it happens.

    Cheap, and it earns its cost by catching the request that the agent's tools
    simply cannot answer. Stopping here costs the operator seconds; the
    alternative is a run that gathers the wrong things and then reports
    confidently on them.
    """
    state, ctx = graph_state["state"], graph_state["ctx"]
    state.visit(Phase.TRIAGE)

    catalogue = "\n".join(
        f"- {spec.name}: {spec.description}" for spec in ctx.toolbox.specs
    ) or "(this agent has no tools in this run)"

    result: TriageOut = ctx.structured(
        Phase.TRIAGE,
        TriageOut,
        [
            _system(ctx, prompts.TRIAGE),
            HumanMessage(
                f"The operator asked:\n\n{state.input}\n\n"
                f"Tools available to this run:\n{catalogue}"
            ),
        ],
    )

    state.messages.append(Message(role="assistant", text=result.restated))

    if not result.can_proceed:
        # Not a failure. The run answers the operator with what is missing and
        # what would do the job instead, which is a useful thing to be told.
        state.findings.append(
            Finding(
                summary=result.reason or "This request cannot be answered with the available tools.",
                severity="unknown",
            )
        )
        state.phase = Phase.REPORT
        return {"state": state}

    state.phase = Phase.GATHER if ctx.toolbox.specs else Phase.REPORT
    return {"state": state}


def gather(graph_state: GraphState) -> GraphStateUpdate:
    """Collects observations with read-only tools, and stops.

    The boundary case: when the model asks for tools, this returns holding them
    and the graph ends. Java runs them, and the next reduce comes back into
    this same node with the results already filed in the ledger.
    """
    state, ctx = graph_state["state"], graph_state["ctx"]
    rounds = state.visit(Phase.GATHER)

    available = ctx.toolbox.for_phase(Phase.GATHER)
    if not available:
        state.phase = Phase.HYPOTHESIZE
        return {"state": state}

    nudge = ""
    if rounds > MAX_GATHER_ROUNDS:
        # Not a hard stop mid-turn — the model is told to conclude, which lets
        # it write a final honest sentence about what it could not establish
        # rather than being cut off holding a half-formed picture.
        nudge = (
            f"\n\nYou have now collected {rounds - 1} rounds of observations, which is the "
            f"limit for this run. Make no further tool calls. Work with what you have."
        )

    reply: AIMessage = ctx.bound(Phase.GATHER).invoke(
        [
            _system(ctx, prompts.GATHER + nudge),
            HumanMessage(f"The operator asked:\n\n{state.input}"),
            *_gather_history(state),
        ],
        config=ctx.config(Phase.GATHER),
    )
    ctx.track(reply)
    calls = _capture(state, reply)

    if calls and rounds <= MAX_GATHER_ROUNDS:
        refusals = _refuse_disallowed(state, ctx, Phase.GATHER, calls)
        if refusals:
            return {"state": state}
        return _park(state, calls)

    state.phase = Phase.HYPOTHESIZE
    return {"state": state}


def _refuse_disallowed(
    state: AgentState, ctx: RunContext, phase: Phase, calls: list[ToolCallWire]
) -> bool:
    """Answers out-of-phase calls in place instead of dispatching them.

    Reaching a tool the phase was never shown means the model produced a name
    from somewhere other than the request — a hallucination, or a memory of a
    previous run. It gets a real error it can recover from, written as a tool
    result so the turn stays well-formed, and nothing is looked up.
    """
    problems = {call.id: ctx.toolbox.check(phase, call) for call in calls}
    if not any(problems.values()):
        return False

    from agent_runtime.app.state import ToolResultWire

    results = [
        ToolResultWire(
            call_id=call.id,
            ok=False,
            content=problems[call.id] or "This call was not dispatched.",
        )
        for call in calls
    ]
    state.messages.append(Message(role="tool_results", tool_results=results))
    return True


def hypothesize(graph_state: GraphState) -> GraphStateUpdate:
    """Turns observations into findings, with no tools and no way to collect.

    Separated from GATHER on purpose. A model that can still call tools while
    it is concluding will keep calling them — there is always one more thing to
    check — and the run drifts instead of landing.
    """
    state, ctx = graph_state["state"], graph_state["ctx"]
    state.visit(Phase.HYPOTHESIZE)

    result: HypothesisOut = ctx.structured(
        Phase.HYPOTHESIZE,
        HypothesisOut,
        [
            _system(ctx, prompts.HYPOTHESIZE, evidence_rule=True),
            HumanMessage(
                f"The operator asked:\n\n{state.input}\n\n"
                f"Observations collected:\n\n{evidence.render(state)}"
            ),
        ],
    )

    known = state.cited()
    state.findings = [
        Finding(
            summary=item.summary,
            severity=item.severity,
            subject_kind=item.subject_kind,
            subject_id=item.subject_id,
            finding_type=item.finding_type,
            # An id the run never issued is dropped here rather than carried
            # into the report, where it would have to be caught again.
            cites=[value for value in item.cites if value in known],
        )
        for item in result.findings
    ]

    if result.need_more and state.visits(Phase.GATHER) <= MAX_GATHER_ROUNDS:
        state.messages.append(
            Message(role="user", text=f"Collect this as well: {result.collect}")
        )
        state.phase = Phase.GATHER
        return {"state": state}

    state.phase = Phase.PLAN if ctx.toolbox.has_mutating() else Phase.REPORT
    return {"state": state}


def plan(graph_state: GraphState) -> GraphStateUpdate:
    """Proposes actions. Nothing here executes; the gate is a separate node."""
    state, ctx = graph_state["state"], graph_state["ctx"]
    state.visit(Phase.PLAN)

    mutating = [spec for spec in ctx.toolbox.specs if spec.mutating]
    catalogue = "\n\n".join(_render_tool(spec) for spec in mutating)

    result: PlanOut = ctx.structured(
        Phase.PLAN,
        PlanOut,
        [
            _system(ctx, prompts.PLAN, evidence_rule=True),
            HumanMessage(
                f"The operator asked:\n\n{state.input}\n\n"
                f"Observations:\n\n{evidence.render(state)}\n\n"
                f"Findings:\n{_render_findings(state)}\n\n"
                f"State-changing tools you may propose:\n{catalogue}"
            ),
        ],
    )

    known = state.cited()
    allowed = {spec.name for spec in mutating}
    state.planned = [
        PlannedAction(
            tool=action.tool,
            arguments=action.arguments,
            intent=action.intent,
            blast_radius=action.blast_radius,
            rollback=action.rollback,
            cites=[value for value in action.cites if value in known],
        )
        # A proposal naming a tool this agent was not granted is dropped, not
        # passed to a human to approve. Approving it could not run it anyway,
        # and putting it in front of someone implies it is available.
        for action in result.actions
        if action.tool in allowed
    ]

    if not state.planned:
        if result.do_nothing_reason:
            state.findings.append(
                Finding(summary=f"No action proposed: {result.do_nothing_reason}", severity="info")
            )
        state.phase = Phase.REPORT
        return {"state": state}

    state.phase = Phase.GATE
    return {"state": state}


def gate(graph_state: GraphState) -> GraphStateUpdate:
    """Emits the first planned action as a real tool call, and stops.

    There is no approval logic here, and that is the design. Java decides
    whether a target needs a human, raises the approval, parks the run and
    resumes it — this node's whole job is to turn a plan into the call that
    triggers all of that. One action per pass, because a human approving three
    things at once cannot reject the second.
    """
    state, ctx = graph_state["state"], graph_state["ctx"]
    index = state.visit(Phase.GATE) - 1

    if index >= len(state.planned):
        state.phase = Phase.VERIFY if state.planned else Phase.REPORT
        return {"state": state}

    action = state.planned[index]
    call = ToolCallWire(
        id=f"gate-{state.visits(Phase.GATE)}-{action.tool}",
        name=action.tool,
        arguments=action.arguments,
    )

    problem = ctx.toolbox.check(Phase.GATE, call)
    if problem:
        state.findings.append(Finding(summary=f"Could not run {action.tool}: {problem}"))
        state.phase = Phase.REPORT
        return {"state": state}

    state.messages.append(
        Message(role="assistant", text=action.intent, tool_calls=[call])
    )
    state.phase = Phase.ACT
    return _park(state, [call])


def act(graph_state: GraphState) -> GraphStateUpdate:
    """Takes in the outcome of a gated action and decides what follows.

    A human's rejection routes to REPORT, never back to PLAN. An agent that
    responds to "no" by proposing a different way in is the single worst
    behaviour an operations agent can have, and the routing is where that is
    prevented — not the prompt.
    """
    state, _ = graph_state["state"], graph_state["ctx"]
    state.visit(Phase.ACT)

    decision = state.human_decision
    if decision is not None and not decision.approved:
        who = decision.decided_by or "An administrator"
        state.findings.append(
            Finding(summary=f"{who} rejected the proposed action, so nothing was changed.")
        )
        state.human_decision = None
        state.phase = Phase.REPORT
        return {"state": state}

    state.human_decision = None
    if state.visits(Phase.GATE) < len(state.planned):
        state.phase = Phase.GATE
        return {"state": state}

    state.phase = Phase.VERIFY
    return {"state": state}


def verify(graph_state: GraphState) -> GraphStateUpdate:
    """Re-checks that an action actually moved the system.

    Available, and not mandatory this round: an agent whose graph omits it goes
    straight to REPORT. Where it is used, an automation returning success is
    explicitly not accepted as evidence that anything changed — only an
    observation of the system is.
    """
    state, ctx = graph_state["state"], graph_state["ctx"]
    state.visit(Phase.VERIFY)

    if not ctx.toolbox.for_phase(Phase.VERIFY):
        state.phase = Phase.REPORT
        return {"state": state}

    reply: AIMessage = ctx.bound(Phase.VERIFY).invoke(
        [
            _system(ctx, prompts.VERIFY, evidence_rule=True),
            HumanMessage(
                f"The operator asked:\n\n{state.input}\n\n"
                f"What was done:\n{_render_actions(state)}\n\n"
                f"Observations so far:\n\n{evidence.render(state)}"
            ),
            *_gather_history(state),
        ],
        config=ctx.config(Phase.VERIFY),
    )
    ctx.track(reply)
    calls = _capture(state, reply)

    if calls and state.visits(Phase.VERIFY) <= 2:
        if _refuse_disallowed(state, ctx, Phase.VERIFY, calls):
            return {"state": state}
        return _park(state, calls)

    state.phase = Phase.REPORT
    return {"state": state}


def report(graph_state: GraphState) -> GraphStateUpdate:
    """Writes the final answer, then checks it against the ledger.

    The enforcement loop is one retry and no more. A model that will not
    substantiate a claim twice is not going to on the third attempt, and the
    operator is better served by a report that ships with its weak lines named
    than by a run that fails having produced nothing.
    """
    state, ctx = graph_state["state"], graph_state["ctx"]
    state.visit(Phase.REPORT)

    conversation: list[Any] = [
        _system(ctx, prompts.REPORT, evidence_rule=True),
        HumanMessage(
            f"The operator asked:\n\n{state.input}\n\n"
            f"Observations you may cite:\n\n{evidence.render(state)}\n\n"
            f"Findings:\n{_render_findings(state)}\n\n"
            f"Actions:\n{_render_actions(state)}"
        ),
    ]

    reply: AIMessage = ctx.model().invoke(conversation, config=ctx.config(Phase.REPORT))
    ctx.track(reply)
    draft = _text_of(reply)

    allowed = state.cited()
    result = evidence.audit(draft, allowed)

    if not result.clean:
        conversation.append(AIMessage(content=draft))
        conversation.append(
            HumanMessage(prompts.repair_prompt(result.unknown, result.uncited))
        )
        retry: AIMessage = ctx.model().invoke(conversation, config=ctx.config(Phase.REPORT))
        ctx.track(retry)
        draft = _text_of(retry)
        result = evidence.audit(draft, allowed)

    if not result.clean:
        draft = draft + "\n" + evidence.banner(result)
        state.uncited_claims = [*(f"[e:{value}]" for value in result.unknown), *result.uncited]

    state.messages.append(Message(role="assistant", text=draft))
    state.phase = Phase.DONE
    return {"state": state}


def respond(graph_state: GraphState) -> GraphStateUpdate:
    """The un-phased loop, for agents authored before this runtime existed.

    Every tool at once, the whole transcript, no evidence enforcement, no
    narrowing — a faithful reproduction of what ``AgentRunService`` does today.
    It is here so that switching the runtime on does not change the behaviour
    of an agent nobody has migrated yet. A JSON agent that worked yesterday
    works identically today; only agents authored as Python modules get the
    phased treatment.

    It is a deliberate dead end, not a foundation. Nothing routes into it.
    """
    state, ctx = graph_state["state"], graph_state["ctx"]
    state.visit(Phase.RESPOND)
    # Set before the model call, not after. A single-phase agent has no triage,
    # yet a run that parked holding tool calls used to report the state's
    # default phase — TRIAGE — in the run view, naming a phase it never runs.
    state.phase = Phase.RESPOND

    reply: AIMessage = ctx.bound(Phase.RESPOND).invoke(
        [
            _system(ctx, "Answer the operator's request using the tools you have."),
            HumanMessage(state.input),
            *_gather_history(state),
        ],
        config=ctx.config(Phase.RESPOND),
    )
    ctx.track(reply)
    calls = _capture(state, reply)

    if calls:
        if _refuse_disallowed(state, ctx, Phase.RESPOND, calls):
            return {"state": state}
        return _park(state, calls)

    state.phase = Phase.DONE
    return {"state": state}


# ------------------------------------------------------------ rendering ---


def _render_tool(spec) -> str:
    """One mutating tool, WITH its argument contract.

    This exists because PLAN is the one phase that has to produce arguments
    without being bound to the tool. GATHER calls tools directly, so every
    provider sends it the real JSON schema through ``bind_tools`` and the model
    can see what each one takes. PLAN emits a structured PlanOut instead, and
    ``gate`` then hands ``action.arguments`` to Java verbatim.

    Given only a name and a description, a model invents plausible argument
    names — ``region``, ``volumes``, ``dry_run`` — and NativeInputValidator
    fails closed on an undeclared field. Every gated action would fail on
    arrival, complaining about a form the model had never been shown. The
    contract has to travel with the proposal.
    """
    schema = spec.input_schema or {}
    properties = schema.get("properties") or {}
    required = set(schema.get("required") or [])

    lines = [f"- {spec.name}: {spec.description}"]
    if not properties:
        lines.append("  arguments: none")
        return "\n".join(lines)

    lines.append("  arguments:")
    for name, field in properties.items():
        parts = [str(field.get("type", "string"))]
        if field.get("enum"):
            parts.append("one of " + ", ".join(str(option) for option in field["enum"]))
        parts.append("required" if name in required else "optional")
        description = field.get("description") or ""
        lines.append(
            f"    {name} ({'; '.join(parts)})" + (f" - {description}" if description else "")
        )
    return "\n".join(lines)


def _render_findings(state: AgentState) -> str:
    if not state.findings:
        return "(none recorded)"
    return "\n".join(
        f"- [{finding.severity}] {finding.summary}"
        + (f" (cites {', '.join(f'[e:{value}]' for value in finding.cites)})" if finding.cites else "")
        for finding in state.findings
    )


def _render_actions(state: AgentState) -> str:
    if not state.planned:
        return "(nothing was proposed or run)"
    return "\n".join(
        f"- {action.tool}({action.arguments}) — {action.intent}\n"
        f"  blast radius: {action.blast_radius or 'not stated'}\n"
        f"  rollback: {action.rollback or 'NONE STATED'}"
        for action in state.planned
    )
