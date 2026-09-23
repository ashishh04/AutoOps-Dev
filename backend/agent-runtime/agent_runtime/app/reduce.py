"""``(state, event) -> state'`` — the whole service, in one function.

Factor 12, taken literally. :func:`reduce` holds nothing between calls: it
rebuilds the agent, the toolbox, the model client and the graph from the request
it was handed, advances the run to its next boundary, and returns. Two
consecutive calls could land on two different instances, or on the same instance
three days apart across a redeploy, and neither case is special-cased because
neither case is different.

What makes that affordable is that the boundaries are cheap and few. A run stops
when it wants a tool — which Java must execute anyway, because Java owns
approvals and audit — or when it is finished. Everything between those points is
one graph traversal, and nothing in it needs to survive.

The other half of the contract is error handling. A reduce that raises produces a
500 and a run that Java has to guess about; a reduce that returns
:data:`~agent_runtime.app.state.Directive.FAIL` produces a run that fails with a
sentence an operator can act on. So the only exceptions that escape this module
are the ones that mean the request itself was malformed.
"""

from __future__ import annotations

import logging
import time
from typing import Any

from agent_runtime import agents
from agent_runtime.app import evidence, extraction, subject_scope, tracing, verdicts
from agent_runtime.app.config import ELISION_MARKER, settings
from agent_runtime.app.models import MissingCredential, VendorNotRunnable
from agent_runtime.app.state import (
    AgentState,
    Directive,
    HumanDecision,
    Message,
    Phase,
    ReduceRequest,
    ReduceResponse,
    StartEvent,
    StateVersionError,
    ToolResultsEvent,
    ToolResultWire,
    Usage,
    load_state,
)
from agent_runtime.app.toolbox import Toolbox
from agent_runtime.graph.context import RunContext

log = logging.getLogger(__name__)


def reduce(request: ReduceRequest) -> ReduceResponse:
    """Advances one run by one boundary."""
    try:
        resolution = agents.resolve(
            request.agent.ref, request.agent.version, request.agent.phases
        )
    except agents.UnknownAgent as exc:
        return _fail(None, str(exc))

    spec = resolution.spec

    try:
        state = _apply(request, spec.ref)
    except StateVersionError as exc:
        return _fail(None, str(exc))

    toolbox = Toolbox(specs=list(request.tools), unavailable=list(request.unavailable))
    # Off unless a provider turned it on. `trace_id` is the LangSmith THREAD
    # this run's reduces are stitched into, not a per-call id: a run parked on
    # an approval for two days produces several reduces, and scattering them
    # across unrelated traces loses the one artefact worth keeping.
    callbacks, trace_id = tracing.handlers(
        request.agent, run_id=request.run_id, tenant_id=request.tenant_id
    )

    context = RunContext(
        agent=request.agent.model_copy(update={"max_tokens": settings().max_tokens}),
        toolbox=toolbox,
        # A Python-authored agent's voice comes from this image. A legacy JSON
        # agent's comes from the tenant's row, on the descriptor. Exactly one
        # of the two is ever populated.
        persona=spec.persona or (request.agent.instructions or ""),
        callbacks=callbacks,
        trace_id=trace_id,
    )

    try:
        graph = spec.build_graph()
        # The ONE place callbacks are attached. Every phase inside inherits the
        # parent run through LangChain's context variable, which is what makes
        # a reduce read as one trace with eight nested phases rather than eight
        # unrelated traces. See tracing.graph_config.
        result = graph.invoke(
            {"state": state, "ctx": context},
            config=tracing.graph_config(
                callbacks, request.agent, run_id=request.run_id, tenant_id=request.tenant_id
            ),
        )
        state = result["state"]
    except (VendorNotRunnable, MissingCredential) as exc:
        # A configuration problem, not a run problem. The message names the
        # vendor and the field, which is what sends the operator to the right
        # screen instead of to the run log.
        return _fail(state, str(exc), context=context, trace_id=trace_id)
    except Exception as exc:  # noqa: BLE001 - see the module docstring
        log.exception("Agent %s failed while reducing run %s", spec.ref, request.run_id)
        return _fail(
            state,
            f"The agent stopped unexpectedly: {type(exc).__name__}: {exc}",
            context=context,
            trace_id=trace_id,
        )

    return _respond(
        state, context, resolution, trace_id,
        request=request,
        run_id=request.run_id,
        tenant_id=request.tenant_id,
        # The window an idempotency key is scoped to. A run id would make every
        # key unique and dedupe nothing; the DAY is the coarsest thing that is
        # still honest for agents that run nightly, and it is what lets
        # tomorrow's run recognise today's finding.
        window=time.strftime("%Y-%m-%d", time.gmtime()),
        has_mutating=toolbox.has_mutating(),
    )


