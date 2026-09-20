"""Service settings. Environment only — this service reads no config file.

It holds no state and no credentials of its own: the tenant's model key arrives
per request and leaves with it. What is here is the shared secret its own
``/v1`` endpoints require.
"""

from __future__ import annotations

from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict

DEV_INTERNAL_TOKEN = "dev-internal-token"


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="AGENT_RUNTIME_", case_sensitive=False)

    #: Shared secret required on every ``/v1`` call, matching the
    #: ``X-Internal-Token`` pattern the Java services already use between
    #: themselves. There is no user-facing auth here because there is no
    #: user-facing route: the gateway does not expose this service at all.
    internal_token: str = DEV_INTERNAL_TOKEN

    #: Ceiling on ONE model reply. A per-run budget is Java's job — it owns the
    #: step count — and duplicating it here would give two answers to "why did
    #: this stop".
    max_tokens: int = 4096

    #: core-service, for the `job` workflow node. This is the ONLY call this
    #: service makes back into the control plane, and it exists so a workflow
    #: can run an automation — a script, a job, another workflow — rather than
    #: only think about one.
    #:
    #: The direction matters: nothing is executed HERE. The node asks
    #: core-service to dispatch, and core-service applies the credential
    #: resolution, the approvals gate and the audit trail it always has. This
    #: service still cannot reach a customer's infrastructure.
    core_base_url: str = "http://core-service:8083"
    core_internal_token: str = "dev-internal-token"

    #: How often a `job` node asks whether the run it started has finished.
    job_poll_interval_seconds: float = 3.0

    #: Ceiling on ONE job node. A workflow holding a core-service execution
    #: thread while it waits is the reason this is bounded at all: without a
    #: limit, one stuck automation pins a workflow run forever and the operator
    #: sees a spinner rather than a failure.
    job_timeout_seconds: float = 1800.0


    # ---------------------------------------------------------- tracing ---
    #
    # Opt-in, and off by default. A managed-services platform reasoning over a
    # customer's infrastructure should not ship that reasoning to a third party
    # because someone forgot to turn something off.

    #: Master switch. With this false nothing is built, nothing is imported and
    #: no key is read — the run is simply untraced.
    langsmith_enabled: bool = False

    #: The LangSmith API key. Enabled-without-a-key logs one warning and runs
    #: untraced rather than failing: tracing is never worth a failed run.
    langsmith_api_key: str = ""

    #: Where traces are filed. One project per environment is the useful
    #: split — a staging run next to a production one in the same list is how
    #: an investigation reaches the wrong conclusion.
    langsmith_project: str = "autoops-agents"

    #: Overridable for LangSmith's EU host or a self-hosted instance. Data
    #: residency is a contractual question for a managed-services customer,
    #: not a default.
    langsmith_endpoint: str = "https://api.smith.langchain.com"

    #: Trace the STRUCTURE without the content: phases, timings, token counts
    #: and errors are kept; the messages and tool output are dropped before
    #: they leave the process. For a tenant whose infrastructure detail must
    #: not reach a SaaS, this is the difference between tracing and not.
    langsmith_hide_io: bool = False

    #: Bounds a FAILED tool result before it enters the transcript. Factor 9: a
    #: 40,000-line stack trace teaches a model nothing that its first and last
    #: twenty lines do not, and it crowds out the evidence that would have let
    #: it recover.
    error_excerpt_limit: int = 2000

    #: Bounds a SUCCESSFUL one, and is deliberately far larger.
    #:
    #: The two were one setting, and that was wrong. A failed step's output is
    #: noise to be summarised; a successful step's output is frequently the
    #: DELIVERABLE. A research workflow returned a 50,000-character report and
    #: it was cut to 2,000 before the model ever saw it — the agent was then
    #: asked to hand back a report it had only been shown the first page of.
    #:
    #: Still bounded, because an unbounded result is a context-window overflow
    #: waiting to happen. Raise it if your automations legitimately return more.
    output_limit: int = 120000


#: What compaction writes where it removed the middle of a result.
#:
#: Lives here, beside the limits that cause it, rather than in ``reduce`` —
#: extraction has to RECOGNISE it and ``reduce`` imports extraction, so keeping
#: it there is a cycle. Both modules import this one and neither imports the
#: other.
#:
#: Extraction refuses content carrying this rather than enumerating part of a
#: list: a scope built from the survivors of an elision is internally perfect
#: while describing a fraction of what the run examined. See TRUNCATION.md.
ELISION_MARKER = "... [autoops-elided {count} characters] ..."

#: The fixed part of the marker, for recognising a rendered one.
#:
#: Deliberately unlikely to occur in real output. An earlier draft matched on
#: "characters elided", which a log line, a user-supplied tag or a tool doing its
#: own eliding could plausibly contain — and a spurious refusal is safe but
#: noisy, which is how a check gets weakened by somebody tired of it. The vendor
#: prefix makes a collision a deliberate act.
ELISION_SIGNATURE = "[autoops-elided "


@lru_cache(maxsize=1)
def settings() -> Settings:
    return Settings()
