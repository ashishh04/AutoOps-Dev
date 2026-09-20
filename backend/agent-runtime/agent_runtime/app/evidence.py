"""The evidence ledger, and the rule that a report may not assert what it did
not observe.

An operations agent that invents a figure is worse than one that says nothing.
"/var is at 94%" reads identically whether it came from a tool or from the
model's sense of what a plausible disk report looks like, and an engineer acting
on it at 3am has no way to tell. So this module makes the distinction structural
rather than a matter of prompting:

* Every tool result Java hands back becomes a ledger entry keyed by the id of
  the ``agent_run_steps`` row that recorded it. The citation is a real primary
  key an operator can follow, not a footnote the model made up.
* The report node is required to mark every factual claim ``[e:<id>]``.
* :func:`audit` checks the draft against the ledger. A citation to an id that
  was never issued is caught exactly; a factual-looking claim with no citation
  at all is caught heuristically.

**On the heuristic.** :func:`uncited_claims` decides what "looks factual" from
surface features — figures, percentages, paths, hostnames, status words. It is
not a semantic judgement and it will both miss things and occasionally flag a
harmless sentence. That is an acceptable trade because of how the result is
used: a flagged claim triggers ONE re-prompt, and if the model stands by it the
report still ships, carrying a visible banner. Nothing is silently deleted and
no run fails on this. The exact check — :func:`unknown_citations` — is the one
that carries weight, and Java repeats it independently against the run's own
step ids.
"""

from __future__ import annotations

import hashlib
import re
from dataclasses import dataclass, field

from agent_runtime.app.state import AgentState, Evidence, Phase, ToolResultWire

CITATION = re.compile(r"\[e:(\d+)\]")

#: How much of a tool result the ledger keeps. The full text is already in the
#: step row; this is what gets re-rendered into the report node's context on
#: every call, so it is bounded to keep a long investigation from crowding out
#: the reasoning with raw log output.
EXCERPT_LIMIT = 1200


def digest(content: str) -> str:
    return hashlib.sha256(content.encode("utf-8")).hexdigest()[:16]


def record(state: AgentState, results: list[ToolResultWire], phase: Phase) -> list[Evidence]:
    """Files tool results into the ledger and returns the new entries.

    A result with no ``evidence_id`` is skipped: it can still be reasoned about
    from the transcript, but it has no step row behind it and therefore nothing
    an operator could open. Letting it into the ledger would create a citation
    that looks checkable and is not, which is worse than no citation.

    Failures ARE recorded. "The collection failed" is an observation, and an
    agent needs to be able to cite it — reporting a host as healthy because the
    check errored is precisely the failure mode this exists to prevent.
    """
    added: list[Evidence] = []
    known = state.cited()
    for result in results:
        if result.evidence_id is None or result.evidence_id in known:
            continue
        content = result.content or ""
        entry = Evidence(
            evidence_id=result.evidence_id,
            tool=tool_for(state, result.call_id),
            ok=result.ok,
            excerpt=content[:EXCERPT_LIMIT],
            digest=digest(content),
            phase=phase,
        )
        state.ledger.append(entry)
        known.add(entry.evidence_id)
        added.append(entry)
    return added


def tool_for(state: AgentState, call_id: str) -> str:
    """Which tool produced a result, read back off the transcript.

    Java sends results keyed by call id and does not repeat the tool name; the
    assistant turn that requested it is the only place the name exists.
    """
    for message in reversed(state.messages):
        for call in message.tool_calls:
            if call.id == call_id:
                return call.name
    return "unknown"


def render(state: AgentState) -> str:
    """The ledger as the report node sees it.

    Ordered by id, which is chronological because the ids come from an
    auto-increment. Failures are marked inline so the model cannot read a
    failed collection as data.
    """
    if not state.ledger:
        return "(no observations were collected)"
    lines: list[str] = []
    for entry in sorted(state.ledger, key=lambda item: item.evidence_id):
        status = "OK" if entry.ok else "FAILED"
        lines.append(
            f"[e:{entry.evidence_id}] ({status}, from {entry.tool} during {entry.phase.value})\n"
            f"{entry.excerpt}"
        )
    return "\n\n".join(lines)


# ------------------------------------------------------------- auditing ---

#: Surface features that make a sentence a claim about the customer's estate
#: rather than commentary. Deliberately narrow: it is better to miss a claim
#: than to badger the model about every sentence and teach it to sprinkle
#: citations onto things they do not support.
_FACTUAL = re.compile(
    r"""
    \d+\s?%                     # 94%
    | \b\d+(\.\d+)?\s?(gb|mb|kb|tb|gib|mib|ms|s|m|h|d)\b   # 3.2GB, 450ms
    | \bload\s+average\b
    | (^|\s)/[a-z0-9._/-]+      # /var, /dev/sda1
    | \b\d{1,3}(\.\d{1,3}){3}\b # an IPv4 address
    | \b(vol|i|snap|ami)-[0-9a-f]{8,}\b   # AWS resource ids
    | \b(is|was|are|were)\s+(at|running|down|up|failing|failed|healthy|unhealthy)\b
    | \b(exit\s+code|returned|reported|shows?|showed)\b
    """,
    re.IGNORECASE | re.VERBOSE,
)

