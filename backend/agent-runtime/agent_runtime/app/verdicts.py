"""A run's findings, as verdicts something downstream can deduplicate.

**The gap this closes.** Every agent here already produces structured findings —
``HYPOTHESIZE`` emits them with a severity and the evidence ids they rest on —
and until now every one of them was thrown away. Only the prose report reached
the operator. That is fine for a run somebody watched and useless for a run on a
schedule: nightly agents with no memory re-file the same thing every night, and
nobody can dismiss anything because there is no thing to dismiss.

A verdict is that finding with the three fields that make it addressable:

* **subject** — what it is about, as a kind and an id. ``cloud_resource
  vol-0a1b…``, ``alert_rule cpu-high``. Not the prose.
* **type** — what KIND of finding, from a vocabulary. ``idle_resource``,
  ``public_bucket``. Two runs finding the same problem must agree on the word.
* **idempotency_key** — a hash of (agent, subject, type, window). A replay
  collapses; tomorrow's run recognises today's finding.

**Where a key cannot be stable, it says so.** A model that does not name a
subject leaves nothing to hash but its own prose, and prose changes wording
between runs while meaning the same thing. Hashing it anyway would manufacture
a key that looks stable, dedupes nothing, and quietly re-files every night —
which is worse than admitting it. ``stable_key`` is false in that case and the
caller decides whether to store it.
"""

from __future__ import annotations

import hashlib
from typing import Any

from agent_runtime.app.state import AgentState, Finding

SCHEMA_VERSION = "1.0"

#: The severities the phase kit produces, mapped onto the envelope's scale.
#: `unknown` is deliberately NOT mapped to a middle value: a finding the agent
#: could not grade is not a medium-severity finding, and averaging it into one
#: is how an ungraded problem gets sorted into the middle of a backlog.
_SEVERITY = {
    "critical": "critical",
    "warning": "medium",
    "info": "info",
    "unknown": "info",
}

#: What an agent may be trusted to do with its own conclusion, before any
#: policy narrows it further. This is the runtime's honest self-assessment and
#: not an authorisation: an agent holding no mutating tool cannot act whatever
#: tier it names, because the narrowing already removed the tools.
OBSERVE = "observe"
SUGGEST = "suggest"
ACT_WITH_APPROVAL = "act_with_approval"


def key_for(agent_ref: str, finding: Finding, window: str) -> tuple[str, bool]:
    """The idempotency key, and whether it is worth trusting.

    Composition is frozen: agent, subject kind, subject id, finding type,
    window. Changing what goes into it silently reopens every finding anyone
    has ever closed, so it is stated here rather than assembled at the call
    site.
    """
    stable = bool(finding.subject_id and finding.finding_type)
    if stable:
        material = "|".join([
            agent_ref,
            finding.subject_kind or "unknown",
            finding.subject_id or "",
            finding.finding_type or "",
            window,
        ])
    else:
        # Prose. Included so a replay of the SAME run still collapses, which is
        # the one guarantee it can honestly make.
        material = "|".join([agent_ref, "prose", finding.summary, window])
    return hashlib.sha256(material.encode("utf-8")).hexdigest()[:32], stable


def autonomy_for(state: AgentState, has_mutating: bool) -> dict[str, Any]:
    """What this run is asking to be allowed to do.

    Derived from what the agent HELD rather than from anything it said. An
    agent with no state-changing tool cannot be granted more than `suggest`,
    because the phase narrowing already made acting impossible — and a tier
    that claims otherwise would be the first thing to mislead a policy engine
    reading this.
    """
    if not has_mutating:
        return {
            "requested_tier": SUGGEST,
            "tier_source": "no_mutating_tool",
            "detail": "This agent holds no state-changing tool, so it can only report.",
        }
    if state.planned:
        return {
            "requested_tier": ACT_WITH_APPROVAL,
            "tier_source": "gated_plan",
            "detail": f"{len(state.planned)} action(s) were put to a human before anything ran.",
        }
    return {
        "requested_tier": SUGGEST,
        "tier_source": "no_action_proposed",
        "detail": "The agent held a state-changing tool and proposed nothing.",
    }