# --------------------------------------------------------------- events ---


def _apply(request: ReduceRequest, agent_ref: str) -> AgentState:
    """Folds the incoming event into the state, or starts a new one."""
    event = request.event

    if isinstance(event, StartEvent):
        # A START against an existing state would silently discard a run's
        # history — including tool calls that already executed. Java only ever
        # sends START with a null state, and if that ever stops being true the
        # right answer is to notice, not to reset.
        if request.state is not None:
            raise StateVersionError(
                "A START event arrived for a run that already has saved state. This run "
                "cannot be restarted in place; start a new one."
            )
        return AgentState(agent_ref=agent_ref, input=event.input)

    state = load_state(request.state)
    if state is None:
        raise StateVersionError(
            "This run has no saved state, so there is nothing to resume. Start a new run."
        )

    if isinstance(event, ToolResultsEvent):
        # ORDERING IS THE ENFORCEMENT. Extraction reads event.results, which are
        # the RAW results as Java sent them. _absorb creates the compacted copies
        # and they never leave it, so there is no shortened value in scope here
        # to read by mistake.
        #
        # This matters because _compact elides the MIDDLE of a long result and a
        # scope built from the survivors is internally perfect — count matches
        # digest, digest matches rows, coverage matches count — while describing
        # a fraction of what the run examined, and reaping the rest. See
        # TRUNCATION.md.
        #
        # extraction.extract() also refuses content carrying ELISION_SIGNATURE,
        # which backs this up if somebody moves the call. That guard should
        # never fire in a correct build: if it fires, the ordering is wrong, not
        # the guard.
        _observe(state, request, event.results)
        _absorb(state, event.results)

    return state


def _observe(
    state: AgentState, request: ReduceRequest, results: list[ToolResultWire]
) -> None:
    """Records what the tools in this turn said the run examined.

    Subjects come from TOOL OUTPUT, never from the model's account of it — an
    agent that does not author its own coverage claim cannot overclaim one.
    """
    try:
        spec = agents.resolve(
            request.agent.ref, request.agent.version, request.agent.phases
        ).spec
    except agents.UnknownAgent:
        return

    declared = _declared_sources(request, spec)
    if not declared:
        return

    # The model calls tools by the name Java generated — workflow_<id>, a
    # tenant-local number. The ref is the stable name the agent's author
    # declared against, and it only exists on the wire spec.
    ref_by_name = {tool.name: tool.ref for tool in request.tools if tool.ref}

    for result in results:
        ref = ref_by_name.get(evidence.tool_for(state, result.call_id))
        for source in declared.get(ref, ()):
            found = extraction.extract(result.content, source, tool_ok=result.ok)
            state.extractions.append(
                {
                    "subject_kind": found.subject_kind,
                    "outcome": found.outcome.value,
                    "subject_ids": list(found.subject_ids),
                    "source_total": found.source_total,
                    "reason": found.reason,
                }
            )


def _declared_sources(
    request: ReduceRequest, spec: agents.AgentSpec
) -> dict[str, list[extraction.SubjectSource]]:
    """Every subject source this agent declared, by tool ref.

    Two origins, and they never overlap. A Python-authored agent declares in its
    module, which ships in this image. A console-authored agent has no module,
    so its declaration arrives on the descriptor — which is the only way it can
    claim coverage at all, and therefore the only way its findings can ever be
    reaped.

    **The module wins where both exist.** A shipped agent's declaration is part
    of what was reviewed and released; letting a database row override it would
    mean a hand-edited catalog entry could redirect subject extraction at a
    different field of a tool's output and produce a coverage claim over
    something the agent never examined. The catalog is the provider's, but it is
    still a row, and rows get edited.

    Both origins are STATIC with respect to this run: the module's is fixed at
    build time, and the descriptor's is sent identically on every reduce because
    it hangs off the agent, not off the per-run tool list. That property is the
    one that matters — a declaration that varied with which tool happened to run
    first would make a coverage claim a function of scheduling.
    """
    declared: dict[str, list[extraction.SubjectSource]] = {
        ref: list(sources)
        for ref, sources in (
            (tool.ref, tool.subjects) for tool in spec.manifest.tools
        )
        if sources
    }
    for ref, wired in (request.agent.subjects or {}).items():
        if ref and wired and ref not in declared:
            declared[ref] = [
                extraction.SubjectSource(
                    subject_kind=source.subject_kind,
                    items=source.items,
                    id_template=source.id_template,
                    total_field=source.total_field,
                    truncated_field=source.truncated_field,
                )
                for source in wired
            ]
    return declared


