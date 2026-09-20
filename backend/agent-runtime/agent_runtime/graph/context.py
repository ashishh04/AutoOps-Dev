"""The per-call context a phase needs, and the usage it accumulates.

Built fresh on every reduce and thrown away when it returns. Nothing here
survives a request — the credentials in particular are held for the length of
one call and then dropped, which is only true because there is no cache, no
session and no background worker holding a reference.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from langchain_core.language_models import BaseChatModel
from langchain_core.messages import AIMessage

from agent_runtime.app.models import build_model, to_openai_tools
from agent_runtime.app.state import AgentDescriptor, Phase, ToolSpecWire, Usage
from agent_runtime.app.toolbox import Toolbox


class StructuredOutputError(ValueError):
    """A phase asked for a typed answer and did not get one.

    Its own type so :func:`~agent_runtime.app.reduce.reduce` can report it as a
    run outcome the operator can act on, rather than as an unexplained crash.
    """


@dataclass
class RunContext:
    """Everything a phase needs that is not part of the run's own state."""

    agent: AgentDescriptor
    toolbox: Toolbox

    # The agent's own voice, resolved by the registry. For a Python-authored
    # agent this comes out of this service's image and the customer's database
    # has never held a copy; for a legacy JSON agent it is the `instructions`
    # column Java sent across. The phases cannot tell the difference, which is
    # what lets both kinds run on one kit.
    persona: str = ""

    usage: Usage = field(default_factory=Usage)

    #: How many times a model was called during THIS reduce.
    #:
    #: agent-service's step budget exists to stop a run costing a weekend, and
    #: it can only do that if it counts what it thinks it counts. One reduce can
    #: span several model calls — triage straight into gather is two — so
    #: incrementing the budget by one per reduce would let a phased agent make
    #: several times its allowance. This is what goes back on the wire.
    calls: int = 0

    callbacks: list[Any] = field(default_factory=list)
    trace_id: str | None = None

    # Built lazily and reused within the single reduce call. A reduce that
    # touches two phases (triage straight into gather, say) should not pay to
    # construct the client twice; a reduce that returns before any model call
    # should not construct it at all.
    _model: BaseChatModel | None = field(default=None, init=False, repr=False)

    def model(self) -> BaseChatModel:
        if self._model is None:
            self._model = build_model(self.agent)
        return self._model

    def bound(self, phase: Phase) -> BaseChatModel:
        """The model with exactly this phase's tools attached — and no others.

        Phases with no tools get the bare model rather than one bound to an
        empty list: several providers reject a request carrying ``tools: []``,
        and a phase that is meant to be thinking should not be sending a tool
        block at all.
        """
        specs: list[ToolSpecWire] = self.toolbox.for_phase(phase)
        if not specs:
            return self.model()
        return self.model().bind_tools(to_openai_tools(specs))

    def track(self, message: AIMessage | None) -> None:
        """Records one model call and adds its tokens to the running total.

        ``usage_metadata`` is absent on some providers and on streamed replies.
        A missing count is recorded as zero rather than estimated — a made-up
        token count on a billing surface is worse than an obviously low one. The
        CALL is still counted either way, because the budget must hold even when
        the token figures do not arrive.
        """
        self.calls += 1
        metadata = getattr(message, "usage_metadata", None) or {}
        self.usage.prompt_tokens += int(metadata.get("input_tokens") or 0)
        self.usage.completion_tokens += int(metadata.get("output_tokens") or 0)

    def structured(self, phase: Phase, schema: type, messages: list[Any]) -> Any:
        """A structured-output call whose tokens are actually counted.

        The plain ``with_structured_output(...).invoke(...)`` returns the parsed
        object and throws the ``AIMessage`` away with it — so the three phases
        that use structured output (triage, hypothesize, plan) would spend
        tokens that never reached the run's totals. On a surface a customer is
        billed from, silently undercounting is worse than not reporting at all.

        ``include_raw=True`` hands back both. Providers that ignore it return
        the parsed object directly, which is why the shape is checked rather
        than assumed — the call still gets counted, only its tokens are lost.
        """
        model = self.model().with_structured_output(schema, include_raw=True)
        result = model.invoke(messages, config=self.config(phase))

        if isinstance(result, dict):
            self.track(result.get("raw"))
            error = result.get("parsing_error")
            if error is not None:
                raise StructuredOutputError(
                    f"The model's {schema.__name__} reply could not be read: {error}"
                )
            parsed = result.get("parsed")
        else:
            self.track(None)
            parsed = result

        if parsed is None:
            # The model answered in prose instead of calling the schema tool.
            # LangChain reports that as parsed=None with NO parsing_error, so
            # returning it would hand the caller a None it immediately
            # dereferences — which is how this surfaced the first time, as
            # `AttributeError: 'NoneType' object has no attribute 'findings'`
            # three frames from the cause.
            #
            # Weaker models do this often enough that the message names the
            # phase and the fix, because the fix is usually "use a different
            # model" and nothing in a stack trace says so.
            raise StructuredOutputError(
                f"The model did not return the structured {schema.__name__} that the "
                f"{phase.value} phase requires — it replied with prose instead. This agent "
                f"needs a model that reliably uses tools; try a stronger one."
            )
        return parsed

    def config(self, phase: Phase) -> dict[str, Any]:
        """LangChain run config, tagged so a trace reads as a sequence of phases.

        **No ``callbacks`` key, deliberately.** A run's parent is resolved from
        the callback manager it inherits through a context variable, and naming
        callbacks here REPLACES that manager: pass the tracer again and this
        phase becomes its own top-level trace; pass an empty list — which is
        what this did before — and the call is not traced at all, because an
        empty list replaces the inherited manager with nothing.

        The tracer is attached once, at ``graph.invoke``, and every phase nests
        under it. ``tests/test_tracing.py`` pins this.
        """
        return {
            "run_name": f"{self.agent.ref}:{phase.value}",
            "tags": [f"agent:{self.agent.ref}", f"phase:{phase.value}"],
            "metadata": {
                "agent_ref": self.agent.ref,
                "agent_version": self.agent.version,
                "phase": phase.value,
                "model": self.agent.model,
                "vendor": self.agent.vendor.value,
            },
        }
