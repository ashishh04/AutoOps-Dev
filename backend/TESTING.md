# What these tests have to do to be worth their run time

Not a style guide. These are the specific ways tests in this repo have looked
like coverage and not been it, each found by a test that was green at the time.

## A persistence test that never re-reads is testing the object graph

`RunScopeServiceTest.reload()` was `runs.findById(runId)` — which, inside
`@DataJpaTest`'s single transaction, returns **the same instance the service
just mutated**. Every assertion about what was "stored" was reading the session.

Adding `flush()` + `clear()` broke exactly one of eleven assertions, and it was
`anInventoryThatPagedShortCompletesOverWhatItActuallySaw` — the partial-coverage
precedent the correlators were written to follow. It had stopped proving
anything about persistence and nobody could tell.

The same applies to `findAll()`: it runs the query, then resolves each row to
the instance already managed in the context, so the field values come from the
session. `FindingIngestServiceTest` routes every such read through a `stored()`
helper now.

```java
private AgentRun reload() {
    entities.flush();   // push pending writes to the database
    entities.clear();   // forget what the session remembers
    return runs.findById(runId).orElseThrow();
}
```

**`clear()` without `flush()` is worse than neither** — it discards pending
writes instead of writing them, so a service's own write vanishes and reads as
the service being broken.

## Test the layer that acts, not only the layer that thinks

`RunScopeTest` covered the shapes, the narrowing rules and the coherence
arithmetic — all the interesting reasoning — and `RunScopeService` had **no
tests at all**. But the reaper does not act on a `SubjectScope` object; it acts
on rows. A correct rule that is never written down reaps nothing; a correct rule
written down wrongly reaps the wrong things.

When the reaper lands, its predicate logic will be the interesting part and its
`UPDATE` semantics will be the part that deletes state. Write the second set
first.

## Assert the premise beside the vector

A regression test whose precondition has quietly stopped holding is a tautology
that passes forever. Two in one session:

- the astral-sort digest vector, which only tests anything while Java's natural
  `String` order really does disagree with UTF-8 byte order — so the test
  asserts that disagreement too;
- the 1,200-volume inventory proving extraction reads raw output, which only
  tests anything while that inventory really does exceed the compaction limit.
  The first version used skinny rows totalling 34 KB against a 120 KB limit:
  nothing was ever elided, and it would have passed forever.

If the value of a test depends on a condition, assert the condition.

## A property that holds on the parts does not survive a merge

Twice, in unrelated places:

- **Composition.** `" vol-2"` inside `"{region}/{volume_id}"` becomes
  `"me-south-1/ vol-2"`, which has no surrounding whitespace at all. A check
  after a template is checking the template, not the data — so each component is
  validated before composing.
- **Verification.** One source reporting a total and another not means the
  *union's* total is unknown; the missing count could be anywhere in it. So
  `source_verified` is true only when every contributing source reported one.

## The harness is narrower than production, always in that direction

`@DataJpaTest` has diverged four times, and each one made a test **wrong**
rather than absent:

| # | divergence | how it showed up |
|---|---|---|
| 1 | schema from Hibernate DDL, not Flyway | 18 green tests, then error 1071 on the real database |
| 2 | unflushed persistence context | raw-SQL sweep saw a database without the writes |
| 3 | no auto-configured `ObjectMapper` | context failed once a service needed one |
| 4 | `JSON` column wrapped as a string literal | every re-read scope unparseable — **and it concealed #1's shape: nothing re-read, so nothing noticed** |

The first three were worked around in place. The fourth moved `ScopeFixtureIT`
onto real MySQL (`@AutoConfigureTestDatabase(replace = NONE)` plus
`@DynamicPropertySource`), because by then the harness was hiding a coverage gap
rather than merely inconveniencing a test.

**Split assertions by what each backend does faithfully.** Materialised rows in
the H2 unit test; JSON document text in the IT on real MySQL. Better than
forcing both into one place and picking a backend that cannot do half the job.

## A fixture that builds both sides of an equality cannot test it

The generalisation of the one below, and the more expensive lesson.

Generating the *verdict* from the real producer caught a missing `verdict_id`.
The *agent row* was still hand-written — and it set the display name and the
graph ref to the same constant. Production sets them differently, attribution
compared the wrong one, and **every verdict from every Python agent was refused
as `foreign_run`** while 186 tests stayed green.

> A fixture asserts your model of the system. The parts you hand-write are
> exactly the parts where your model is not being tested.

So: wherever a test builds both sides of a comparison from one variable or
literal, it is structurally incapable of catching a mismatch between them, and
it passes while doing so. Make them differ, and assert the difference as a
premise — `assertThat(stored.getName()).isNotEqualTo(stored.getGraphRef())`
before the behaviour, so the test cannot quietly stop testing.

**Found by the same sweep:** `VerdictAttributionService`'s tenant check is
tautological on the internal path — `AgentRunService` passes
`run.getTenantId()`, which is then compared to `run.getTenantId()`. Not a bug
(it is real defence for the REST path that does not exist yet), but a check that
cannot fail reads as a check that passed. It is commented as such, and one test
drives it with a different tenant so the comparison is exercised somewhere.

## Fixtures at a service seam come from the producer

A test that builds its own JSON tests what somebody *believed* the other side
emits, and a seam is exactly where belief goes stale. `scope-fixtures/*.json` is
generated by running agent-runtime's real extraction and fold;
`tests/test_scope_fixtures.py` asserts the runtime still produces those bytes
and `ScopeFixtureIT` asserts agent-service still accepts them. Drift fails on
whichever side moved.

## Prefer tests that pin *why* over tests that pin *what*

Two design changes silently broke a guarantee in one session, and both were
caught because the test asserted the reason:

- `withCoverage()` dropped `sourceVerified` — caught by a test asserting that
  stamping coverage *preserves* the flag, not by one pinning its value;
- component validation moved behind composition — caught by a test asserting
  that a malformed id *refuses*, not by one pinning an id string.

A test pinning a value gets updated to match new behaviour, and the guarantee
moves without anyone noticing.

## Related

`MIGRATIONS.md` for the rules about schema change, the soak, and the
project-wide tiebreaker (*recoverable loss beats permanent wrong state*, and
*refuse to make a claim you cannot stand behind*).
