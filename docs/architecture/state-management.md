# State Management

Where every durable fact in AutoOps lives, and which components deliberately
hold nothing.

## One database per service

| Database | Owner | Holds |
|---|---|---|
| `autoops_auth` | auth-service | Users, hashed OTPs, refresh-token sessions, the auth audit log |
| `autoops_subscription` | subscription-service | Plans, per-tenant subscriptions |
| `autoops_core` | core-service | Projects, jobs, runs, approvals, integrations, secrets, webhooks, governance, compliance, the single audit trail |
| `autoops_workflow` | workflow-service | Workflow definitions and node counts |
| `autoops_agent` | agent-service | Agents, agent runs, run steps, transcripts |
| `autoops_rundeck` | rundeck-service | The Rundeck project provisioned per AutoOps project, plus dispatch receipts |

No service reads another's tables. Everything crosses a `/internal` HTTP
boundary, which is what makes the ownership above enforceable rather than
aspirational.

Schema is Flyway-managed per service. Persistence tests run on H2 in MySQL mode
with test-managed transactions **disabled** (`NOT_SUPPORTED`) so commit and
rollback semantics are real — that is what caught security writes being rolled
back by a thrown exception.

> H2 will not catch everything. MySQL's index key-length limit is a real
> constraint that an H2 suite passes straight through; a unique key over wide
> `VARCHAR` columns has to be checked against MySQL itself.

## Redis is for things that expire

| Key class | TTL | Purpose |
|---|---|---|
| OTP rate limits and register locks | minutes | Stop a check-then-insert race and brute force |
| SSO state + PKCE verifier | one use | Single-use OIDC state |
| Entitlement cache | 60 s | Evicted on any subscription change |
| Gateway rate-limit counters | the window | Shared across replicas |

Nothing in Redis is a source of truth. Losing it costs a cache warm-up and some
re-authentication, not data.

## The run is the record

A run row carries the target's **name and definition snapshotted onto it**.
There is no foreign key back to the job or workflow, so editing or deleting the
target cannot rewrite history.

```
runs
  status          QUEUED | RUNNING | SUCCEEDED | FAILED | CANCELED
  trigger         MANUAL | SCHEDULE | WEBHOOK | AGENT
  target_type     JOB | WORKFLOW
  target_name     snapshot
  definition      snapshot
  step_completed / step_total
  log             the engine trace
  output          the deliverable
```

The output and the log are separate columns because they answer different
questions. The deliverable is what the customer asked for; the trace is node
timings, retry attempts and the inputs that were fed in. Merged, a customer
opens their report and reads engine bookkeeping first.

**Run history is bounded on read, not by a job that deletes.** The plan's
`history_days` (cached 60 s from the subscription) bounds list queries, and an
older run 404s by id. This never fails closed: if subscription-service is down,
history is unbounded until it recovers, because hiding a customer's own data
during an outage is the wrong failure.

## Workflow runs hold no checkpoint

A workflow executes as a compiled graph. The only thing nodes read is a
**scope** keyed by node id: every node writes its result there, and any node may
read any already-executed node's output through `{{#node.field#}}`.

There are no globals, no mutable workflow variables and no assignment. A canvas
where any node can rewrite any value is a canvas whose behaviour cannot be read
off the picture.

The graph library is used **for routing, not for durability**. There is no
checkpointer, on purpose: the run's real record is the database row the control
plane writes as each node finishes, and a checkpointer would be a second answer
to "what has this run already done". When those two disagree, the cost is a
side effect performed twice.

That choice has a visible consequence. When a workflow's `job` node hits a
target that needs an approval, the node **fails** rather than parking the graph
— parking would require persisting mid-flight state. The message names the
approval so an operator can decide it and re-run.

## Agent runs hold a transcript

Agents are the opposite case, because an agent run genuinely must survive a
two-day pause.

The loop's state is the `transcript` column, rewritten after every step;
resuming is reading it back. It is not a while-loop in memory — a run can park
for a human and not move again until Monday, across a redeploy.

`TranscriptCodec` writes that format by hand rather than through polymorphic
serialization, because a parked run makes the format a compatibility surface: a
renamed record must not silently strand every run in the queue. A corrupt
transcript is **refused** rather than resumed with the middle of the
conversation missing.

A model can request several tools in one turn, and vendors require all of them
answered together. If the second of three needs approval, the transcript parks
holding the results collected so far, and the resume path derives what is still
outstanding by comparing the assistant turn's tool calls against the results
already recorded. No extra bookkeeping column, and no result emitted twice.

## The reasoning runtime holds nothing

agent-runtime is a **stateless reducer**: `(state, event) → state'`. The whole
run state arrives in the request and leaves in the response. No database, no
cache keyed by run id, nothing on disk. The run id and tenant id in a request
are **correlation only**.

Two consecutive calls can land on two different instances, or on the same
instance three days apart across a redeploy, and neither case is special-cased
because neither case is different.

The refactor that would quietly destroy this is a callback: having the runtime
`POST` something it computed back to the control plane. It reads like a small
convenience, and it is not one — it means the runtime now owns a fact that has
to reach another system, so it acquires retries, ordering, and a failure mode
where a parked run depends on a call having succeeded hours ago. Everything the
runtime computes rides back on the response instead, and the control plane —
which already owns the run's lifetime and its transaction — writes it.

## Credentials

| Secret | Stored | Key |
|---|---|---|
| Cloud integration credentials | core-service, AES-256-GCM | `CLOUD_CRED_KEY` |
| Vault entries (`/api/secrets`) | core-service, AES-256-GCM, **write-only** — no endpoint returns a value | `CLOUD_CRED_KEY` |
| The execution engine's admin token | **An environment variable only** — never a database row, in a service with no gateway route | — |
| Model provider keys | core-service, fetched per run by agent-service, **cached nowhere** | `CLOUD_CRED_KEY` |
| Connector config (Slack URL, tokens) | core-service, AES-GCM, never returned | `CLOUD_CRED_KEY` |

Model credentials are deliberately not cached. A cached key would survive a
rotation the tenant believes took effect, and outlive a provider they just
disabled.

Changing an encryption key **orphans everything already encrypted with the old
one**. There is no re-wrap path.

## Findings

Agent output is not just prose. A finding is a structured verdict with a frozen
**idempotency key**, so the same condition observed on two runs deduplicates
into one row with a disposition rather than two rows that look like two
problems. A finding also carries an honesty flag recording whether its stable
key is genuinely stable, so a dedup that *cannot* be trusted says so instead of
silently merging two different things.

## What is not persisted anywhere

- Step execution state in job-service — scratch workspaces are deleted, and
  nothing about a step survives it.
- Alert data — read live from the alert engine on every request.
- Engine-side state. The execution engine's projects and executions are the
  engine's own; AutoOps keeps only the provisioned project name and a **dispatch
  receipt** per step, so that "who ran this on production" still has an answer
  after the engine's own history rolls off. A receipt records the step type and
  the engine execution id — never the step body and never the credential bundle.

  Receipts are per **step**, not per run: `StepExecutor` does not pass a run id,
  and widening that interface touches every executor including the simulated
  one.

## Related

- [Execution Engine](execution-engine.md)
- [High Availability](high-availability.md)
- [Vault Integrations](../security/vault-integrations.md)