def _declared_kinds(request: ReduceRequest, spec: agents.AgentSpec) -> list[str]:
    """The subject kinds this run INTENDS to cover.

    Read from the declaration, never from results so far. That distinction has
    already cost this codebase once: an agent that audits S3, then security
    groups, then IAM would declare only ``cloud_resource`` after the first two
    calls, its completion would name ``cloud_resource`` and ``principal``, and
    the narrowing check would reject the claim — correctly, because a completion
    may never introduce a kind the run did not set out to cover. The
    declaration would have been wrong, not the validation.

    Order is preserved and duplicates removed, so the opening claim reads the
    same on every reduce of the same run.
    """
    kinds: list[str] = []
    for sources in _declared_sources(request, spec).values():
        for source in sources:
            if source.subject_kind not in kinds:
                kinds.append(source.subject_kind)
    return kinds


def _absorb(state: AgentState, results: list[ToolResultWire]) -> None:
    """Files results onto the transcript and into the ledger.

    Order matters: the transcript entry is written first because
    :func:`evidence.record` reads it back to work out which tool produced each
    result — Java sends results keyed by call id and does not repeat the name.
    """
    compacted = [
        result.model_copy(update={"content": _compact(state, result)}) for result in results
    ]
    state.messages.append(Message(role="tool_results", tool_results=compacted))
    state.pending_tool_calls = []
    evidence.record(state, compacted, state.phase)

    # A human's verdict rides in on a result but belongs on the state, because
    # the graph routes on it. The LAST decision in the turn wins: a turn can
    # only ever park on one approval at a time, so there is at most one.
    for result in compacted:
        if result.decision is not None:
            state.human_decision = HumanDecision(
                approved=result.decision == "APPROVED",
                decided_by=result.decided_by,
                content=result.content,
                call_id=result.call_id,
            )


