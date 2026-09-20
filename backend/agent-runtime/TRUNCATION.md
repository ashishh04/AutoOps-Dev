# Where a tool result gets shortened, and why it matters now

Until coverage scopes existed, truncating a tool result cost the model some
context and nothing else — the worst case was an agent that could not see the
middle of a report. That is no longer the worst case. **A scope is built from
what a tool returned, so a shortened result produces a smaller scope, and a
smaller scope reaps every subject it left out.**

The failure is silent and well-formed: `subject_id_count` matches the digest,
the digest matches the materialised rows, the coverage verdict matches the
count. Every check in the system passes a scope that describes 200 of the 1200
volumes the run actually looked at.

This file is the audit. **Anything added to this pipeline that shortens,
samples, paginates or caps a tool result belongs on this list.**

## 1. `reduce._compact` — the one that will bite

`app/reduce.py`. Elides the **middle** of a result, keeping head and tail:

| result | limit | source |
|---|---|---|
| success | **120,000 characters** | `settings().output_limit` |
| failure | **2,000 characters** | `settings().error_excerpt_limit` |

Two things about this are worse than they look.

**120,000 characters is not a comfortable margin for an inventory.** A volume
record carrying id, size, type, availability zone and age runs 120–200
characters; 1,200 of them is 150,000–240,000. The flagship enumeration case is
at or over the limit *today*, not at some future scale.

**Partial failure produces a MORE dangerous scope than total failure.** That
sentence is worth reading twice, because it is the opposite of what anyone
designing this would assume, and somebody will refactor back toward it.

`_compact` picks its limit from `result.ok`. A totally failed inventory returns
no subjects, the element is refused or marked SKIPPED, and nothing reaps — safe.
A *partially* failed inventory — one region unreachable, the other nine fine —
is `ok = false`, so it is cut to **2,000 characters**: a handful of subjects, a
perfectly coherent enumerated scope, and a run that reaps almost the entire
estate.

The inversion is that partial failure narrows what the run **lists** while
leaving what it **claims** intact. Every other degradation in this system
narrows the claim too. This one does not, and that is exactly why a smaller
failure is worse here than a complete one.

## 2. `evidence.record` — bounded, and not on the extraction path

`app/evidence.py`, `EXCERPT_LIMIT = 1200`. This truncates the ledger *excerpt*,
which exists so an operator can see what a citation refers to. It is fed the
already-compacted result, so it is a truncation of a truncation — harmless,
because nothing derives a scope from it. Listed so the next audit does not have
to rediscover that it is safe.

## What this means for extraction

**Extraction runs on the raw results, before `_compact` has produced anything.**
Not "reads the raw field" — runs earlier in the pipeline, so there is no
compacted value in scope at the point extraction happens. Ordering as the
enforcement, because the compacted result is the same type as the raw one and a
type cannot tell them apart without wrapping every result.

Ordering alone is weak — somebody can move the call — so it is backed by a
runtime guard: `_compact` writes a recognisable elision marker, and extraction
**refuses** content containing it rather than extracting a partial set. That
turns "somebody reordered the pipeline" from a silently smaller scope into a
loud failure, and a run whose scope is refused declares nothing and reaps
nothing.

## The gap that remains, stated plainly

**Truncation upstream of this service is undetectable here.** If a workflow
paginates and returns the first page, or caps its own output, or an automation
prints a summary instead of a list, the result arriving here is complete as far
as anything in this process can tell. There is no marker to look for.

The backstop is V11's out-of-scope check in agent-service: a verdict about a
subject the run's own scope excludes is a contradiction, and it is refused. That
is real protection and it is **not complete**:

- it only applies to `enumerated` scopes, since `all` and `dimensional` admit
  anything of their kind;
- it only fires when the missing subjects actually produce verdicts. An
  inventory truncated in a region where nothing was findable passes silently and
  still reaps that region.

**Partly closed by something already in the catalog.** `RD-203`
(`m365-mailbox-rule-audit`) sets a `truncated` flag in its own output when it
shortens its list. `SubjectSource.truncated_field` reads it, and extraction
refuses when it is set — with a reason distinct from our own elision marker, so
"our pipeline elided" is never diagnosed as "their API paginated". Any
enumerating automation that grows this flag gets the same protection for free.

For the rest, nothing available today closes that second case.

### The ask on the automations, stated precisely

Any tool an agent enumerates a scope from should return a **total count**
alongside its list, so extraction can compare the two and refuse a mismatch.

**The count must be the one the SOURCE reported, before any shaping the tool
does to its own output.** This is the whole specification and it is the part
that gets lost:

| tool behaviour | reports | signal |
|---|---|---|
| paginates, returns page 1 of 6 | `total: 1200`, 200 items | **detectable** — extraction refuses |
| caps its own output at 200 | `total: 200`, 200 items | **none** — indistinguishable from a complete inventory of 200 |

"Return a total" gets implemented as `len(results)` by default, and that is
precisely the version that provides no signal at all — it agrees with the list
by construction, in every case including the broken ones. The count has to come
from the API's own `TotalCount` / `NextToken`-implied remainder / result-set
size, captured before the tool decides what to print.

### Catalog sweep, 2026-09-20

Done once so the next person does not have to, and so a third convention is not
discovered after seven declarations are written against two.

**Truncation flags — one, and no competing convention.** Only `RD-203`
(`m365-mailbox-rule-audit`) reports its own shortening, under `truncated`.
Nothing in the catalog uses `has_more`, `is_complete`, `capped`, `limited`,
`incomplete` or `sampled`. `SubjectSource.truncated_field` names the field per
declaration anyway, so a future automation choosing differently costs one
declaration rather than a format change.

**Source totals — none. Not one enumerating automation reports one.** The
count-shaped fields that exist are per-item attributes, not list totals:

| field | automation | what it actually is |
|---|---|---|
| `attached_to_count` | RD-137 | ENIs one security group is attached to |
| `licence_count` | RD-201 | seats on one subscription |
| `prior_total` / `recent_total` | RD-141 | cost figures, not counts |
| `account_enabled` | RD-203 | a boolean; it matched a search for "count" |

**So every enumerated scope will be `source_verified: false` until the
automations are changed, and `uncheckedEnumerations()` will read 100%.**

That is the CORRECT reading, not a bug. It is worth writing down because a
copy-constructor defect produced the identical symptom once already
(`ScopeClaim.sourceVerified` silently dropped by `withCoverage`), and the next
person to see 100% will reasonably suspect the same thing. The distinguishing
check: a scope built from a tool declaring `total_field` reads `true`. If one
does and still reads `false`, that is the bug; if none do, that is the estate.

### Until that lands

An `enumerated` scope derived from a tool that reports no source total is a
claim **the platform cannot validate**. The reaper should treat it identically —
this is not a different coverage status, and inventing one would split the
vocabulary for no behavioural difference — but the scope element carries a flag
saying so, so that "which of our reaps rest on unverifiable enumerations" is a
query rather than a per-agent re-derivation. Cheap to record now and impossible
to reconstruct later, because the tool output it would have to be recovered from
is long gone.
