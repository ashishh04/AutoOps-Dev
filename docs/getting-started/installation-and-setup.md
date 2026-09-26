# Installation & Setup

Everything AutoOps needs to run, and every knob worth turning before you put it
in front of a customer.

## Topology

One `docker compose` project brings up the whole platform. The browser only
ever talks to the frontend origin, which proxies `/api/**` to the gateway.

```
Browser  ──►  frontend (nginx :5173)  ──/api/**──►  api-gateway :8080
                                                          │
   ┌──────────────┬───────────────┬──────────────┬────────┴───────┬──────────────┐
   ▼              ▼               ▼              ▼                ▼              ▼
auth :8081  subscription :8082  core :8083  workflow :8086  agent :8087   alert :8091
                                    │                            │
              ┌─────────────────────┴────────┐                   ▼
              ▼                              ▼            agent-runtime
   rundeck-service :8090              job-service :8084    (no gateway route)
              │   (no gateway route)  (no host port)
              ▼
   the Rundeck engine :4440
   (no host port)

 MySQL 8.4  ·  Redis  ·  Keycloak :8180 (optional, SSO only)
```

Several components are deliberately unreachable from outside the platform
network:

- **The step runtime** — rundeck-service and the engine behind it, or
  job-service — executes arbitrary commands, so none of it has a gateway route
  and the engine publishes no host port.
- **agent-runtime** is the model-facing reducer; nothing in it can reach a
  customer's infrastructure, and nothing outside the platform needs it.

## Prerequisites

| Purpose | Requirement |
|---|---|
| Running the platform | Docker Desktop (or any Docker with Compose v2) |
| Building a Java service outside its image | JDK 21 + Maven 3.9 |
| Building the console outside its image | Node 18+ |

There is no `mvn` on the standard dev machines here. Run a Maven build inside a
container instead:

```
docker run --rm -v "$PWD":/app -v "$PWD/../../.m2":/root/.m2 -w /app \
  maven:3.9-eclipse-temurin-21 mvn -B test
echo $?     # CHECK THE EXIT CODE ON ITS OWN LINE
```

> Piping a Maven build into `tail` or `grep` reports the exit code of `tail`,
> which is always `0`. That has produced a "green" build that had actually
> failed. Always read the exit code separately.

## First boot

```
cp .env.example .env
docker compose up -d --build
```

MySQL publishes host port **3308** in this stack, not 3306 — so it cannot clash
with a local `mysqld`, or with the separate `auth-service/docker-compose.yml`
dev infra on 3307.

Databases created on first init: `autoops_auth`, `autoops_subscription`,
`autoops_core`, `autoops_workflow`, `autoops_agent`, `autoops_rundeck`. A MySQL
volume that predates one of them needs it created by hand:

```
docker exec -i <mysql-container> mysql -uroot -p<rootpw> -e \
  "CREATE DATABASE IF NOT EXISTS autoops_core CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
   GRANT ALL PRIVILEGES ON autoops_core.* TO 'autoops'@'%'; FLUSH PRIVILEGES;"
```

Schema is Flyway-managed per service; each one migrates itself on startup.

## Execution mode

This is the single most consequential setting.

| `EXECUTION_MODE` | What a step does |
|---|---|
| `rundeck` | Runs on the Rundeck engine AutoOps operates, via rundeck-service. **Compose default** |
| `remote` | Runs in job-service, the in-house runtime. The rollback path — one variable and a restart |
| `simulated` | Sleeps 300–1500 ms and reports success. Nothing runs. Bare-metal dev default |

`simulated` exists so you can develop the orchestration layer without a runtime.
It is not a dry run and should never be the mode in any environment a customer
sees — the run log says `SUCCEEDED` for work that never happened.

`powershell` steps are **refused** under `rundeck` and run under `remote`. See
[Execution Engine](../architecture/execution-engine.md).

## Secrets you must change

The compose stack falls back to well-known development values. Set real ones in
`.env` anywhere that is not a laptop.

| Variable | Why it matters |
|---|---|
| `RUNDECK_INTERNAL_TOKEN` | Guards step execution for **every tenant on the platform** under the default mode |
| `RUNDECK_API_TOKEN` | Admin on the execution engine. The repository ships a **dev token** — override the mounted `tokens.properties` (or bake your own image) anywhere that is not a laptop |
| `JOB_INTERNAL_TOKEN` | The same guard on job-service, for `EXECUTION_MODE=remote` |
| `SUBSCRIPTION_INTERNAL_TOKEN`, `CORE_INTERNAL_TOKEN`, `WORKFLOW_INTERNAL_TOKEN`, `AGENT_INTERNAL_TOKEN` | Shared secrets on each service's `/internal/**` |
| `CLOUD_CRED_KEY` | AES-256-GCM key for stored cloud credentials. **Changing it orphans everything already encrypted** |
| `JWT_KEYSTORE_PATH` / `_PASSWORD` / `_ALIAS` | Production token signing. In dev the key pair is ephemeral — restarting auth-service invalidates every outstanding token, by design |

Every service ships a `ProdSafetyGuard` that **refuses to start** under the
`prod` profile while a dev default survives: a localhost JWKS URI, the default
DB password, fail-open entitlement checking, a missing alert-engine key, or a
disabled step sandbox.

## Configuration reference

### Identity and the edge

