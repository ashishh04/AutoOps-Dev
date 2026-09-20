"""Tracing must observe runs, and must never be able to break one.

Three things are pinned here, and the third is the one that would otherwise be
rediscovered the hard way:

1. **Off by default, and safe when misconfigured.** Enabled with no key, or a
   LangSmith client that throws on construction, produces an untraced run — not
   a failed one.
2. **The tenant's model credential never reaches the trace payload.** It arrives
   on the descriptor and leaves with the response; shipping it to a third-party
   observability SaaS would be a credential disclosure with good intentions.
3. **Callbacks are attached at the graph and nowhere else.** This is a LangChain
   semantic, not a style preference, and getting it wrong is silent: a tracer
   passed again on each model call turns one readable trace into eight
   disconnected ones, and an EMPTY callbacks list — which is what the code did
   before — replaces the inherited manager and traces nothing at all.
"""

from __future__ import annotations

from typing import TypedDict

import pytest
from langchain_core.callbacks import BaseCallbackHandler
from langchain_core.language_models import BaseChatModel
from langchain_core.messages import AIMessage
from langchain_core.outputs import ChatGeneration, ChatResult
from langgraph.graph import END, START, StateGraph

from agent_runtime.app import tracing
from agent_runtime.app.config import Settings
from agent_runtime.app.state import AgentDescriptor, Phase, Vendor
from agent_runtime.graph.context import RunContext
from agent_runtime.app.toolbox import Toolbox

SECRET = "sk-ant-thetenantsrealkey"


def descriptor() -> AgentDescriptor:
    return AgentDescriptor(
        ref="aws.public_exposure_auditor",
        version="1.0.0",
        model="claude-sonnet-5",
        vendor=Vendor.ANTHROPIC,
        credentials={"apiKey": SECRET},
    )


def configure(monkeypatch, **overrides) -> None:
    """Points the whole module at one Settings object, cache and all."""
    values = {"langsmith_enabled": False, "langsmith_api_key": ""} | overrides
    monkeypatch.setattr("agent_runtime.app.tracing.settings", lambda: Settings(**values))


# ------------------------------------------------------------- switches ---


def test_tracing_is_off_until_a_provider_turns_it_on(monkeypatch):
    configure(monkeypatch)
    callbacks, trace_id = tracing.handlers(descriptor(), run_id=7, tenant_id="t1")
    assert callbacks == []
    assert trace_id is None


def test_enabled_without_a_key_runs_untraced_rather_than_failing(monkeypatch, caplog):
    configure(monkeypatch, langsmith_enabled=True, langsmith_api_key="")
    callbacks, trace_id = tracing.handlers(descriptor(), run_id=7, tenant_id="t1")
    assert callbacks == []
    assert trace_id is None
    assert any("untraced" in record.message for record in caplog.records)


def test_a_tracer_that_cannot_be_built_never_fails_the_run(monkeypatch):
    configure(monkeypatch, langsmith_enabled=True, langsmith_api_key="ls-key")

    class Exploding:
        def __init__(self, *args, **kwargs):
            raise RuntimeError("LangSmith is unreachable")

    monkeypatch.setattr("langsmith.Client", Exploding)
    callbacks, trace_id = tracing.handlers(descriptor(), run_id=7, tenant_id="t1")
    assert callbacks == []
    assert trace_id is None


def test_enabled_with_a_key_produces_one_tracer_for_the_configured_project(monkeypatch):
    configure(
        monkeypatch,
        langsmith_enabled=True,
        langsmith_api_key="ls-key",
        langsmith_project="autoops-prod",
    )
    callbacks, trace_id = tracing.handlers(descriptor(), run_id=42, tenant_id="t1")

    assert len(callbacks) == 1
    tracer = callbacks[0]
    assert tracer.project_name == "autoops-prod"
    # The thread, not a per-call id: a run spans many reduces and they belong
    # on one conversation.
    assert trace_id == "agent-run-42"


def test_a_run_with_no_id_gets_no_thread(monkeypatch):
    configure(monkeypatch, langsmith_enabled=True, langsmith_api_key="ls-key")
    _, trace_id = tracing.handlers(descriptor(), run_id=None, tenant_id="t1")
    # A thread every unidentified run shares would merge unrelated evals into
    # one conversation, which is worse than having none.
    assert trace_id is None


# ---------------------------------------------------------- credentials ---


def test_the_tenants_model_key_never_reaches_the_trace_payload():
    config = tracing.graph_config([], descriptor(), run_id=9, tenant_id="acme")
    assert SECRET not in repr(config)
    assert "credentials" not in config["metadata"]
    # What IS there is the part someone actually filters on.
    assert config["metadata"]["agent_ref"] == "aws.public_exposure_auditor"
    assert config["metadata"]["session_id"] == "agent-run-9"
    assert "agent:aws.public_exposure_auditor" in config["tags"]


