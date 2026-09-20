"""What an agent IS, and the line between what ships and what stays.

An agent is a Python module in this image. It exports one :class:`AgentSpec`
holding two very different things:

* :class:`Manifest` — **public**. Name, description, model, the tool refs it
  needs, its guardrails. This is what the build step writes to ``manifest.json``
  and what ``publish.py`` puts in ``library_items`` for rollout. A customer sees
  all of it, and should.
* ``persona`` and the graph — **private**. They never leave this image.

That split is the point, and it is stronger than what the JSON tree does today.
Under the current model a rolled-out agent's ``instructions`` are physically
copied into the customer's own database row, protected only by the API not
exposing them: anyone with a database credential has the product. Under this
model the customer's row holds a *reference* — ``{"kind":"PYTHON","ref":...}`` —
and the prompts exist only where the provider runs them.

The cost is that an agent can no longer be rolled out independently of a
deploy: shipping a new agent means shipping this image. That is the right trade
for a catalog the provider authors and the customer only consumes, and it is
the model the platform already assumes.
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass, field
from typing import Any

from agent_runtime.app.extraction import SubjectSource
from agent_runtime.app.state import Phase


@dataclass(frozen=True)
class ToolRef:
    """A tool this agent needs, named by ``ref`` rather than by id.

    A numeric id is meaningless across tenants — workflow #42 in the provider's
    workspace is a different automation in the customer's. Rollout resolves the
    ref to the tenant's own delivered copy; an agent authored against raw ids
    fails to roll out to everyone.

    ``mutating`` **defaults to True**, matching the same default on the Java
    side. It decides whether this tool is shown to a phase that is still
    gathering evidence, and nothing else in the platform records it — a workflow
    is a list of steps, and "does step four delete something" is not a question
    its schema can answer. So the author declares it, and an omission fails
    safe: an unmarked read-only tool goes unused and is noticed, where an
    unmarked destructive one would reach exactly the phase that must not see it.
    """

    type: str  # "WORKFLOW" | "JOB"
    ref: str
    mutating: bool = True

    #: Where this tool's output carries subject ids, if it enumerates any.
    #:
    #: A TUPLE because one tool can return several lists: ``RD-136`` yields
    #: unattached volumes, unassociated elastic IPs and stopped instances from a
    #: single call, and all three are ``cloud_resource``.
    #:
    #: Empty is the safe default and the common case — a tool nobody declared
    #: subjects for contributes none, so the run's scope is narrower than
    #: reality, which reaps less rather than more. Same failure direction as
    #: ``mutating`` defaulting to True.
    subjects: tuple[SubjectSource, ...] = ()


@dataclass(frozen=True)
class Manifest:
    """The public face of an agent. Everything here is safe to ship."""

    ref: str
    version: str
    name: str
    description: str
    domain: str
    model: str
    tools: list[ToolRef] = field(default_factory=list)
    guardrails: list[str] = field(default_factory=list)

    task_id: str | None = None
    sub_category: str | None = None
    scope: str | None = None
    risk_level: str | None = None
    automation_type: str | None = None
    approval_required: bool = False
    runtime: str | None = None
    blocked_by: str | None = None

    def to_json(self) -> dict[str, Any]:
        """The catalog row's ``definition``.

        Note what is NOT here: no persona, no prompts, no phase list. A reader
        of the customer's database learns what the agent does and what it is
        allowed to touch — which they are entitled to — and nothing about how
        it is made to behave that way.
        """
        return {
            "kind": "PYTHON",
            "ref": self.ref,
            "version": self.version,
            "name": self.name,
            "description": self.description,
            "domain": self.domain,
            "model": self.model,
            "tools": [
                {"type": tool.type, "ref": tool.ref, "mutating": tool.mutating}
                for tool in self.tools
            ],
            "guardrails": list(self.guardrails),
            "taskId": self.task_id,
            "subCategory": self.sub_category,
            "scope": self.scope,
            "riskLevel": self.risk_level,
            "automationType": self.automation_type,
            "approvalRequired": self.approval_required,
            "runtime": self.runtime,
            "blockedBy": self.blocked_by,
        }


@dataclass(frozen=True)
class AgentSpec:
    """One agent: its manifest, its voice, and the graph that runs it."""

    manifest: Manifest

    #: The agent's own instructions. Layered under the runtime preamble and
    #: over each phase's prompt. This is the field customers never see and the
    #: reason a rolled-out agent is worth paying for.
    persona: str

    #: Built per call. A factory rather than a compiled instance because the
    #: graph must not accumulate state between runs, and a module-level
    #: singleton is exactly how it would.
    build_graph: Callable[[], Any]

    #: Declared for documentation and for the eval harness, which compares runs
    #: by the phases they actually visited against the ones they could.
    phases: tuple[Phase, ...] = ()

    @property
    def ref(self) -> str:
        return self.manifest.ref

    @property
    def version(self) -> str:
        return self.manifest.version

    def declared_subject_kinds(self) -> tuple[str, ...]:
        """Every subject kind this agent's tools are declared able to enumerate.

        **This, and not what the tools have returned so far, is what a run
        declares at its start** — and getting that wrong would have failed every
        correlator.

        ``aws.public_exposure_auditor`` runs the S3 audit, then the security
        group audit, then IAM. If the declaration were built from results seen so
        far it would name only ``cloud_resource`` after the first two, the
        completion would name ``cloud_resource`` and ``principal``, and
        ``RunScope.checkNarrows`` would reject it — correctly, because a
        completion may never introduce a kind the run did not set out to cover.
        The declaration would have been wrong, not the validation.

        Read statically it is honest and available at run start: these are the
        kinds the run intends to cover, each as ``{"kind":"all"}``, and
        completion narrows them to what was actually enumerated. Which is also
        the answer to whether scope declaration can happen at run start — the
        INTENT can, because it comes from the agent rather than from the estate.
        """
        kinds: list[str] = []
        for tool in self.manifest.tools:
            for source in tool.subjects:
                if source.subject_kind not in kinds:
                    kinds.append(source.subject_kind)
        return tuple(kinds)
