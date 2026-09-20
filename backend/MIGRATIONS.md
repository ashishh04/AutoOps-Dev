# Database migrations

Every service here runs Flyway against MySQL 8.4. Two rules, and the reason for
both is the same single fact:

> **MySQL has no transactional DDL.** A migration that fails on its fourth
> statement leaves the first three applied, permanently. There is no rollback
> to reach for, because the transaction that would have carried one never
> existed.

## 1. Forward-only. No undo scripts.

We do not write `U__*.sql`, and we do not keep a "down" migration per "up".

The tempting alternative is worse than nothing. An undo script is only correct
if the forward migration applied *completely*, which is exactly the case where
you do not need it — the failure that makes you want a rollback is the
half-applied one, and an undo script written for the whole migration will
itself fail partway through the wreckage. What it buys is the feeling of a
safety net during the calm period when it is never used, and its absence at the
moment it is reached for.

**Recovery is forward.** If V6 is broken, V7 fixes it. If V6 is half-applied,
the operator drops what it created and deletes the failed history row (see
below), then a corrected V6 or a V7 goes in.

## 2. Expand / contract for anything destructive.

Never rename or drop a column in the same migration that stops writing it.
Three releases, and the gap between them is deliberate:

| Release | Migration | Code |
|---|---|---|
| **Expand** | add the new column, nullable, no backfill constraint | write BOTH, read the old |
| **Migrate** | backfill | write both, read the NEW |
| **Contract** | drop the old column | write and read the new only |

The cost is three deploys for one rename. What it buys is that at no point does
a running instance of the previous version encounter a schema it cannot serve —
which matters here because a rolling restart runs both versions at once, and a
`depends_on` does not stop that.

A column added and filled in one migration is fine. A column *removed* is not.

## Recovering a failed migration

This has happened once (`V6__findings.sql`, error 1071) and will happen again.

```sql
-- 1. What actually got applied? A failed migration is partial, not absent.
SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC;
SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE();

-- 2. Undo what it created, in FK order (children first).
DROP TABLE IF EXISTS finding_transitions;
-- ... and any ALTERs it managed before failing

-- 3. Remove the failed row, or Flyway refuses to start.
DELETE FROM flyway_schema_history WHERE success = 0;
```

Then fix the migration and restart the service. **Check the service actually
became healthy** — a failed migration stops the whole application context, so
the symptom is a container that never passes its healthcheck, not a log line
somebody happens to read.

This procedure is safe only while the tables are empty or disposable. Once a
table holds real data, dropping it is data loss, and the answer is a corrective
forward migration instead.

## Multi-phase rollouts: how long the soak is

Expand/contract across services has a middle phase that ends when a number
reaches zero and *stays* there. That number is the whole gate, and the decision
of when it has been zero for long enough is the one most likely to be made by
impatience on a Friday. So it is written down here rather than judged each time.

**The rule, for any phase that gates on a counter reaching zero:**

