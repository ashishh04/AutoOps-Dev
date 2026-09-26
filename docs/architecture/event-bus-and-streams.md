# Event Bus & Streams

How work and information move between the parts of AutoOps — and, just as
importantly, how they do not.

## There is no message broker

AutoOps runs no Kafka, no RabbitMQ, no NATS. Nothing in the platform publishes
to a topic and nothing subscribes to one.

That is worth stating plainly, because "event-driven" is the shape people assume
when they see a dozen services. What AutoOps has instead is four movement
mechanisms, each chosen for a specific reason, and all four are legible from a
database row.

| Mechanism | Used for | Delivery |
|---|---|---|
| Internal HTTP with a shared secret | Service → service, synchronously | Request/response; a failure is visible to the caller |
| The `runs` table + a bounded pool | Queuing work for execution | At-most-once; the row is the record |
| Database pollers | Scheduled runs, approval verdicts | Pull, with a lease where it matters |
| Webhook ingress | External systems triggering a run | Push, one token per hook |

## Internal HTTP is the spine

Every cross-service call goes over `/internal/**` on the callee, guarded by a
shared `X-Internal-Token` — one secret per callee, never routed through the
gateway:

```
core ──► rundeck      execute a step (default mode)
core ──► job          execute a step, verify a credential
core ──► subscription entitlement checks
workflow ──► core     confirm the owning project, fetch run stats
agent ──► core        dispatch a tool, resolve model credentials, write audit
agent ──► workflow    validate a workflow tool target
runtime ──► core      read the platform timeline
```

Two rules make this safe to reason about:

- **Fails closed on the write path.** An unreachable peer is a refusal, not an
  assumption. `WorkflowClient` never treats an unreachable workflow-service as
  "no such workflow": `find` returns empty only on a real 404, and anything else
  throws `503 workflow_unavailable`. Collapsing the two would let an outage look
  like a deletion — which, in the governance and compliance paths, would
  silently report a project as having no workflows to gate.
- **Degrades on the read path, deliberately and narrowly.** A workflow list must
  still render when core-service is down, so run stats fall back to empty and
  complexity rules to the platform defaults. Note the direction of that
  fallback: the defaults keep complex workflows *gated*.

## The run queue is a table

Triggering a run inserts a `runs` row in `QUEUED` and returns `202`. A bounded
executor pool picks it up. There is no queue process, no broker topic and no
visibility timeout.

The consequence, stated honestly: **a run that was `RUNNING` when the process
died does not resume.** It is a row, not a message with a redelivery guarantee.
What the platform gives you instead is that the row is never wrong — the target
definition is snapshotted onto it, so you can always see exactly what was
supposed to happen.

## Pollers, and why they are pollers

Two places pull instead of being pushed, and in both the alternative was worse.

**The cron scheduler** polls the database every `SCHEDULER_POLL_INTERVAL`
(default 30 s) for jobs whose `next_run_at` has passed. Multiple replicas are
safe: `SchedulerLeaseService` holds a DB lease (90 s TTL, renewed each poll,
stealable only after it expires) so exactly one instance polls and a crashed
leader is replaced within the TTL.

**The agent approval poller** picks up a human's verdict every 15 s. A callback
would be the obvious design and does not work here: core-service cannot reach a
*specific paused loop*, and a run parked before the last restart has no loop to
call back into. Polling is what makes a run parked for two days
indistinguishable from one parked for two milliseconds.

## Webhook ingress

A webhook is the one genuinely inbound event path. `POST /api/hooks/{token}`
triggers its bound job or workflow; the run is stamped with trigger `WEBHOOK`.
The token is the whole credential, so an unknown token gets the same answer as a
disabled one — it never confirms that a hook exists.

See [Custom Webhooks](../integrations/custom-webhooks.md).

## The alert plane

The one place where AutoOps genuinely consumes a stream is alerts, and it does
so through a dedicated engine rather than a broker of its own.

```
a monitoring tool ──► alert-service /api/alerts/ingest/{type}
                        stamps the owner from a signed token
                                │
                                ▼
                      the alert engine (dedup, enrich, correlate)
                                │  scoped read
                                ▼
                      alert-service :8091 ──► console
```

alert-service holds **no state at all** — no JPA, no Flyway, no datasource.
Every alert is read from the engine on demand, because an alert's whole value is
that it is current. There is nothing to back up and no table that can drift out
of step with the alert plane.

Three properties are enforced rather than promised:

- **No engine JSON reaches a browser.** `AlertMapper` is an allow-list onto the
  view types, so the engine's internal ids, provider fields and dedup
  bookkeeping stop at the service. A test asserts it, because the failure mode
  is a field appearing in someone's dev tools.
- **Tenant scope is computed from the JWT, never accepted from the request.** No
  method takes a tenant id or a filter expression from a caller. Filtering
  happens in-process; the engine's query API takes a CEL expression, and
  building one from caller input would turn the tenant boundary into a
  string-escaping problem.
- **It fails closed.** An alert with no tenant label belongs to nobody and no
  tenant sees it.

An alert gets its owner at the ingest door: the signed key a customer pastes
into their monitoring tool names the tenant and the project, and the labels are
stamped from it **last and unconditionally**, so a payload carrying those keys
cannot claim someone else's alerts. Anything that bypasses that door and lands
straight in the engine has no label, belongs to nobody, and stays invisible —
the fail-closed rule working as intended.

## What "streaming" means in the console

There is exactly one WebSocket in the product, and it is not for run data: the
landing-page voice agent opens an audio socket straight to ElevenLabs using a
short-lived signed URL.

Everything else in the console **polls**. Run progress, the approvals inbox, the
alert list and agent runs are all `GET` requests on a timer. See
[Websockets](../api/websockets.md) for what that means if you are building
against the API.

Within a workflow run, the engine emits a progress callback per node —
`(node_id, title, finished, elapsed_ms, failed)` — which core-service writes
onto the run. The live run screen is reading those rows, not a socket.

## Related

- [Execution Engine](execution-engine.md)
- [High Availability](high-availability.md)
- [Custom Webhooks](../integrations/custom-webhooks.md)
