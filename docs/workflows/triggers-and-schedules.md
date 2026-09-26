# Triggers & Schedules

The four ways a run starts, and what each one does differently.

Every run records which one started it:

```
trigger:  MANUAL | SCHEDULE | WEBHOOK | AGENT
```

That field is not decoration. Two of the four bypass gates the other two go
through, and knowing which is which is how you read a run log honestly.

## MANUAL

A person pressing **Run**, or a `POST` with their own bearer token.

```
POST /api/jobs/{id}/run
POST /api/workflows/{id}/run
```

Both return `202 Accepted` with the run id. Both are **gated mutations**: the
tenant's subscription is checked live, and governance policies in `Enforced`
mode can block the run outright (`403 policy_scm_required`,
`403 policy_failure_budget`).

Inputs for a workflow's published form go in the body:

```
curl -X POST http://localhost:8080/api/workflows/12/run \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"inputs":{"service":"payments-api","hours":6}}'
```

## SCHEDULE

A job may carry a cron expression.

| Format | Example | Meaning |
|---|---|---|
| 5-field unix | `0 3 * * *` | 03:00 every day |
| 6-field Spring | `0 0 3 * * *` | The same, with seconds |

**Everything is evaluated in UTC.** The expression is validated when the job is
saved — an invalid one is rejected with `400 invalid_schedule` rather than
failing silently at 3am.

### How it fires

A database poller runs every `SCHEDULER_POLL_INTERVAL` (default 30 s), picks up
jobs whose `next_run_at` has passed, triggers them and advances the field.

Running more than one core-service replica is safe: `SchedulerLeaseService`
holds a DB lease (90 s TTL, renewed each poll, stealable only once expired), so
exactly one instance polls and a crashed leader is replaced within the TTL. A
job due during that gap fires late, not never.

### Two things scheduled runs skip

This is the important part of this page.

- **The entitlement gate.** A scheduled run carries no user token, so it does
  not ask subscription-service whether the tenant may still do this.
- **Enforced governance policies.** The cron scheduler is not blocked by
  `SCM_REQUIRED` or `FAILURE_BUDGET` in Enforced mode.

Both are accepted trade-offs, documented here rather than discovered. If a
policy must hold for scheduled work too, the enforcement belongs in the job.

### Scheduling stays in AutoOps

Steps run on an execution engine, but **schedules are never pushed into it**.
Two schedulers over one runbook is two answers to "why did this run", and the
one that is wrong is always the one nobody is looking at.

The same reasoning keeps the whole orchestration layer here: an engine could
run a job end to end, and that would take the approval gate, per-step retries,
`continueOnError` and cancel-between-steps with it. AutoOps dispatches one step
at a time on purpose.

## WEBHOOK

A webhook binds one token to one job or workflow.

```
POST /api/webhooks          { "name": "...", "projectId": 3,
                              "targetType": "JOB", "targetId": 42 }
GET  /api/webhooks
PUT  /api/webhooks/{id}     enable / disable / retarget
DELETE /api/webhooks/{id}
```

External systems then call the public fire endpoint:

```
curl -X POST http://localhost:8080/api/hooks/9f3c1a7e...

202 Accepted
{ "accepted": true, "runId": 5512 }
```

Notes that matter when you wire one up:

- **The token is the entire credential.** There is no signature, no tenant
  header and no bearer token on this path. Treat the URL as a secret and rotate
  it by deleting the hook and creating another.
- **The response is deliberately minimal.** A caller gets `accepted` and a run
  id, and nothing tenant-shaped. An unknown token and a disabled hook get the
  same answer, so the endpoint never confirms that a hook exists.
- **The hook records its own outcome.** `lastFiredAt` and `lastStatus` are on
  the webhook row, which is usually the fastest way to tell "the sender is not
  calling us" apart from "the run failed".
- Creating, updating and deleting a webhook are all audited
  (`WEBHOOK_CREATED` / `_UPDATED` / `_DELETED`).

See [Custom Webhooks](../integrations/custom-webhooks.md) for payload handling
and the ingest side.

## AGENT

An agent running one of its allowed tools produces a run with trigger `AGENT`.

The agent does not execute anything itself. It asks the control plane to
dispatch the target, which resolves credentials, applies the approvals gate and
writes the audit row exactly as it does for a person pressing Run. The same
holds for a workflow's `job` node.

If the target needs a human, an agent run **parks** in `AWAITING_APPROVAL` and
an admin approving it in the normal inbox **starts the run** — the loop then
attaches to *that* run rather than starting a second one. A workflow's `job`
node instead **fails** with the approval named, because a workflow holds no
resumable checkpoint. See [Approval Gates](approval-gates.md).

## Cancellation

```
POST /api/runs/{id}/cancel
```

Sets a flag the engine honours **between steps**. A step already executing runs
to its own timeout; cancel does not reach into a running process. A run that has
already reached a terminal state answers `409 run_finished`.

Cancel also wins over a pending retry: a step waiting out `EXECUTION_RETRY_DELAY`
does not get its next attempt.

## Related

- [Handling Failures](handling-failures.md)
- [Approval Gates](approval-gates.md)
- [REST API](../api/rest-api.md)
