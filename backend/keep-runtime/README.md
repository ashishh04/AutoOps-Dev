# AutoOps Keep Runtime — the alert plane

[Keep](https://github.com/keephq/keep) ingests from 100+ monitoring tools
(Datadog, Grafana, Prometheus, CloudWatch, Sentry, …), deduplicates, enriches
and correlates alerts into incidents. AutoOps already knows how to *run* things
safely; what it had no source for was the **event**. That is the whole job Keep
does here.

`Dockerfile` here is upstream `keep-api`, pinned, plus a writable `/state` —
one `mkdir`+`chown` that Keep cannot do for itself. Read its header before
changing it; without that layer the container crash-loops invisibly.

## What runs

| File | What it adds |
|---|---|
| `Dockerfile` | upstream `keep-api:0.54.3` + a `keep`-owned `/state` |
| `docker-compose.keep.yml` | `keep-backend`, no host port, SQLite on `keep-data` |
| `docker-compose.keep-ui.yml` | **temporary**: Keep's own console + soketi, loopback only |

Append to `COMPOSE_FILE` in `./.env`, after the base file:

```
COMPOSE_FILE=docker-compose.yml;deploy-demo/docker-compose.demo.yml;docker-compose.keep.yml
```

## White-label

Identical terms to the execution engine, and for the same reason:

- the URL and API key come from **environment variables**, never a database row;
- `keep-backend` publishes **no host port**, and no gateway route points at it;
- Keep's own Next.js console is **not deployed** — `docker-compose.keep-ui.yml`
  is an operator tool bound to `127.0.0.1`, reached over an SSH tunnel, and is
  meant to be removed once the monitoring providers are configured;
- nothing tenant-facing renders the word Keep.

`AUTH_TYPE=DB` rather than Keep's `NOAUTH` default. NOAUTH would leave a full
alert/incident/workflow API answering any unauthenticated caller on the compose
network — the sharpness `docker-compose.aws.yml` already calls out for
`rundeck-service:8090`. DB auth is in Keep's OSS tier; `AUTH0`, `KEYCLOAK` and
`AZUREAD` are the `ee/`-licensed ones and are deliberately unused.

## How an alert becomes a run

```
Datadog / Grafana / CloudWatch / …
   │  Keep provider (webhook or poll)
   ▼
Keep: deduplicate → enrich → correlate
   │  workflow `autoops-dispatch`, trigger: alert, status=firing
   ▼
POST http://api-gateway:8080/api/hooks/{token}      ← permitAll at the gateway
   ▼
core-service WebhookService.fire → Run
   ▼
ExecutionEngine: approvals, retries, audit, artifacts
```

**Keep's workflow engine is used as a dispatcher and nothing more.** It is a
real orchestrator — triggers, steps, conditions, 100+ action providers — and
adopting it would put a third brain beside Dify and `ExecutionEngine`, taking
the approval gate, per-step retries and `continueOnError` with it. Those are the
product. The rule is the one already written for Rundeck: Keep is a sense organ,
AutoOps is the brain. **`autoops-dispatch` has one action and must keep having
one action.**

## Configuration

Set in `./.env` (untracked):

| Var | Default | Notes |
|---|---|---|
| **`AUTOOPS_HOOK_TOKEN`** | *(placeholder)* | the token from an AutoOps webhook. Nothing dispatches until this is real |
| **`KEEP_JWT_SECRET`** | `dev-…-change-me` | signs console sessions |
| **`KEEP_API_KEY`** | `dev-…-change-me` | minted at boot as the read-only `noc` role; what alert-service reads with |
| **`KEEP_INGEST_KEY`** | `dev-…-change-me` | minted at boot as `webhook` — write:alert only, cannot read the stream back |
| `KEEP_ADMIN_USER` / `KEEP_ADMIN_PASSWORD` | `keep` / `keep` | console login |
| `KEEP_NEXTAUTH_SECRET` | `dev-…-change-me` | console only |

Get `AUTOOPS_HOOK_TOKEN` from **Project → Webhooks → create**; the token is the
last path segment of the URL it shows you.

## Provisioning is declarative AND destructive

`KEEP_WORKFLOW` and `KEEP_PROVIDERS` / `KEEP_PROVIDERS_DIRECTORY` are
GitOps-style: on every boot Keep **deletes any provisioned resource that is not
in the env**. That is the property worth having, but it has a sharp edge —
*a workflow or provider created by hand in the console is deleted on the next
restart.* Configure providers in the console if you must, but treat anything
that has to survive as belonging in the compose file.

The dispatcher lives inline in `docker-compose.keep.yml` rather than in a
mounted file because the hook token is the **sole credential** on
`/api/hooks/{token}` and must not be committed. Compose substitutes it there
from the untracked `./.env`; a bind-mounted YAML file could not.

## Two traps, both hit while building this

- **A crash-looping Keep looks like a slow-booting Keep.** `restart:
  unless-stopped` restarts the container, and every restart resets the
  healthcheck's `start_period`, so the status sits on `health: starting`
  forever rather than going `unhealthy`. `docker compose ps` shows something
  that looks merely slow. Check `docker inspect -f '{{.RestartCount}}'` before
  believing it — 33 restarts is not a slow boot. The underlying cause was the
  `/state` ownership the Dockerfile now fixes.
- **`POST /workflows/{id}/run` reports acceptance, not outcome.** It answers
  `{"status": "success"}` for a workflow whose only action then failed. The
  truth is in `GET /workflows/{id}/runs/{execution_id}` — `status: error` plus a
  per-step log. Do not wire an integration test to the trigger response.
- **`POST /alerts/event/{provider_type}` answers 202 and silently drops a
  payload it cannot parse.** That path expects *that provider's own* native
  shape (a Prometheus webhook body, a Datadog body). Posting Keep's own alert
  shape to it is accepted, processed in the background, and stored nowhere —
  no error, no log line at the caller. Keep's own shape goes to the generic
  `POST /alerts/event`. Verify an ingest landed by reading `GET /alerts` back;
  the 202 means nothing.

## Verified on 2026-09-18

Against a live stack, `keep-backend` healthy in ~60s with 0 restarts:

- the dispatcher provisions from `KEEP_WORKFLOW` at boot and comes up enabled
  (`provisioned: true`);
- the read-only `noc` API key authenticates against `GET /workflows`,
  `GET /alerts` and `GET /incidents`, and alert-service reaches all three from
  inside its own container using its own configured key;
- **custom labels survive ingest unchanged** — an alert posted with
  `{"autoops_tenant": "acme", "autoops_project": "7"}` reads back with exactly
  those labels. That is the assumption the entire tenant boundary rests on, and
  it is now checked rather than believed;
- firing the dispatcher POSTs to `api-gateway:8080` and AutoOps answers **404**
  for the placeholder token — the gateway passed the anonymous POST through and
  core-service gave its documented "404 on any miss". The wire is complete; the
  only thing between that and a real run is a real `AUTOOPS_HOOK_TOKEN`.

## Known gaps

- **The alert payload is discarded.** `WebhookController.fire(String token)` has
  no `@RequestBody` (`WebhookController.java:113`), so the run starts but cannot
  know which host, which alert, or what fired it. The dispatcher sends a body
  anyway, so widening that endpoint is a one-sided change. **This is the single
  highest-leverage next change** — it turns "an alert started a job" into "an
  alert started a job that knows what broke".
- **One webhook, therefore one job, for every alert.** Routing different alerts
  to different jobs means either more dispatch actions (see the one-action rule
  above — prefer not) or Phase 1's alert→job binding, held in AutoOps.
- **No tenant boundary yet.** Keep runs single-tenant; every alert lands in one
  stream. Phase 1 stamps tenant/project at ingest and filters on the way out.
  Until then this is a single-workspace capability.
- **SQLite, so single-instance** — the same constraint the engine has with its
  stock H2. A second replica needs a real datasource first.
- **Not in CI.** `.github/workflows/build-images.yml` enumerates services
  explicitly and does not list `rundeck-service` either; nothing here is built
  or tested by CI.
- **Keep's HTTP provider blacklists `localhost`, `169.254.169.254` and the
  cloud metadata hostnames** by substring. `api-gateway:8080` is unaffected —
  but "helpfully" rewriting the dispatch URL to `localhost` breaks it.