def evidence_for(state: AgentState, cites: list[int]) -> list[dict[str, Any]]:
    """The ledger entries a finding rests on, as evidence records.

    Only ids this run actually issued. The ledger is the authority and the
    finding's citation list is a claim about it — one that ``hypothesize``
    already filters, and that is re-checked here because this output may be
    stored long after the run and read by something that never saw the ledger.
    """
    by_id = {entry.evidence_id: entry for entry in state.ledger}
    records = []
    for cited in cites:
        entry = by_id.get(cited)
        if entry is None:
            continue
        records.append({
            "kind": "query_result" if entry.ok else "event",
            "label": f"{entry.tool} during {entry.phase.value}",
            "value": entry.excerpt,
            "source": {"system": "autoops", "step_id": entry.evidence_id},
            "observed_at": entry.recorded_at,
            "ok": entry.ok,
        })
    return records


def verdict_id(run_id: int | None, idempotency_key: str, ordinal: int) -> str:
    """Identifies ONE emission, stably across retries of the same response.

    ``run_id`` and the finding's ordinal would be enough on their own, and the
    idempotency key is mixed in so that a run whose findings change between
    reduces — a retry after the model produced a different list — cannot reuse
    an id for a different finding.

    A run with no id (the runtime is called directly in tests) falls back to the
    key and ordinal alone, which is stable for the same reason.

    **What "retry" means here, and where the stability ends.** The ordinal is a
    position in ``state.findings``, which nothing in this codebase sorts or
    reorders — HYPOTHESIZE sets the list and later phases append. So:

    * agent-service re-POSTing the same payload after a network failure carries
      the id already in it. Stable by construction.
    * agent-service calling reduce again over the SAME persisted state
      recomputes the same findings in the same order, so the same ids.
    * a run that re-enters HYPOTHESIZE and derives a DIFFERENT set of findings
      gets different ids — correctly, because that is a new emission rather than
      a retry of an old one. Sameness across runs is what the idempotency key
      is for; this identifies one emission.

    The dangerous version would be sorting ``state.findings`` anywhere, which
    would silently change every id in the list. If that ever becomes useful,
    this has to move off the ordinal first.
    """
    seed = f"{run_id or 0}|{idempotency_key}|{ordinal}"
    return hashlib.sha256(seed.encode("utf-8")).hexdigest()[:32]


def verdicts(
    state: AgentState,
    *,
    agent_ref: str,
    agent_version: str | None,
    run_id: int | None,
    tenant_id: str | None,
    window: str,
    has_mutating: bool,
) -> list[dict[str, Any]]:
    """Every finding this run reached, as an addressable verdict.

    Returns a LIST because a run reaches several conclusions and they have
    different subjects, severities and lifetimes. Collapsing them into one
    record per run is what forces a customer to dismiss an entire report to
    silence the one line in it they have already dealt with.
    """
    autonomy = autonomy_for(state, has_mutating)
    out: list[dict[str, Any]] = []

    for index, finding in enumerate(state.findings):
        idempotency_key, stable = key_for(agent_ref, finding, window)
        out.append({
            "schema_version": SCHEMA_VERSION,
            # ONE emission's identity, for replay protection. Distinct from the
            # idempotency key, which identifies the FINDING across runs: two
            # nights' sightings of the same problem share a key and must have
            # different verdict ids, or the second is discarded as a duplicate.
            #
            # Derived, not random. agent-service refuses a verdict it has seen
            # before — that is how a network retry stops becoming a second
            # sighting and inflating every counter — so the same emission
            # re-sent has to carry the same id. A uuid would be fresh on every
            # retry and every one of them would store.
            "verdict_id": verdict_id(run_id, idempotency_key, index),
            "idempotency_key": idempotency_key,
            # Named rather than implied. A caller that stores an unstable key
            # as though it were stable gets silent duplicates every run, and
            # the only place that can be known is here.
            "stable_key": stable,
            "agent": {
                "name": agent_ref,
                "version": agent_version,
                "run_id": run_id,
            },
            "tenant_id": tenant_id,
            "subject": {
                "kind": finding.subject_kind or "unknown",
                "id": finding.subject_id or "",
            },
            "finding": {
                "type": finding.finding_type or "unclassified",
                "summary": finding.summary[:280],
                "severity": _SEVERITY.get(finding.severity, "info"),
            },
            "autonomy": autonomy,
            "evidence": evidence_for(state, finding.cites),
            # An agent that could not substantiate its report says so on every
            # verdict it emitted, not only in the prose nobody parses.
            "fallback": {
                "is_fallback": bool(state.uncited_claims),
                "reason": "partial_data" if state.uncited_claims else None,
            },
            "ordinal": index,
        })
    return out