| Variable | Default | Used by |
|---|---|---|
| `JWT_ISSUER` | `autoops-auth-service` | all |
| `AUTH_JWKS_URI` | `http://localhost:8081/oauth2/jwks` | gateway, every service |
| `ACCESS_TOKEN_TTL` / `REFRESH_TOKEN_TTL` | `15m` / `30d` | auth |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173,http://localhost:3000` | gateway, auth |
| `RATE_LIMIT_ENABLED` / `_REQUESTS` / `_WINDOW` | `true` / `600` / `1m` | gateway — see [Rate Limits](../api/rate-limits.md) |
| `ENTITLEMENT_FAIL_OPEN` | `false` | auth, core — keep it false |
| `TENANT_REQUIRE_HEADER` | dev `false`, prod `true` | auth |

### Execution

| Variable | Default | Used by |
|---|---|---|
| `EXECUTION_MODE` | `simulated` (compose: `rundeck`) | core |
| `EXECUTION_POOL_SIZE` | `4` | core run engine |
| `EXECUTION_STEP_TIMEOUT` / `_RETRY_DELAY` | `60s` / `2s` | core |
| `SCHEDULER_ENABLED` / `SCHEDULER_POLL_INTERVAL` | `true` / `30s` | core cron scheduler |
| `RUNDECK_URL` / `RUNDECK_API_VERSION` | `http://rundeck:4440` / `50` | rundeck-service — API 44 is a **floor**, not a preference: JSON job import is rejected below it |
| `RUNDECK_STEP_TIMEOUT` / `_POLL_INTERVAL` / `_MAX_LOG_LINES` | `10m` / `2s` / `500` | rundeck-service |
| `RUNDECK_PROJECT_PREFIX` | `autoops` | One ACL glob covers every AutoOps project |
| `STEP_TIMEOUT` / `STEP_MAX_TIMEOUT` / `STEP_OUTPUT_MAX_CHARS` | `60s` / `10m` / `16000` | job |
| `STEP_SANDBOX` / `STEP_SANDBOX_USERS` / `STEP_SLOT_WAIT` | `true` / `8` / `30s` | job |
| `STEP_ENV_PASSTHROUGH` | *(empty)* | job — extra env vars steps may inherit. Never a secret |

Under `EXECUTION_MODE=remote`, keep `EXECUTION_POOL_SIZE` at or below
`STEP_SANDBOX_USERS`: the step-user pool
size is also the concurrency ceiling, and steps beyond it wait `STEP_SLOT_WAIT`
and then fail honestly rather than queueing forever.

job-service also needs an **init process** (`init: true` in compose). A
timed-out step's grandchildren get reparented to PID 1, and a JVM does not reap
processes it never spawned — without an init they accumulate as zombies until
the container runs out of PIDs.

The Rundeck engine is **slow to boot** (Grails plus schema init), which is why
its health check carries a two-minute start period. A shorter one marks it
unhealthy while it is still fine.

### Optional subsystems

| Subsystem | Turn it on with | Notes |
|---|---|---|
| Email (OTP, verification) | `RESEND_API_KEY`, `RESEND_FROM_EMAIL`, `RESEND_WEBHOOK_SECRET` | Without it, codes print to the auth-service console. **The from-address must be on a domain verified in Resend** — the default shared sender only delivers to the Resend account owner, and `prod` refuses to boot while it is set |
| SSO | `KEYCLOAK_ISSUER_URI`, `_CLIENT_ID`, `_CLIENT_SECRET`, `SSO_SUCCESS_REDIRECT` | Needs an `autoops` realm; see [Authentication (OIDC)](../security/authentication-oidc.md) |
| Landing-page voice agent | `ELEVENLABS_API_KEY`, `ELEVENLABS_AGENT_ID` | Stays hidden until both are set. Needs a secure context — `localhost` counts, a plain-http LAN address does not |
| Alert ingestion | `KEEP_API_KEY`, `KEEP_URL` | Read-only key; see [Custom Webhooks](../integrations/custom-webhooks.md) |
| The execution engine | On by default; `RUNDECK_API_TOKEN` + `RUNDECK_INTERNAL_TOKEN` | Invisible to customers by construction — see [Execution Engine](../architecture/execution-engine.md) |

## Running a single service outside the stack

Useful when you are changing one service and do not want to rebuild its image.

```
cd backend/auth-service && DB_PORT=3307 mvn spring-boot:run -Dspring-boot.run.profiles=dev
cd backend/core-service && mvn spring-boot:run -Dspring-boot.run.profiles=dev
cd frontend            && npm install && npm run dev     # http://localhost:5173
```

The Vite dev server proxies `/api` to `http://localhost:8080`; override the
target with `VITE_PROXY_TARGET`.

> **The console's nginx config is shadowed in the demo deployment.** `.env`
> merges the demo overlay, so editing `frontend/nginx.conf` has no effect
> there — `deploy-demo/nginx.demo.conf` is the file actually being served.

## Verifying the install

```
curl -s localhost:8080/actuator/health          # gateway
curl -s localhost:8081/oauth2/jwks | head -c 80 # signing keys are published
curl -s localhost:8080/api/plans                # anonymous: the plan catalog
```

Then run the test suites — 400+ hermetic tests, no network, no cloud account:

```
cd backend/core-service && docker run --rm -v "$PWD":/app -v "$PWD/../../.m2":/root/.m2 \
  -w /app maven:3.9-eclipse-temurin-21 mvn -B test; echo $?
cd frontend && npm test
```
