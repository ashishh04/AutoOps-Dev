"""What a run covered, in the shape agent-service will accept.

**Why an agent has to say this at all.** A finding may only be resolved by
absence when a run that *demonstrably covered its subject* did not re-emit it.
Reaping on "nobody mentioned it lately" means one agent outage marks a whole
backlog resolved, and that failure is invisible — a backlog that empties looks
exactly like a good week. The coverage claim is what turns "nobody mentioned it"
into "somebody looked and it was gone".

**This module is a convenience, never the boundary.** Every rule here is
enforced again server-side in ``RunScopeService`` / ``RunScope``, because an
agent that builds its own HTTP request bypasses everything in this file. If the
two ever disagree, the server is right and this is a bug.

Three shapes and no more, because the reaper has to push coverage into a SQL
``WHERE`` over indexed columns:

* ``scope_all()`` — everything, or everything of one kind.
* ``scope_dimensional()`` — a conjunction of ``IN`` lists over ``environment``
  and ``service_ref``.
* ``scope_enumerated()`` — an explicit list, sent alongside and materialised.

No negation and no wildcards. An exclusion is only correct if the excluded set
at reap time matches the set at run time, and tags move: an agent that skipped
prod on Monday plus a finding retagged to prod on Tuesday yields a scope
claiming coverage it never had. Inclusion-only means the claim is always
*narrower* than reality when data drifts, which is the direction that fails safe.

**The scope is declared twice.** At start it is the intent; at completion it is
what was actually reached, and the second may only narrow the first. Every kind
declared at start must reappear at completion carrying an explicit
:data:`Coverage`, so an agent whose IAM call threw cannot quietly omit
``principal`` and have the run still count as complete coverage of it.

**If a run cannot be opened, the agent fails — it does not buffer.** Verified
2026-09-20: every agent run is started through ``AgentRunService.start``, called
only from the authenticated API by a person or a scheduler. ``WebhookService``
fires core-service *job* runs, not agent runs, so **no agent in this build is
event-driven**, and all eight are sweeps over a window. For a sweep, failing is
free: the next sweep re-derives everything from the estate and nothing is lost.
Buffering verdicts against a run that does not exist yet only moves the problem
— they would arrive unattributable, which is the thing the run id exists to
prevent.

That is a statement about today's agents, not a law. **An event-driven agent
would need different handling**, because a missed event has no next sweep to
re-derive it; the first one of those is the moment to revisit this, and it
should not be discovered by an outage.
"""

from __future__ import annotations

import hashlib
from typing import Any, Iterable, Sequence

#: Dimensions a scope may filter on.
#:
#: Two hard rules decide this set. A dimension must be a COLUMN on ``findings``,
#: or the predicate cannot be pushed down. And it must be knowable *before* the
#: run — a scope says what was looked at, so anything derived from evaluating a
#: subject (``severity``, ``risk_tier``, ``confidence_band``, ``priority_score``)
#: describes the output set, not the input set, and is refused.
DIMENSIONS = ("environment", "service_ref")

#: The most subjects one run may enumerate. Each becomes a row server-side.
MAX_ENUMERATED = 100_000

#: How an individual kind ended, independent of the others.
#:
#: Per-element rather than per-run because a run that enumerated buckets fine
#: and then had its IAM call throw has real, usable coverage of
#: ``cloud_resource`` — failing the whole run throws it away every time one
#: dimension flakes.
COMPLETE = "COMPLETE"
PARTIAL = "PARTIAL"
SKIPPED = "SKIPPED"


class SubjectIdError(ValueError):
    """A subject id that would hash two ways depending on who found it."""


def require_well_formed(subject_id: str) -> str:
    """Refuse a malformed subject id rather than repairing it.

    Trimming silently means the same resource hashes two ways depending on which
    code path found it, and the join to it simply stops matching — no error, no
    log line, just a finding that never reaps.
    """
    if not subject_id:
        raise SubjectIdError(
            "A subject id cannot be empty. An empty id hashes to a real value "
            "and would silently become a subject nobody can name."
        )
    if subject_id != subject_id.strip():
        raise SubjectIdError(
            f"The subject id {subject_id!r} has surrounding whitespace. It is "
            "refused rather than trimmed: trimming means the same resource "
            "hashes two ways depending on which code path found it."
        )
    return subject_id


def subject_hash(subject_id: str) -> str:
    """The per-subject hash, as lowercase hex.

    Must agree byte for byte with Java's ``SubjectDigest.hash`` and with the V7
    migration's ``UNHEX(SHA2(subject_id, 256))``. **No normalisation**: not
    trimmed, not case-folded, not Unicode-normalised. Case-folding would be
    actively wrong — S3 object keys are case-sensitive, so folding would merge
    subjects that really are different.
    """
    return hashlib.sha256(subject_id.encode("utf-8")).hexdigest()


