# Websockets

What real-time surfaces exist, what to use instead where they do not, and how
to poll without hurting the platform.

## The short version

There is **exactly one WebSocket in AutoOps**, and it is not for run data.

| Surface | Transport |
|---|---|
| Voice agent on the landing page | ✅ WebSocket — browser straight to ElevenLabs |
| Run progress and logs | Polling |
| Approvals inbox | Polling |
| Agent runs and steps | Polling |
| Alerts and incidents | Polling |
| Notifications | Polling |

No SSE endpoint either. If you are building against the API, you are polling.

Saying so plainly is better than a "real-time" claim you would discover was
polling the first time you opened dev tools.

## The one WebSocket

Real-time duplex audio has to be a direct browser-to-vendor socket — anything
proxied adds latency the conversation cannot absorb. That creates one problem:
the API key cannot be in the browser.

voice-agent exists to solve exactly that, and nothing else:

```
GET  /api/voice/config     -> { "enabled": true, "agentName": "Aegis-01" }
POST /api/voice/session    -> { "signedUrl": "wss://...", "expiresInSeconds": 900 }
```

- `config` carries **no secret, not even the agent id**. When `enabled` is
  false the page renders no talk button at all, so an un-keyed deployment
  degrades to "no button" rather than a dead one.
- `session` exchanges the API key for a URL **scoped to one conversation and
  valid for 15 minutes**. The browser connects with that.
- Both endpoints are anonymous — a landing-page visitor has no account — which
  is why the service rate-limits itself: 5 sessions per IP and 120 overall per
  10-minute sliding window. That cap is what protects the vendor bill, and the
  production profile refuses to start with it disabled.
- Enable authentication on the agent in the vendor dashboard so it is reachable
  only through signed URLs.

The microphone also needs a **secure context**: `localhost` counts, a plain-http
LAN address does not.

## Polling a run

```
POST /api/jobs/42/run            -> 202 { "runId": 5512 }
GET  /api/runs/5512              -> status, stepCompleted, stepTotal, log, output
```

Poll `GET /api/runs/{id}` until `status` is terminal — `SUCCEEDED`, `FAILED` or
`CANCELED`.

Reasonable cadence: **every 2 seconds while `RUNNING`**, backing off to 5–10
seconds for a long run. A step has a 60-second default budget and a 10-minute
hard cap, so sub-second polling buys nothing.

Inside a workflow run, the engine emits a progress callback per node —
`(node_id, title, finished, elapsed_ms, failed)` — and core-service writes it
onto the run. What you poll is that record, which is also why the run screen and
the API agree: they read the same rows.

## Polling an agent run

```
POST /api/agents/7/runs          -> 202, run starts PENDING
GET  /api/agent-runs/{runId}     -> the run plus every step, in order
```

The `202` is honest: the answer does not exist yet. An agent run can also park
in `AWAITING_APPROVAL` for up to two days, so a client must treat "no movement"
as a normal state and not a hang. Check whether the run is parked before
escalating.

Agent runs are also **two-thirds model time**, measured. Polling faster does not
make them finish sooner.

## Why polling, and what it costs

The reason is not laziness — it is the same reason the approval poller exists
rather than a callback. A run's state lives in a database row written by the
control plane. A push channel would need a second place that knows the state and
a way to reach a specific waiting client; a run parked before the last restart
has no socket to push into.

The honest cost, stated: **every alert list call reaches the alert engine**,
with no caching and no rate limit on that path. The console polls, and a lot of
open tabs during an incident is exactly when the engine is busiest. If you are
building a dashboard, poll alerts conservatively.

## Being a good client

1. Respect `Retry-After` on a `429`; see [Rate Limits](rate-limits.md).
2. Stop polling at a terminal status — do not keep asking about a finished run.
3. Back off as a run ages.
4. Add jitter across workers so they do not synchronise.
5. Prefer one poll of a list endpoint over N polls of individual items.
6. Remember the 600-requests-per-minute **tenant** budget is shared with every
   console tab your colleagues have open.

## If you need push

There is no supported push channel today. What works now:

- Have AutoOps call **you**: a workflow's `http` node makes an arbitrary
  outbound call, so a workflow can notify your system when it finishes.
- Have your system call AutoOps: `POST /api/hooks/{token}` starts a run.

Neither is a subscription to run state, and neither pretends to be.

## Related

- [Event Bus & Streams](../architecture/event-bus-and-streams.md)
- [Rate Limits](rate-limits.md)
- [REST API](rest-api.md)