# ---------------------------------------------------------------- tenant ---


def test_the_tenant_is_filterable_on_the_trace_and_on_the_tracer(monkeypatch):
    """One agent is delivered to every customer who buys it.

    So "is v1.0.0 failing everywhere or only at one site?" is answered by
    filtering, and a tag is the facet the trace list filters on without a
    query. Both surfaces carry it: the tracer's own tags apply to the run
    LangSmith opens, the graph config's to the reduce underneath it.
    """
    configure(monkeypatch, langsmith_enabled=True, langsmith_api_key="ls-key")

    callbacks, _ = tracing.handlers(descriptor(), run_id=42, tenant_id="acme")
    assert "tenant:acme" in callbacks[0].tags

    config = tracing.graph_config([], descriptor(), run_id=42, tenant_id="acme")
    assert "tenant:acme" in config["tags"]
    assert config["metadata"]["tenant_id"] == "acme"


def test_a_run_with_no_tenant_is_tagged_as_such_rather_than_left_bare():
    """An untenanted run is an eval or a replay.

    Tagged rather than omitted so a customer investigation can filter those
    OUT — a missing tag is not something a trace list can search for.
    """
    config = tracing.graph_config([], descriptor(), run_id=None, tenant_id=None)
    assert "tenant:none" in config["tags"]


def test_hide_io_is_passed_to_the_client_for_tenants_who_refuse_to_ship_content(monkeypatch):
    configure(
        monkeypatch,
        langsmith_enabled=True,
        langsmith_api_key="ls-key",
        langsmith_hide_io=True,
    )
    seen = {}

    class Recording:
        def __init__(self, *args, **kwargs):
            seen.update(kwargs)

    monkeypatch.setattr("langsmith.Client", Recording)
    tracing.handlers(descriptor(), run_id=1, tenant_id="t1")

    assert seen["hide_inputs"] is True
    assert seen["hide_outputs"] is True


# ------------------------------------------------------- where it hangs ---


def test_phase_config_carries_no_callbacks():
    """The regression guard for the bug this replaced.

    ``config()`` used to pass ``callbacks=self.callbacks``. With tracing off
    that is an empty list, which REPLACES the inherited callback manager — so
    every model call was untraceable no matter what was attached at the graph.
    """
    context = RunContext(
        agent=descriptor(),
        toolbox=Toolbox(specs=[], unavailable=[]),
    )
    config = context.config(Phase.GATHER)
    assert "callbacks" not in config
    assert config["metadata"]["phase"] == "GATHER"


def test_graph_config_is_the_one_place_callbacks_are_attached():
    sentinel = object()
    config = tracing.graph_config([sentinel], descriptor(), run_id=1, tenant_id="t")
    assert config["callbacks"] == [sentinel]


class _Recorder(BaseCallbackHandler):
    """Notes whether each chat-model run had a parent."""

    def __init__(self):
        self.parented: list[bool] = []

    def on_chat_model_start(self, serialized, messages, *, parent_run_id=None, **kwargs):
        self.parented.append(parent_run_id is not None)


class _Fake(BaseChatModel):
    """A REAL BaseChatModel, unlike tests/fakes.ScriptedModel.

    The duck-typed fake is right for every other test — the runtime only uses
    three methods off a model — but LangChain's instrumentation is what is
    under test here, and it only instruments something it recognises.
    """

    def _generate(self, messages, stop=None, run_manager=None, **kwargs):
        return ChatResult(generations=[ChatGeneration(message=AIMessage(content="ok"))])

    @property
    def _llm_type(self) -> str:
        return "fake"


class _S(TypedDict):
    n: int


def _trace_one_node(per_call_config: dict) -> list[bool]:
    """Runs a one-node graph with the tracer on the GRAPH, and reports nesting."""
    recorder = _Recorder()

    def node(state):
        _Fake().invoke([], config=per_call_config)
        return {"n": 1}

    graph: StateGraph = StateGraph(_S)
    graph.add_node("A", node)
    graph.add_edge(START, "A")
    graph.add_edge("A", END)
    graph.compile().invoke({"n": 0}, config={"callbacks": [recorder], "run_name": "reduce"})
    return recorder.parented


@pytest.mark.parametrize(
    "per_call, expected, why",
    [
        ({"run_name": "x"}, [True], "no callbacks key -> nested under the graph run"),
        ({"run_name": "x", "callbacks": []}, [], "an empty list silences the call entirely"),
    ],
)
def test_langchain_parenting_is_why_config_omits_callbacks(per_call, expected, why):
    """Pins the LangChain semantic the design rests on.

    If a future LangChain release changes how a config's callbacks interact
    with the inherited manager, this fails here — loudly, offline, in a second
    — rather than as a LangSmith project that quietly stopped showing phases.
    """
    assert _trace_one_node(per_call) == expected, why
