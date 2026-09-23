"""The agent registry: the only place a ``ref`` becomes something runnable.

Java sends a ``ref`` and a ``version`` read out of the tenant's own catalog row.
This module answers from a table built at import time and nothing else — there
is no filesystem lookup, no dynamic import of a name from the request, and no
fallback that goes and finds something. An unknown ref is refused.

That refusal matters more than it looks. The ref arrives from a database row
that a rollout wrote, and rollout is the boundary between the provider's
catalog and a customer's workspace. A registry that resolved loosely — nearest
match, or "any agent in that domain" — would turn a corrupted or hand-edited
row into a *different agent running against production*, which is a far worse
outcome than a run that fails with "this agent is not in this build".

**On versions.** The registry holds one current version per ref. When the
version on the row does not match, the current one runs and the substitution is
reported back so Java can record it on the run. Refusing instead would be
tidier and wrong: sealed copies carry the version they were rolled out with, so
a strict check would break every tenant the moment the provider shipped an
update — which is the exact opposite of how this catalog is meant to work. What
matters is that the substitution is never silent.
"""

from __future__ import annotations

from dataclasses import dataclass

from agent_runtime.agents.autoops import activity_correlator, escalation_router
from agent_runtime.agents.aws import (
    alert_quality_analyst,
    cost_anomaly_investigator,
    finops_analyst,
    idle_resource_reclaimer,
    incident_rca_analyst,
    public_exposure_auditor,
)
from agent_runtime.agents.generic import phased, single_phase
from agent_runtime.agents.m365 import offboarding_auditor, privileged_access_auditor
from agent_runtime.agents.spec import AgentSpec

#: Every agent this build can run. Adding one is an import and a line here —
#: deliberately explicit, so `git log` on this file is the deployment history
#: of the catalog.
REGISTRY: dict[str, AgentSpec] = {
    spec.ref: spec
    for spec in (
        # The platform's own plane first: it is the only one that needs no
        # vendor credential and works for every estate.
        activity_correlator.AGENT,
        escalation_router.AGENT,
        incident_rca_analyst.AGENT,
        public_exposure_auditor.AGENT,
        cost_anomaly_investigator.AGENT,
        finops_analyst.AGENT,
        alert_quality_analyst.AGENT,
        idle_resource_reclaimer.AGENT,
        offboarding_auditor.AGENT,
        privileged_access_auditor.AGENT,
        single_phase.AGENT,
    )
}

#: What an agent with no ``graph_ref`` resolves to — the JSON-persona agents
#: that predate this runtime.
DEFAULT_REF = single_phase.REF


class UnknownAgent(KeyError):
    """A ref this build does not contain."""


@dataclass(frozen=True)
class Resolution:
    spec: AgentSpec
    #: Set when the requested version is not the one that ran. Surfaced on the
    #: response and stamped on the trace; never swallowed.
    substituted_from: str | None = None

    @property
    def note(self) -> str | None:
        if not self.substituted_from:
            return None
        return (
            f"This tenant holds {self.spec.ref} version {self.substituted_from}; "
            f"the runtime ran version {self.spec.version}."
        )


def resolve(ref: str | None, version: str | None = None,
            declared_phases: list[str] | tuple[str, ...] = ()) -> Resolution:
    """Looks up an agent, or refuses by name.

    ``declared_phases`` is how an agent with no module of its own gets the real
    runtime. It is read ONLY when there is no ref — an agent that names a module
    is that module, and a descriptor that somehow carried both must not be able
    to reshape a shipped agent's graph from a database row.

    An agent with no ref and no phases resolves exactly where it always did, to
    the un-phased compatibility loop. That is what makes this change invisible
    to everything already running: the phased runtime is reached by declaring
    phases, never by being redeployed.
    """
    if not (ref or "").strip() and declared_phases:
        return Resolution(spec=phased.spec_for(phased.phases_from(declared_phases)))

    key = (ref or DEFAULT_REF).strip()
    spec = REGISTRY.get(key)
    if spec is None:
        available = ", ".join(sorted(REGISTRY)) or "none"
        raise UnknownAgent(
            f'This runtime has no agent "{key}". It was rolled out from a catalog entry '
            f"this build does not contain — the deployment is behind the catalog, or the "
            f"entry is wrong. Agents in this build: {available}."
        )
    if version and version != spec.version:
        return Resolution(spec=spec, substituted_from=version)
    return Resolution(spec=spec)


def catalog() -> list[dict]:
    """Every agent's public manifest. Backs ``GET /v1/agents`` and the build step."""
    return [spec.manifest.to_json() for spec in REGISTRY.values()]
