# Handling Failures

What fails, what retries, what stops the run, and how to read the log
afterwards.

## What counts as a failure

| Layer | Fails when |
|---|---|
| A job step | Non-zero exit code, HTTP ≥ 400 on a `rest` step, or the step timeout |
| A workflow `http` node | A **transport** failure — DNS, TLS, timeout. A non-2xx **status is returned, not raised** |
| A workflow `llm` node | The vendor call throws; the vendor's own error is the message, because that is the useful part |
| A workflow `platform` node | No tenant or project on the run, an out-of-range window, or a control plane that refused |
| A workflow `job` node | The dispatched automation failed — **or the target needs an approval** |
| A reference | The named node or field does not exist in the scope |

The `http` case is the one people trip over. An API answering 404 is often the
*answer* a workflow is asking for, and a node that cannot observe it cannot
branch on it. Branch on `{{#check.status#}}` rather than expecting the node to
fail.

## Per-step reliability policy

Job steps carry their own policy, Rundeck-style, in the step JSON:

```json
{
  "type": "rest",
  "label": "Notify",
  "value": "POST https://hooks.example/done",
  "retries": 2,
  "continueOnError": true
}
```

| Field | Range | Behaviour |
|---|---|---|
| `retries` | `0`–`5` | A failed attempt waits `EXECUTION_RETRY_DELAY` (default 2 s), then re-runs |
| `continueOnError` | boolean | The step's failure is logged and the pipeline proceeds |

Every attempt is stamped in the run log:

```
[step 3/6] Notify
[attempt 1/3] connection refused
[attempt 2/3] connection refused
[attempt 3/3] 202 Accepted
```

**`continueOnError` still ends the run `SUCCEEDED`**, noting how many failures
were ignored. That is the intended meaning — a non-critical step — but it means
a green run can contain failures. Read the ignored-failure count before treating
green as clean.

**Cancel wins over a pending retry.** A step waiting out the retry delay does
not get its next attempt.

## Timeouts

| Bound | Default | Enforced by |
|---|---|---|
| Per-step wall clock | `60s` (hard cap `10m`) | job-service — force-kills the whole process **tree** |
| Step output capture | 16 KB, merged stdout+stderr | job-service |
| Waiting for a free execution slot | `STEP_SLOT_WAIT`, 30 s | job-service — then the step fails honestly rather than piling up |
| Agent tool watch | 10 m | agent-service — **stops watching, never cancels** |
| Agent approval | 2 days from run start | agent-service |
| Agent steps per run | 12, copied onto each run at start | agent-service |

Two of those deserve emphasis.

The step timeout kills the **process tree**, so a backgrounded child cannot
outlive its step. This is also why the container needs an init process: a
timed-out step's grandchildren are reparented to PID 1, and a JVM does not reap
processes it never spawned.

The agent tool timeout stops *watching*, and the model is told the job is still
running — which is true. Cancelling a long automation because the observer got
bored would be a worse failure than waiting.

The agent step budget is copied onto each run when it starts, so tightening the
platform default never kills a run already in flight.

## When a run stops

A failed step with no retries left and `continueOnError` false ends the run:

```
FAILED at step 3/6 — output captured up to that point
```

Steps after it do not execute. The captured output from the failing step is kept
— a failure with no output is a failure nobody can diagnose.

## Cancellation

```
POST /api/runs/{id}/cancel
```

The engine honours the cancel flag **between steps**. A step already executing
runs to its own timeout; cancel does not reach into a running process. A run
already in a terminal state answers `409 run_finished`.

## Failure modes that are not the run's fault

| Symptom | Cause | Fix |
|---|---|---|
| `403 no_subscription` / `subscription_expired` / `trial_expired` | The subscription gate | Renew. Reads keep working |
| `403 quota_exceeded` | A plan limit; the message carries the max | Delete something, or upgrade |
| `503 entitlement_unavailable` | subscription-service unreachable; mutations fail **closed** | Restore the service |
| `503 workflow_unavailable` | workflow-service unreachable. Never reported as "no such workflow" — collapsing the two would let an outage look like a deletion | Restore the service |
| `403 policy_scm_required` / `policy_failure_budget` | A governance policy in Enforced mode is blocking manual runs in this project | Fix the cause; violations are computed on read, so they clear |
| `409 approval_pending` | Another request for this target is already waiting | Decide the existing one |
| Step fails with "ambiguous integration" | Two cloud integrations match and the step named neither | Set the step's `connection` |
| Everything 500s for two minutes after a restart | `docker compose restart` does not re-apply `depends_on` | Wait |

## Reading the aftermath

A finished run shows two separate things, and mixing them up wastes time:

- the **output** — the deliverable;
- the **log** — the engine trace: node timings, attempt stamps, the inputs fed
  in, and which step stopped the run.

The target's name and definition are **snapshotted onto the run**, so a run from
March still shows the March definition even if the job was rewritten since. If
a run's behaviour does not match the job you are looking at, the run is right.

## Failure as a governance signal

`FAILURE_BUDGET` watches the last 30 days of finished runs per project: more
than 25% failures is a violation (with a minimum of 4 finished runs, so a
project with two runs is not judged). The default mode is Monitor; in **Enforced**
mode it blocks manual runs in violating projects.

The same window feeds compliance reports — above 25% fails the control, above
10% warns.

## Related

- [Execution Engine](../architecture/execution-engine.md)
- [Triggers & Schedules](triggers-and-schedules.md)
- [High Availability](../architecture/high-availability.md)
