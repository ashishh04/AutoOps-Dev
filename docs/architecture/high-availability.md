# High Availability

What can be run as more than one replica today, what cannot, and what each
component does when a dependency disappears.

This page is deliberately specific about the gaps. A HA page that lists only the
good news is the one that gets read at 3am and then disbelieved.

## Replica safety, component by component

| Component | Safe to scale? | Why |
|---|---|---|
| **api-gateway** | Yes | Stateless. Token validation is local against cached JWKS — it never calls auth-service per request. Rate-limit counters live in Redis, so the limit does not multiply per replica |
| **auth-service** | Yes | All state is in MySQL/Redis. Refresh-token rotation serializes on `SELECT … FOR UPDATE` |
| **subscription-service** | Yes | Stateless; the entitlement cache is in Redis and evicted on change |
| **core-service (API)** | Yes | Stateless request handling |
| **core-service (scheduler)** | Yes, with a caveat — see below | A DB lease elects one poller |
| **workflow-service** | Yes | Stateless |
| **agent-service** | Yes | Loop state is the transcript column, not memory |
| **agent-runtime** | Yes | A stateless reducer; two calls in one run may land on different instances |
| **alert-service** | Yes | No datasource at all |
| **rundeck-service** | Yes | Stateless adapter; provisioning is idempotent |
| **the Rundeck engine** | **No** — see below | Single instance on a stock H2 file database |
| **job-service** | **Per-container only** — see below | The step-user pool is an OS resource inside one container |
| **voice-agent** | Partially | Its rate limiter is in-process, so N replicas allow N× the intended sessions |

## The scheduler lease

The cron poller would double-fire with two replicas, so it does not race:
`SchedulerLeaseService` holds a database lease with a 90-second TTL, renewed on
every poll and stealable only after it expires. Exactly one instance polls, and
a crashed leader is replaced within the TTL.

Two consequences to plan for:

- **Up to 90 seconds of no polling** after a leader dies. A job due in that
  window fires late, not never.
- **Scheduled runs carry no user token**, so they skip the entitlement gate.
  That is an accepted trade-off, and it is the same one the approval gate makes
  for cron.

## The execution engine is single-instance

Under the default `EXECUTION_MODE=rundeck`, every step for every tenant runs on
one Rundeck instance, and it uses the stock **H2 file database**. A second
replica needs a real datasource first. Today it is the platform's execution
single point of failure, and losing it stops all real execution — runs queue and
then fail rather than silently succeeding.

Two mitigations that are already in place: a step past `RUNDECK_STEP_TIMEOUT` is
**aborted** upstream rather than abandoned mid-change, and tenant isolation is a
Rundeck project per AutoOps project with a computed, sanitized, uniquely-keyed
name — so engine-side crowding cannot become a tenancy failure.

`EXECUTION_MODE=remote` rolls back to job-service, which is one variable and a
restart.

## job-service and the step-user pool

Concurrency in job-service is capped by a pool of throwaway OS users
(`STEP_SANDBOX_USERS`, default 8) *inside one container*. That is a real
isolation boundary — two concurrent steps hold different uids — but it does not
coordinate across containers.

So: more job-service containers give you more total step capacity, and each
container enforces its own ceiling. What they do **not** give you is a shared
queue; core-service talks to one `JOB_SERVICE_URL`, so scaling out means putting
a load balancer in front of it.

Keep `EXECUTION_POOL_SIZE` at or below `STEP_SANDBOX_USERS`. Past that,
core-service dispatches steps that will sit waiting `STEP_SLOT_WAIT` and then
fail.

> **No container resource limits are set.** Concurrency is capped by the step
> pool, but the compose entry sets no `cpus` or `mem_limit`, so a runaway step
> can still starve the container.

## Failure behaviour, dependency by dependency

The rule across the platform: **the write path fails closed, the read path
degrades.** A tenant can always see its own data; it cannot always change
things.

| If this is down | Then |
|---|---|
| subscription-service | Mutations get `503 entitlement_unavailable`. Reads are unaffected. Run history becomes unbounded (the plan's retention is unknown, and hiding a customer's data during an outage is the wrong failure) |
| workflow-service | Running or gating a workflow fails with `503 workflow_unavailable` — never "no such workflow". Dashboard counts degrade to 0 |
| core-service | Workflow lists still render: run stats fall back to empty and complexity rules to platform defaults. Creating a workflow **fails**, because the owning project cannot be confirmed |
| agent-service | Workflow creation counts 0 agents against the shared automation budget — a rare over-count that self-corrects, chosen over an outage in the primary feature |
| The step runtime (engine or job-service) | Steps fail. There is no local fallback; `simulated` mode is a development setting, not a degraded mode |
| Redis | The gateway rate limiter **fails open** and allows the request. A limiter that 503s when its bookkeeping store blips has converted a Redis incident into a total platform outage — it would be the most effective denial of service in the system |
| The alert engine | Alert endpoints return `502` with a body that names no vendor. Nothing else is affected |
| A model vendor | The node or agent step fails with the vendor's own error, which is the useful part. `ChatModels` refuses an unknown vendor rather than falling back — an agent configured for Bedrock that quietly ran on OpenAI would bill the wrong account and send the tenant's data somewhere they did not choose |

## Boot order

`depends_on` with health conditions orders the first `docker compose up`.

**`docker compose restart` does not re-apply it.** Restarting the stack brings
containers back in whatever order Docker chooses, so for roughly the first two
minutes the gateway can answer `500` for services that have not finished
booting. That is a race, not a fault — the tell is that it clears on its own.

## Data durability

There is **one MySQL instance** in the compose stack, holding every service's
database. It is the single point of failure in this topology, and nothing in the
platform works around it.

For anything beyond a demo, that means managed MySQL with replication and
point-in-time recovery. Note also that some data is not in git and never will
be: the provider script catalog and the user table exist only as a `mysqldump`
kept outside the repository. Losing the volume without that dump loses the
catalog.

## Known limits, collected

- Single MySQL instance in the reference topology.
- The execution engine is single-instance on a file database.
- Scheduler leadership gap of up to the lease TTL (90 s).
- Scheduled runs skip the entitlement gate and the enforced-policy block.
- A run that was `RUNNING` when its process died does not resume — see
  [State Management](state-management.md).
- job-service scale-out needs a load balancer; the step pool is per container.
- The voice agent's rate limiter is per process.
- `KEEP_MAX_FETCH` is a window, not pagination: a tenant whose alerts all fall
  outside the newest 1000 platform-wide sees nothing. Fine at current volumes,
  wrong at scale — and the fix is an ingest-side tenant index, not a bigger
  number.
- No CI pipeline covers every service; `rundeck-service` and `alert-service` are
  not enumerated in the workflow files.

## Related

- [Execution Engine](execution-engine.md)
- [State Management](state-management.md)
- [Rate Limits](../api/rate-limits.md)