def subject_set_digest(subject_ids: Iterable[str]) -> str:
    """The digest over a whole enumerated scope.

    Sorted **by UTF-8 bytes**, deduplicated, **length-prefixed**, UTF-8. Sorted
    and deduplicated because the order a run happened to page its subjects in is
    not part of what it covered, and the same bucket reachable through two
    dimension slices is one subject.

    Sorting on the encoded bytes rather than on the string is what keeps this
    identical to Java. Python's ``sorted`` already compares code points, which
    agrees with UTF-8 byte order — but Java's ``String.compareTo`` compares
    UTF-16 code units, where a high surrogate sorts *below* U+E000–U+FFFF. The
    set ``{U+FFFD, U+1F600}`` frames in opposite orders under the two rules and
    yields two digests for one set. Both sides now sort on bytes, so the
    agreement holds by construction rather than by luck about which characters
    turn up in a resource name.

    The length prefix is the part that is easy to get wrong. Joining ids with a
    newline — on the reasoning that an id cannot contain one — makes ``["a","b"]``
    and ``["a\\nb"]`` serialise identically and collide, which is two different
    coverage claims sharing one digest. Prefixing each element with its byte
    length makes the encoding injective, so no assumption about the contents of
    an id is needed.

    ``subject_kind`` is deliberately NOT mixed in: it lives in its own indexed
    column server-side, so there is no concatenation and no question about how a
    null kind renders.
    """
    canonical = "".join(
        f"{len(sid.encode('utf-8'))}:{sid}\n"
        for sid in sorted(set(subject_ids), key=lambda sid: sid.encode("utf-8"))
    )
    return "sha256:" + hashlib.sha256(canonical.encode("utf-8")).hexdigest()


# --------------------------------------------------------------- builders ---


def scope_all(subject_kind: str | None = None) -> dict[str, Any]:
    """Everything, or everything of one kind.

    A bare ``scope_all()`` with no kind is the only scope that may stand alone
    as a whole run's claim — it already covers every kind, so anything beside it
    would be redundant or contradictory.

    Use it honestly, and note that **nothing can check this one for you**.
    An enumerated scope is derived from what a tool actually returned, so it
    cannot overclaim. ``all`` and ``dimensional`` are *authored*: declaring
    ``all`` and then examining 40% of the estate produces a claim that reaps
    two-thirds of the backlog on the first run, while the coverage gauge reads
    perfect throughout because the scope is present and the run is complete.

    That is the specific case ``RunScopeService.overclaimSuspects`` exists for.
    It is not redundant with derived scopes — it is the only cover for the
    authored ones.
    """
    scope: dict[str, Any] = {"kind": "all"}
    if subject_kind:
        scope["subject_kind"] = subject_kind
    return scope


def scope_dimensional(subject_kind: str, **dimensions: Sequence[str]) -> dict[str, Any]:
    """A conjunction of ``IN`` lists. An omitted dimension is unconstrained."""
    for key, values in dimensions.items():
        if key not in DIMENSIONS:
            raise ValueError(
                f"{key!r} is not a dimension a scope can filter on. Supported: "
                f"{list(DIMENSIONS)}. Anything derived from evaluating a subject "
                "describes the output set, not what was looked at."
            )
        if not values:
            raise ValueError(
                f"Dimension {key!r} needs at least one value. Omit it entirely "
                "to leave it unconstrained — an empty list matches nothing."
            )
    return {
        "kind": "dimensional",
        "subject_kind": subject_kind,
        "dimensions": {k: sorted(set(v)) for k, v in dimensions.items()},
    }


def scope_enumerated(subject_kind: str, subject_ids: Iterable[str]) -> dict[str, Any]:
    """An explicit list. The ids travel separately and are checked against this."""
    ids = sorted(
        {require_well_formed(sid) for sid in subject_ids},
        key=lambda sid: sid.encode("utf-8"),
    )
    if len(ids) > MAX_ENUMERATED:
        raise ValueError(
            f"An enumerated scope may list at most {MAX_ENUMERATED} subjects; "
            f"this one lists {len(ids)}. A run over more than that is describing "
            "a sweep — use a dimensional or all scope."
        )
    return {
        "kind": "enumerated",
        "subject_kind": subject_kind,
        "subject_id_count": len(ids),
        "subject_ids_digest": subject_set_digest(ids),
    }


def covered(scope: dict[str, Any], coverage: str) -> dict[str, Any]:
    """Stamp a completion scope element with how that kind actually ended."""
    if coverage not in (COMPLETE, PARTIAL, SKIPPED):
        raise ValueError(f"Unknown coverage {coverage!r}.")
    return {**scope, "coverage": coverage}