> **Soak = max(35 days, 2 × the slowest participating agent's cadence)**, during
> which **every** counter in the gate reads zero and the associated
> over-claim gauge stays flat. One violation restarts the clock — not the
> remaining days, the whole period.

Why those two terms:

- **Two cycles, not one.** One clean cycle proves an agent ran correctly once.
  Two proves it was not a one-off that happened to coincide with a quiet estate.
- **A 35-day floor.** The slowest plausible cadence is a monthly cost/FinOps
  sweep, and 35 days covers one of those plus the slack to cross a month
  boundary. Nightly agents prove themselves in a day and then wait, which is the
  intended cost: the gate is the slowest agent, not the average one.
- **Restarting the clock on any violation** is what stops the soak becoming a
  countdown that survives evidence against it.

**Today there is no scheduler.** Every agent run is started through the
authenticated API by a person, so "cadence" is not yet a fact this repo can
read, and 35 days is a floor that cannot be argued down rather than a computed
figure. When scheduling arrives, recompute the second term and take the larger.

### The current instance: making `run_id` mandatory

Phase A shipped in V9/V10. Phase C — rejecting verdicts that cannot be traced to
a scoped run — may ship only when, for the full soak period and **per agent**:

| Signal | Must read | Meaning if not |
|---|---|---|
| `verdict_attribution_daily.unattributed` | 0 | agent still emits with no run |
| `verdict_attribution_daily.unscoped` | 0 | half-wired: passes a run, declares no scope |
| `verdict_attribution_daily.out_of_scope` | 0 | scope describes less than the run examined |
| `verdict_attribution_daily.foreign_run` | 0 | emitting under another agent's coverage claim |
| `verdict_attribution_daily.late` | 0 | racing its own completion |
| `RunScopeService.coverageGaps().silent` | 0 | run declares nothing at all |
| `RunScopeService.overclaimSuspects()` | flat | claims `all`, examines little |

`attributed` must also be **non-zero** — an agent that emitted nothing has not
proved anything, and a column of zeros is not evidence of correctness.

**Expected readings on the first real runs**, so a correct system is not
mistaken for a broken one:

| signal | expected | why |
|---|---|---|
| `uncheckedEnumerations()` | **100%** | no catalog automation reports a source total yet (sweep, 2026-09-20). This is a pass, not a finding. |
| `unattributed` | non-zero until each agent ships | that is what phase B is |

The 100% reading is worth the note because a copy-constructor defect produced
exactly that symptom once (`ScopeClaim.sourceVerified` dropped by
`withCoverage`). **The distinguishing check:** a scope built from a tool that
declares `total_field` must read `source_verified: true`. If one does and reads
false, that is the bug. If none do, that is the estate.

Which means `sourceVerified` and `uncheckedEnumerations()` are **untested in
production** until at least one automation reports a real total — no number of
unit tests changes that. The first concrete ask on the catalog is therefore: pick
the easiest enumerating automation, return the count from the API's own response,
and confirm one scope reads `true` end to end.

**`out_of_scope` is the one that covers blast radius rather than duration.**
Scope is derived generically across all eight agents at once, which removed the
incremental rollout that would have caught an extraction bug on agent one. A
uniformly wrong extraction produces uniformly *clean* counters — every agent
declares, every verdict attributes, coverage gap reads zero everywhere — so no
amount of soaking finds it. `out_of_scope` fires on the contradiction instead:
a run with an opinion about a subject its own scope says it never examined.

### The reaper also gates on a dry run

The reaper is gated on all of the signals above, and on one more that is
specific to it.

Every component before it fails **loudly** (a migration refuses, an ingest
returns REJECTED) or **safe** (an undeclared scope reaps nothing). The reaper is
the first where a bug is *silent and destructive in the same step*: a wrong
predicate marks findings STALE, every counter stays healthy, and nobody notices
until somebody goes looking for a finding that should have been there.

So it ships in **dry-run first**: it writes what it *would* reap to its own
table and mutates nothing. Run for a full soak cycle alongside the real gate
counters, and diff intended-reap against the live backlog. That diff is the only
check that catches a wrong predicate *before* it acts — the alternative is
learning the predicate was wrong from its effects, which is unrecoverable by
construction.

Dry-run is a gate inside the reaper's own rollout, not a phase before it.

**And it is non-negotiable, because every instrument meant to catch a reaper
mistake has itself been broken at some point.** `overclaimSuspects` was dead
twice over from unrelated causes — an `agent_name` join that could never match,
and `COUNT(DISTINCT subject_id_hash)` over a column nothing populated. The
coverage gauge read 100% silent before scopes existed. `RunScopeServiceTest`
was reading the persistence context rather than the database. Two independent
single points of failure in one gauge means that gauge's design was written and
never validated.

A dry run does not depend on any gauge being alive. It is the only check that
works when the checks are broken.

**The dry run must record WHY, not only what.** A table of a thousand findings
marked for reaping says far less than one showing they all trace to a single
run's scope element. Each row needs the run that grounded it, which scope
element matched, and which coverage claim that element carried — otherwise the
diff tells you the count is wrong and nothing about which claim produced it.

### Decisions that are correct today and expire on a specific change

Both are right now and become wrong later, and both are easy to lose because
nothing fails when they stop being right.

| decision | correct because | expires when | then |
|---|---|---|---|
| `AgentRunService` logs and swallows a failed scope declaration or completion | nothing reads coverage, so a refused claim costs that run its reap while a throw would cost the report somebody asked for | **the reaper goes live** | a swallowed scope failure becomes a run that LOOKS covered and is not — revisit `declareScopeOnce` and `completeScope` in the same change, not afterwards |
| `VerdictAttributionService`'s tenant check is tautological | `AgentRunService` passes `run.getTenantId()`, which is then compared to itself; it is real defence for a path that does not exist yet | **`POST /findings` lands** | the tenant arrives from a caller's token and the run id from a request body — the check goes from ceremonial to load-bearing, and is the only thing stopping a verdict naming another tenant's run |

The second one also bounds a claim: asked today whether tenant isolation is
enforced on verdict ingest, the honest answer is *the check exists and is not
exercised by production traffic, because the only caller passes the value it is
compared against*.

### The tiebreaker

Three separate decisions here have come down to the same rule, so it is worth
naming rather than re-deriving:

> **Recoverable loss beats permanent wrong state.**

- The guardrail engine **fails closed**: an unclassifiable action is refused,
  not allowed.
- Scope narrowing **never widens**: a claim that cannot be proven smaller fails
  the run rather than being trusted.
- Verdict ingest **refuses an out-of-scope subject** rather than storing it: a
  finding no scope covers is permanently unreapable, where a refused one is
  re-emitted by the next run.

In each case the refused path costs work that can be redone, and the permissive
path costs correctness that cannot. When a new decision here is genuinely
balanced, this is the default.

### Its corollary: refuse to make a claim you cannot stand behind

The same rule shows up a second way whenever the system is uncertain rather than
merely constrained. Four places now answer "I am not sure" by **refusing**
rather than by recording something plausible:

| situation | what a plausible record would have been | what happens |
|---|---|---|
| the runtime cannot name a subject | hash the prose into a key | `stable_key: false`, ingest **rejects** |
| a verdict names a subject outside its run's scope | store it, reap later | **refused** (V11) |
| tool output arrives elided | enumerate what survived | **refuse the element** |
| extraction cannot read a result | declare an empty enumeration | **refuse the element** |

The last one is the sharpest, because an empty enumeration is a *valid* claim —
"I looked and found nothing" — that the reaper acts on. A parse failure
recorded as an empty list is a bug that reaps an entire subject kind while
looking like a clean estate.

So: an empty result may be declared empty. An **unreadable** result may not.

### And the shape that hides both

A field that safely defaults to the *alarming* value is worse than one that
defaults to silence. `ScopeClaim.sourceVerified` defaulted to false through a
copy-style constructor, so every completed scope read back as unverified —
nothing failed, and the gauge would have reported 100% unverifiable forever,
sending somebody to fix automations that were working. Defaults that generate
work are harder to notice than defaults that generate quiet.

## Why unit tests do not cover any of this

`@DataJpaTest` builds its schema from **Hibernate's DDL, not from Flyway**.
Every fast test in every service therefore validates a schema that no
environment ever runs. V6 had 18 green tests and still failed on the real
database.

`agent-service/src/test/java/.../SchemaInvariantsIT.java` is the answer: it
starts a real MySQL 8.4, runs Flyway to head, and asserts what MySQL does not
enforce for you — charset, named constraints, required columns, and index
key-length headroom. It runs in `mvn verify` (CI), not `mvn test`.

### Running the ITs on a Windows workstation

`mvn verify` inside the Maven container **cannot** start a Testcontainers
container here. Docker Desktop's socket proxy answers docker-java's `/info`
with a 400 and a stub pointing at `npipe://./pipe/docker_cli`, which a Linux
container cannot reach. The socket itself is fine — `curl` and the `docker` CLI
both work through the same mount — so this is Testcontainers' daemon discovery
specifically, not something this repo can fix.

```bash
cd backend/agent-service && ./run-its.sh            # all ITs
cd backend/agent-service && ./run-its.sh SchemaInvariantsIT
```

That starts a throwaway MySQL 8.4, hands it to the tests via
`AUTOOPS_TEST_MYSQL_URL`, and tears it down. `MySqlTestDatabase` falls back to
Testcontainers when that variable is absent, so **CI on Linux is unchanged**.

This replaced hand-validating each migration against a clone of the live
schema. That worked for V6–V10 and depended on somebody choosing to do it,
which is the wrong property for the reaper.

**Its most important assertion is that the migration runs at all.** MySQL
refuses an oversized index at CREATE time, so a schema violating the key limit
can never exist to be queried after the fact; the migration failing is the
detection. The queries around it cover the things that fail *silently* instead.

Copy that class into a service the first time it gets a migration worth
protecting.

## Index key length

MySQL's limit is **3072 bytes**, and utf8mb4 charges **4 bytes per character** —
so a `VARCHAR(512)` costs 2048 of the budget on its own, and two of them in one
composite index is error 1071.

- Prefix the wide column: `subject_id(191)`.
- If prefixes start colliding — check
  `COUNT(DISTINCT LEFT(col,191)) / COUNT(DISTINCT col)` once there is volume,
  and worry below ~0.99 — store a `BINARY(32)` hash and index that, keeping the
  full value as an unindexed column.
- The IT warns at 2400 bytes so the problem arrives as a failing test rather
  than as a failed production migration.