#: Sentences that need no citation, because they are not assertions ABOUT the
#: customer's systems: statements that something was not measured, and
#: recommendations about what to do next.
#:
#: The general negation clause is the important one, and it was added after a
#: golden case caught the auditor flagging "the check does not break /var usage
#: down by directory, so the cause needs a follow-up" — which is precisely the
#: sentence the agent's own instructions ask for. Being told to cite it would
#: push the model to either delete the caveat or attach a citation that does
#: not support it, and both are worse than the sentence.
#:
#: It does let a negated claim through uncited ("/var is not below 85%"). That
#: is the right way to be wrong here: a missed claim costs one uncited line,
#: while a false positive teaches the model to stop hedging honestly.
_EXEMPT = re.compile(
    r"\b(?:does|did|do|is|are|was|were|has|have|can|could|would)\s+not\b"
    # The SAME construction with a comma instead of an auxiliary: "that is a
    # sequence, not a cause"; "this shows what our automation did, not what
    # changed on the host". Added after the activity correlator's golden case,
    # where the heuristic flagged the precise sentence that agent's persona
    # instructs it to write — report proximity as a sequence and explicitly NOT
    # as a cause. A rule that penalises the hedge it asked for trains the model
    # out of hedging, which is the failure this module exists to prevent.
    #
    # It does let "/var is at 94%, not 85%" through uncited. That is the
    # documented direction to be wrong in: a missed claim costs one uncited
    # line, a false positive costs the honesty.
    r"|,\s*not\b"
    r"|\b(?:cannot|can't|doesn't|didn't|isn't|wasn't|aren't)\b"
    r"|\bno\s+(?:data|output|figures|breakdown|way\s+to)\b"
    r"|\b(?:unknown|unable\s+to|not\s+(?:checked|collected|available|returned|run))\b"
    r"|\b(?:recommend|recommended|suggest|should|next\s+step|follow[-\s]?up|needs?\s+"
    r"(?:a\s+)?(?:follow|further|investigation))\b",
    re.IGNORECASE,
)

_SENTENCE = re.compile(r"(?<=[.!?])\s+")


@dataclass
class Audit:
    """What the ledger says about a draft report."""

    citations: list[int] = field(default_factory=list)
    unknown: list[int] = field(default_factory=list)
    uncited: list[str] = field(default_factory=list)

    @property
    def clean(self) -> bool:
        return not self.unknown and not self.uncited


def parse_citations(text: str) -> list[int]:
    seen: list[int] = []
    for match in CITATION.finditer(text or ""):
        value = int(match.group(1))
        if value not in seen:
            seen.append(value)
    return seen


def audit(text: str, allowed: set[int]) -> Audit:
    """Checks a draft report against the evidence that exists.

    ``allowed`` is the ledger's id set. An id outside it was invented — the
    model reaching for the SHAPE of a citation without the substance — and that
    is caught exactly, not heuristically.
    """
    text = text or ""
    citations = parse_citations(text)
    return Audit(
        citations=citations,
        unknown=[value for value in citations if value not in allowed],
        uncited=uncited_claims(text),
    )


def uncited_claims(text: str) -> list[str]:
    """Claim-shaped statements carrying no citation.

    Works over "claim units" rather than sentences alone, because these reports
    are markdown: a table row and a bullet are each a single assertion and each
    needs its own citation, while a heading needs none.
    """
    offenders: list[str] = []
    for unit in _claim_units(text or ""):
        if CITATION.search(unit) or _EXEMPT.search(unit):
            continue
        if _FACTUAL.search(unit):
            offenders.append(unit.strip())
    return offenders


def _claim_units(text: str) -> list[str]:
    """Splits a markdown report into individually-citable statements."""
    units: list[str] = []
    in_fence = False

    for line in text.splitlines():
        stripped = line.strip()

        if stripped.startswith("```"):
            # Code and command output are quoted material, not assertions. The
            # claim is whatever the prose around the block says about it.
            in_fence = not in_fence
            continue
        if in_fence or not stripped or stripped.startswith("#"):
            continue
        if set(stripped) <= set("|-: "):
            # A markdown table separator row.
            continue

        if stripped.startswith("|"):
            units.append(stripped)
            continue
        if re.match(r"^([-*+]|\d+\.)\s", stripped):
            units.append(re.sub(r"^([-*+]|\d+\.)\s+", "", stripped))
            continue

        units.extend(part for part in _SENTENCE.split(stripped) if part.strip())

    return units


def banner(audit_result: Audit) -> str:
    """The notice appended to a report that could not be fully substantiated.

    Appended rather than substituted. A report with one unsupported line is
    still worth reading, and withholding it would leave the operator with
    nothing during exactly the incident they needed it for — so the honest move
    is to hand it over with the weak parts named.
    """
    lines = ["", "---", "", "**UNVERIFIED — this report was not fully substantiated.**", ""]
    if audit_result.unknown:
        ids = ", ".join(f"[e:{value}]" for value in audit_result.unknown)
        lines.append(
            f"- It cited {ids}, which this run never recorded. Treat anything resting on "
            f"those as unsupported."
        )
    for claim in audit_result.uncited:
        lines.append(f"- Not backed by any observation: “{claim}”")
    lines.append("")
    lines.append("Everything else carries a citation you can open in the run's step log.")
    return "\n".join(lines)
