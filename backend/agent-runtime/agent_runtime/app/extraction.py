"""Turning a tool result into the subjects a run examined.

**The scope comes from what a tool returned, never from what the model says it
saw.** A model that does not author its own coverage claim cannot overclaim one,
which removes most of the surface area that `overclaimSuspects` exists to watch.
It follows the pattern `ToolRef.mutating` and `ToolRef.ref` already set: the
agent's author declares what the platform cannot infer, and an omission fails
safe — no declaration means no enumeration, which means a scope that reaps
nothing.

**Every uncertain outcome is a refusal, not a smaller claim.** That is the
project's standing rule (see ``backend/MIGRATIONS.md``) and it matters more here
than almost anywhere, because the plausible-looking wrong answer is a *valid*
one: an empty enumeration is a real claim — "I looked and found nothing" — that
the reaper acts on. A parse failure recorded as an empty list reaps an entire
subject kind while the estate looks clean.

So the outcomes are deliberately four, not two:

``ENUMERATED``   subjects were read. The only one that produces a scope.
``EMPTY``        the result was well-formed and genuinely listed nothing.
``UNREADABLE``   the result could not be parsed, or the declared field was
                 absent from a non-empty result. **A platform bug.**
``TRUNCATED``    the content carries the elision marker. See TRUNCATION.md.

``UNREADABLE`` and ``TRUNCATED`` are separated from the tool having simply
*failed*, which is ordinary degraded operation and belongs in per-element
``PARTIAL``. Collapsing them means a parser that broke on a format change gets
diagnosed as a flaky API for as long as it takes somebody to notice.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from enum import Enum
from typing import Any, Iterable

from agent_runtime.app.config import ELISION_SIGNATURE
from agent_runtime.app.subject_scope import (
    COMPLETE,
    PARTIAL,
    SKIPPED,
    SubjectIdError,
    require_well_formed,
)


class Outcome(str, Enum):
    """Why extraction produced the subjects it did — or did not."""

    ENUMERATED = "ENUMERATED"
    EMPTY = "EMPTY"
    TOOL_FAILED = "TOOL_FAILED"
    UNREADABLE = "UNREADABLE"
    TRUNCATED = "TRUNCATED"

    @property
    def may_enumerate(self) -> bool:
        """Only a positive outcome yields a scope. Everything else refuses."""
        return self in (Outcome.ENUMERATED, Outcome.EMPTY)

    @property
    def is_a_gap(self) -> bool:
        """Whether something this source was meant to list went unlisted.

        The distinction that :func:`resolve` turns into coverage. ``EMPTY`` is
        NOT a gap — an estate with no idle volumes is fully covered by looking
        and finding nothing. Everything else means a list the run was supposed
        to enumerate is missing an unknown amount, which is the difference
        between COMPLETE and PARTIAL for the whole kind.
        """
        return self in (Outcome.TOOL_FAILED, Outcome.UNREADABLE, Outcome.TRUNCATED)


@dataclass(frozen=True)
class Extraction:
    outcome: Outcome
    subject_kind: str
    subject_ids: tuple[str, ...] = ()
    #: The count the SOURCE reported, when the tool says so. See TRUNCATION.md:
    #: ``len(subject_ids)`` is not a source total and provides no signal.
    source_total: int | None = None
    reason: str | None = None

    @property
    def source_verified(self) -> bool:
        """Whether a source total was checked against the list.

        Recorded on the scope element so "which of our reaps rest on
        unverifiable enumerations" stays a query rather than a per-agent
        re-derivation.
        """
        return self.source_total is not None


@dataclass(frozen=True)
class SubjectSource:
    """Where a tool's output carries subject ids, as its author declared it.

    The shape of this was decided by reading what the catalog actually emits
    rather than by guessing, and three of its fields exist because of what that
    turned up.

    :param subject_kind: what these ids identify — ``cloud_resource``,
        ``principal``.

        **What may be a subject at all:** something that can still exist on the
        next run and be re-examined. That is the whole criterion, and it decides
        cases that otherwise look arbitrary. An alarm rule qualifies; a
        CloudTrail event does not — the event happened, nothing persists to be
        found again, and a coverage claim over it would mean nothing because
        absence next run is the normal outcome rather than evidence of anything.
        ``RD-211`` is therefore deliberately left undeclared while ``RD-210``,
        which lists alarms, is not. The same question decides every log and
        metric tool somebody is later tempted to declare sources on.
    :param items: dotted path to the list, e.g. ``"unattached_volumes"``. Empty
        means the document itself is the list. **One declaration per list**:
        ``RD-136`` returns ``unattached_volumes``, ``unassociated_eips`` and
        ``stopped_instances`` from a single call, so one tool can need several.
    :param id_template: how to build the id, e.g. ``"{region}/{volume_id}"``.
        A template rather than a field name because **AWS resource ids are not
        globally unique**: ``vol-…`` and ``sg-…`` are region-scoped, and an IAM
        ``user`` is account-scoped. A run covering two regions would otherwise
        produce colliding subjects, and two different resources sharing one
        subject id is a finding reaped by the wrong evidence. Placeholders
        resolve against the ITEM first, then the document — the item is the more
        specific answer where both carry a field.
    :param total_field: dotted path to the SOURCE's own count. **Omit rather
        than pointing it at anything derived from the list** — a total that
        equals the list by construction agrees with it in every case including
        the broken ones, so it reads as verification while providing none.
    :param truncated_field: dotted path to a flag the tool sets when it
        shortened its own output. ``RD-203`` already reports one. This is the
        only signal that exists for truncation upstream of this service, and
        where a tool offers it, refusing on it closes a gap TRUNCATION.md
        otherwise records as unclosable.
    """

    subject_kind: str
    items: str
    id_template: str
    total_field: str | None = None
    truncated_field: str | None = None


def extract(content: str | None, source: SubjectSource, *, tool_ok: bool) -> Extraction:
    """Reads the subjects a tool result names.

    ``content`` must be the RAW result. Extraction runs before
    ``reduce._compact`` has produced anything, so there is no compacted value in
    scope here — and the elision check below is the guard for somebody moving
    the call anyway.
    """
    if not tool_ok:
        # Ordinary degraded operation, NOT a platform bug — calling it
        # unreadable would diagnose a flaky API as a parser problem. But it is
        # also not EMPTY: a list that was meant to be enumerated and was not is
        # a GAP, and collapsing the two is how a kind ends up COMPLETE over the
        # sources that happened to work.
        return Extraction(Outcome.TOOL_FAILED, source.subject_kind,
                          reason="the tool did not succeed")

    text = content or ""
    if ELISION_SIGNATURE in text:
        return Extraction(
            Outcome.TRUNCATED, source.subject_kind,
            reason="the result was elided before extraction saw it, so any enumeration "
                   "would silently describe part of what the run examined",
        )

    document = _parse(text)
    if document is None:
        return Extraction(Outcome.UNREADABLE, source.subject_kind,
                          reason="the result is not readable as JSON")

    if source.truncated_field and _walk(document, source.truncated_field):
        # The tool shortened its OWN output and said so. Distinct from the
        # elision marker, which is this platform truncating; both refuse, and
        # keeping the reasons apart is what stops "our pipeline is eliding" and
        # "their API paginated" being diagnosed as each other.
        return Extraction(
            Outcome.TRUNCATED, source.subject_kind,
            reason=f"the tool reported its own output truncated via "
                   f"{source.truncated_field!r}, so this list is part of what it saw",
        )

    items = _walk(document, source.items)
    if items is None:
        return Extraction(
            Outcome.UNREADABLE, source.subject_kind,
            reason=f"no list at {source.items!r} — the tool's output shape has changed, "
                   f"or the declaration never matched it",
        )
    if not isinstance(items, list):
        return Extraction(Outcome.UNREADABLE, source.subject_kind,
                          reason=f"{source.items!r} is not a list")

    if not items:
        # The legitimate empty: a well-formed result that genuinely listed
        # nothing. An estate with no idle volumes is not a bug.
        return Extraction(Outcome.EMPTY, source.subject_kind,
                          source_total=_total(document, source))

    ids: list[str] = []
    for item in items:
        try:
            composed = _compose(source.id_template, item, document)
        except SubjectIdError as malformed:
            # One malformed component refuses the WHOLE extraction rather than
            # dropping that subject. Dropping under-declares the scope by
            # exactly one, and that subject's findings are reaped on the next
            # run — a silent deletion per malformed record, invisible at any
            # scale and traceable to nothing.
            return Extraction(Outcome.UNREADABLE, source.subject_kind,
                              reason=str(malformed))
        if composed is None:
            # THE TRAP. A non-empty list from which nothing could be read is the
            # zero-subjects case arriving through the DECLARATION rather than
            # the parser, and it is the more likely version: tool output shapes
            # change and declarations do not follow. Reporting EMPTY here would
            # declare "I looked and found nothing" about an estate full of
            # subjects, and the reaper would act on it.
            return Extraction(
                Outcome.UNREADABLE, source.subject_kind,
                reason=f"{len(items)} item(s) could not fill {source.id_template!r} — a "
                       f"declared field is absent from the tool's output, so this is a "
                       f"changed format rather than an empty estate",
            )
        ids.append(composed)

    return Extraction(Outcome.ENUMERATED, source.subject_kind, tuple(ids),
                      source_total=_total(document, source))


def _compose(template: str, item: Any, document: Any) -> str | None:
    """Fills a template from the item, falling back to the document.

    Returns None when any placeholder is unfillable, which the caller treats as
    a changed output shape rather than as a missing subject. Raises
    :class:`SubjectIdError` when a component is present but malformed — see the
    comment below on why that check cannot wait for the composed result.
    """
    out: list[str] = []
    rest = template
    while True:
        start = rest.find("{")
        if start < 0:
            out.append(rest)
            return "".join(out)
        end = rest.find("}", start)
        if end < 0:
            return None
        out.append(rest[:start])
        key = rest[start + 1:end]
        value = item.get(key) if isinstance(item, dict) else None
        if value is None:
            value = _walk(document, key)
        if value is None or isinstance(value, (dict, list)):
            return None
        # Validate the COMPONENT, not just the finished id. Composition hides a
        # malformed part: " vol-2" inside "{region}/{volume_id}" becomes
        # "me-south-1/ vol-2", which has no surrounding whitespace and passes a
        # check on the composed string — while still being a different subject
        # from the same volume read by a code path that did not pad it.
        out.append(require_well_formed(str(value)))
        rest = rest[end + 1:]


def _parse(text: str) -> Any | None:
    """Reads the document, tolerating a ``JSON {...}`` trailer.

    Several automations print a human-readable report and then one machine
    line. Finding it is not cleverness — it is the format the catalog already
    uses, and refusing it would make every one of them unreadable.
    """
    text = text.strip()
    if not text:
        return None
    try:
        return json.loads(text)
    except ValueError:
        pass
    for line in reversed(text.splitlines()):
        line = line.strip()
        if line.startswith("JSON "):
            try:
                return json.loads(line[5:])
            except ValueError:
                return None
    return None


def _walk(document: Any, path: str) -> Any | None:
    if not path:
        return document
    current = document
    for segment in path.split("."):
        if not isinstance(current, dict) or segment not in current:
            return None
        current = current[segment]
    return current


def _total(document: Any, source: SubjectSource) -> int | None:
    if not source.total_field:
        return None
    value = _walk(document, source.total_field)
    return value if isinstance(value, int) else None


@dataclass(frozen=True)
class KindCoverage:
    """What one subject kind's scope element should claim.

    **The case this exists for.** ``RD-136`` returns three lists from a single
    call — volumes, elastic IPs, stopped instances — and all three are
    ``cloud_resource``. If the EIP listing throws while the other two succeed,
    the run really did examine those volumes and instances, so their coverage is
    real and must survive. But the element must NOT claim COMPLETE coverage of
    ``cloud_resource``, because a COMPLETE claim over two of three sources reaps
    every EIP finding on evidence that never looked at an EIP.

    So the union is kept and the verdict is degraded: PARTIAL, which never
    reaps. Under-claim over over-claim, as everywhere else.
    """

    subject_kind: str
    subject_ids: tuple[str, ...]
    coverage: str
    source_verified: bool
    gaps: tuple[str, ...] = ()


def resolve(subject_kind: str, extractions: Iterable[Extraction]) -> KindCoverage:
    """Folds every source for one kind into a single coverage claim."""
    items = list(extractions)
    ids: list[str] = []
    gaps: list[str] = []
    contributing = 0

    for extraction in items:
        if extraction.outcome.is_a_gap:
            gaps.append(f"{extraction.outcome.value}: {extraction.reason}")
            continue
        contributing += 1
        ids.extend(extraction.subject_ids)

    if contributing == 0:
        # Nothing was enumerable. The kind was not covered at all, which is a
        # claim the agent may honestly make and the reaper ignores.
        coverage = SKIPPED
    elif gaps:
        coverage = PARTIAL
    else:
        coverage = COMPLETE

    # Verified only if EVERY contributing source reported a source total. One
    # unverified source makes the union unverifiable — the missing count could
    # be anywhere in it.
    verified = contributing > 0 and all(
        e.source_verified for e in items if not e.outcome.is_a_gap
    )

    return KindCoverage(
        subject_kind=subject_kind,
        subject_ids=tuple(sorted(set(ids))),
        coverage=coverage,
        source_verified=verified,
        gaps=tuple(gaps),
    )


def merge(extractions: Iterable[Extraction]) -> dict[str, list[Extraction]]:
    """Groups by subject kind, because one kind can come from several tools.

    ``aws.public_exposure_auditor`` reads buckets and security groups from two
    different audits and both are ``cloud_resource``. The union is what the run
    examined, and keeping them separate until the end is what lets one tool fail
    without costing the coverage the other established.
    """
    grouped: dict[str, list[Extraction]] = {}
    for extraction in extractions:
        grouped.setdefault(extraction.subject_kind, []).append(extraction)
    return grouped