def _compact(state: AgentState, result: ToolResultWire) -> str:
    """Factor 9: bound one result, and say when it has happened before.

    Two problems, one place. A 40,000-line log crowds out the evidence that
    would let the model recover, so it is elided from the middle — the head
    carries the command and the tail carries the failure, and the interesting
    parts of a log are almost never in between.

    The repeat counter addresses the other failure mode. A model that gets the
    same error twice will often try a third time, because nothing in its
    context distinguishes attempt three from attempt one. Saying so plainly is
    what breaks the loop — and it is a fact, not a nudge.
    """
    content = result.content or ""
    # A failure is summarised hard; a success is the deliverable and is kept.
    limit = settings().error_excerpt_limit if not result.ok else settings().output_limit

    if len(content) > limit:
        head = content[: limit // 2].rstrip()
        tail = content[-(limit // 2) :].lstrip()
        elided = len(content) - len(head) - len(tail)
        content = f"{head}\n\n{ELISION_MARKER.format(count=elided)}\n\n{tail}"

    if result.ok:
        return content

    seen = sum(
        1
        for message in state.messages
        for previous in message.tool_results
        if not previous.ok and _same_failure(previous.content, content)
    )
    if seen:
        content += (
            f"\n\n[This same failure has now occurred {seen + 1} times in this run. "
            f"Repeating the call will not change it — either change the approach or "
            f"report that it cannot be done.]"
        )
    return content


def _same_failure(left: str, right: str) -> bool:
    """Whether two errors are the same one again.

    Compares the first line only. Errors routinely carry a timestamp, a request
    id or a duration that differs on every attempt, and a whole-string
    comparison would call every retry a new problem — which is precisely the
    case the counter exists to catch.
    """
    return (left or "").strip().splitlines()[:1] == (right or "").strip().splitlines()[:1]


# -------------------------------------------------------------- replies ---


def _respond(
    state: AgentState,
    context: RunContext,
    resolution: agents.Resolution,
    trace_id: str | None,
    *,
    # Carried so the reply can read the agent's own declaration, which for a
    # console-authored agent lives on the descriptor rather than in this image.
    request: ReduceRequest,
    run_id: int | None = None,
    tenant_id: str | None = None,
    window: str = "",
    has_mutating: bool = False,
) -> ReduceResponse:
    """Turns the state the graph left behind into a directive for Java."""
    if state.pending_tool_calls:
        return ReduceResponse(
            state=state.model_dump(mode="json"),
            phase=state.phase,
            directive=Directive.CALL_TOOLS,
            tool_calls=list(state.pending_tool_calls),
            usage=context.usage,
            model_calls=context.calls,
            trace_id=trace_id,
            # The declaration, and deliberately NOT the coverage. It is a
            # property of the agent and true from the first call; coverage is a
            # property of a finished run. A parked run has examined an unknown
            # fraction of what it set out to, and publishing that fraction is
            # how one bad night reaps a backlog.
            declared_subject_kinds=_declared_kinds(request, resolution.spec),
        )

    coverage = _coverage(state)
    output = _final_text(state)

    if state.phase is not Phase.DONE:
        # The graph ran out of nodes without reaching REPORT. That is a routing
        # bug, and it is reported as one rather than dressed up as a finished
        # run — a run that "succeeded" with no report is the kind of thing
        # nobody investigates until it has happened a hundred times.
        return _fail(
            state,
            f"The agent stopped in phase {state.phase.value} without producing a report.",
            context=context,
            trace_id=trace_id,
        )

    blocked = _blocked_from_gathering(state, context)
    if blocked:
        # The run reached REPORT having observed nothing, done nothing and
        # concluded nothing, because every tool it was granted is invisible to
        # the only phase that collects evidence. The model still writes
        # something in that situation — it has an instruction and no data, so
        # it narrates its intent — and that narration is what would otherwise
        # be handed to the customer as the deliverable.
        #
        # Reported as a failure with the cause named, because it IS one, and
        # because the alternative is a report that looks like work. A run that
        # succeeded without looking at anything is worse than a run that
        # failed: the failure gets fixed.
        # The narration is replaced rather than shipped beside the error. A
        # failed run still returns its output, and _final_text takes the last
        # assistant message — so without this the paragraph describing tool
        # calls that never happened is still what the customer would read.
        state.messages.append(Message(role="assistant", text=blocked))
        return _fail(state, blocked, context=context, trace_id=trace_id)

    note = resolution.note
    if note:
        # Prepended, not appended: it is a caveat about the whole report and
        # belongs where it will be read.
        output = f"_{note}_\n\n{output}" if output else note

    return ReduceResponse(
        state=state.model_dump(mode="json"),
        phase=Phase.DONE,
        directive=Directive.FINISH,
        output=output,
        usage=context.usage,
        model_calls=context.calls,
        trace_id=trace_id,
        citations=evidence.parse_citations(output or ""),
        uncited_claims=list(state.uncited_claims),
        # Emitted only on a FINISHed run. A run that failed halfway has
        # findings in its state, and publishing them as verdicts would let a
        # half-finished investigation be dismissed as though it were complete.
        findings=verdicts.verdicts(
            state,
            agent_ref=resolution.spec.ref,
            agent_version=resolution.spec.version,
            run_id=run_id,
            tenant_id=tenant_id,
            window=window,
            has_mutating=has_mutating,
        ),
        declared_subject_kinds=_declared_kinds(request, resolution.spec),
        # The COMPLETION claim, and only on a finished run. A run that stopped
        # halfway examined an unknown fraction of what it set out to, and
        # publishing that as coverage is how an outage reaps a backlog.
        subject_scope=coverage[0],
        subject_ids=coverage[1],
    )


def _blocked_from_gathering(state: AgentState, context: RunContext) -> str | None:
    """Why this run could not have gathered anything, if that is the case.

    Narrowing hides a mutating tool from GATHER on purpose, and an agent whose
    grant is ENTIRELY mutating therefore has a gathering phase that can see
    nothing at all. That is a misconfiguration rather than a result, but from
    inside the graph it is indistinguishable from an agent that simply found
    nothing: GATHER routes onward, HYPOTHESIZE asks for data that never
    arrives, and REPORT writes a paragraph about what it intended to do.

    Everything here must be true before this is called a failure. An empty
    ledger alone is not enough — a legitimate run can observe nothing — and a
    run that acted, planned or concluded did work worth reporting even if it
    gathered little. This is the narrow case where the agent was never able to
    start.
    """
    if state.ledger or state.findings or state.planned or state.extractions:
        return None
    granted = context.toolbox.specs
    if not granted:
        return None

    if not context.toolbox.for_phase(Phase.GATHER):
        names = ", ".join(sorted(spec.name for spec in granted))
        return (
            f"The agent could not collect any evidence. All {len(granted)} of the tools it was "
            f"granted ({names}) are marked as state-changing, and the evidence-gathering phase "
            f"is only ever shown read-only tools — so it had nothing to call. Mark the "
            'read-only ones with "mutating": false on the tool grant for this agent, then run '
            "it again."
        )

    attempted = [
        result
        for message in state.messages
        for result in message.tool_results
    ]
    if attempted and not any(result.ok for result in attempted):
        # Every call it made was rejected. The model cannot recover from this
        # on its own — the errors that produce it are usually structural rather
        # than reasoning mistakes, and a model that spells an argument wrong
        # once will spell it wrong identically on every retry — so the run
        # burns its rounds and REPORT is asked to write up an investigation
        # that never happened.
        reasons = []
        for result in attempted:
            first = (result.content or "").strip().splitlines()[:1]
            if first and first[0] not in reasons:
                reasons.append(first[0])
        # The first line of each distinct failure, capped: the operator needs
        # to know WHICH wall it hit, and three of them is enough to see the
        # pattern without pasting a transcript into an error field.
        detail = "; ".join(reasons[:3])
        summary = (
            f"The agent could not collect any evidence: all {len(attempted)} of its tool "
            f"calls were rejected, so it observed nothing to report on."
        )
        return f"{summary} {detail}" if detail else summary

    return None

def _coverage(state: AgentState) -> tuple[list[dict[str, Any]], dict[str, list[str]]]:
    """Folds every extraction into one scope element per subject kind.

    Per kind rather than per tool because a kind can come from several tools —
    and from several lists within one tool. ``resolve`` is what keeps a failure
    in one of them from either contaminating the others or being papered over:
    the union of what was seen is kept, and the verdict degrades to PARTIAL.
    """
    elements: list[dict[str, Any]] = []
    ids: dict[str, list[str]] = {}

    for kind, group in extraction.merge(
        extraction.Extraction(
            outcome=extraction.Outcome(record["outcome"]),
            subject_kind=record["subject_kind"],
            subject_ids=tuple(record["subject_ids"]),
            source_total=record["source_total"],
            reason=record["reason"],
        )
        for record in state.extractions
    ).items():
        covered = extraction.resolve(kind, group)
        element: dict[str, Any] = {
            "kind": "enumerated",
            "subject_kind": kind,
            "subject_id_count": len(covered.subject_ids),
            "subject_ids_digest": subject_scope.subject_set_digest(covered.subject_ids),
            "coverage": covered.coverage,
        }
        if covered.source_verified:
            element["source_verified"] = True
        elements.append(element)
        ids[kind] = list(covered.subject_ids)

    return elements, ids


def _final_text(state: AgentState) -> str:
    for message in reversed(state.messages):
        if message.role == "assistant" and message.text.strip():
            return message.text
    return ""


def _fail(
    state: AgentState | None,
    message: str,
    *,
    context: RunContext | None = None,
    trace_id: str | None = None,
) -> ReduceResponse:
    """A failure Java can record and an operator can read.

    The state is still returned when there is one. A run that failed halfway
    through an investigation did real work — tool calls that executed, evidence
    that was collected — and discarding it would leave the operator with an
    error and no account of what had already happened to their systems.
    """
    if state is not None:
        state.last_error = message
    return ReduceResponse(
        state=state.model_dump(mode="json") if state is not None else {},
        phase=state.phase if state is not None else Phase.TRIAGE,
        directive=Directive.FAIL,
        error=message,
        output=_final_text(state) if state is not None else None,
        # Tokens already spent are still billable and still worth reporting,
        # even on the call that failed.
        usage=context.usage if context is not None else Usage(),
        model_calls=context.calls if context is not None else 0,
        trace_id=trace_id,
    )
