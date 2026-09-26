# AutoOps Platform

Multi-tenant automation SaaS. This repository contains the platform backend
services and the web frontend.

**Status (2026-08-04):** all eight services are built and tested — auth-service,
subscription-service, api-gateway, **core-service** (projects / jobs / runs /
governance / compliance), **workflow-service** (automation workflow
definitions), **agent-service** (AI agents), **job-service** (the runtime that
really executes steps) and **voice-agent** (Aegis-01, the landing-page voice
agent). The whole platform runs as one `docker compose up` stack; the frontend
is wired to real APIs everywhere except the node catalogue, which has no engine
yet.

**Product documentation lives in [`docs/`](docs/README.md)** — quickstart,
installation, architecture, workflow authoring, security, integrations and the
API reference. Those files are the single copy: the console renders them at
`/docs`, so editing one is editing the site. This README stays the
engineering-level view of the repository.

---

## 1. Architecture

```
Browser / SPA (:5173 — Vite dev proxy, or nginx in the compose stack)
        │  /api/**
        ▼
┌─────────────────────────────────────────────────────────────────┐
│  api-gateway :8080                                              │
│  • RS256 token validation via auth JWKS (kid-aware, cached)     │
│  • Anonymous: /api/auth/**, /oauth2/**, GET /plans, /api/voice  │
│  • X-Tenant-ID OVERWRITTEN from the token's tenantId claim      │
│  • CORS for the SPA                                             │
└────────┬──────────────────┬──────────────────────┬──────────────┘
         ▼                  ▼                      ▼
┌──────────────────┐ ┌────────────────────┐ ┌────────────────────────┐
│ auth-service     │ │ subscription-      │ │ core-service :8083     │
│ :8081            │ │ service :8082      │ │ Projects, jobs, RUNS,  │
│ Identity & token │◄┤ Plans / subs /     │◄┤ cloud integrations,    │
│ authority (SAS,  │ │ entitlements /     │ │ approvals, governance, │
│ RS256 + JWKS)    ├►│ payments           │ │ compliance, AUDIT      │
└────────┬─────────┘ └─────────┬──────────┘ └──┬──────┬──────────┬───┘
         ▼                     ▼               │      │          │
   MySQL autoops_auth   MySQL autoops_subscription    │          │ X-Internal-Token
                                                │      │          ▼
   ┌────────────────────────┐  X-Internal-Token │      │  ┌────────────────────────┐
   │ workflow-service :8086 │◄─────────────────►┘      │  │ job-service :8084      │
   │ Workflow DEFINITIONS   │                          │  │ Step execution runtime │
   │ (canvas, node limits)  │◄──┐                      │  │ (shell/py/ssh/rest/    │
   └────────────────────────┘   │ tools allow-list     │  │ terraform/kubectl/     │
   ┌────────────────────────┐   │ + shared automation  │  │ lambda) + credential   │
   │ agent-service :8087    │◄──┘ budget               │  │ verification.          │
   │ AI agents (persona +   │◄────────────────────────►┘  │ NO host port, NOT      │
   │ tools allow-list)      │                             │ routed by the gateway  │
   └────────────────────────┘                             └────────────────────────┘

   MySQL autoops_core / autoops_workflow / autoops_agent
   One MySQL 8.4 container
   Redis (OTP limits, SSO state, entitlement cache)
   Keycloak :8180 (optional — SSO only)

   voice-agent :8085 — see below.

   voice-agent :8085 — Aegis-01 on the landing page. Holds the ElevenLabs
   API key and mints 15-minute signed WebSocket URLs; the browser then opens
   the audio socket straight to ElevenLabs. Anonymous endpoints, rate-limited
   in-service. No database.
```

### Trust model

| Concern | Where it is enforced |
| --- | --- |
| Authentication (who are you) | auth-service — the only holder of the RS256 private key |
| Token validation | Every service, locally, via `GET /oauth2/jwks` (no shared secret exists anywhere) |
| Tenant identity | JWT `tenantId` claim. The gateway overwrites `X-Tenant-ID` with it — clients can never spoof another workspace |
| Roles (`ADMIN` / `CLIENT` / `PROVIDER`) | JWT `role` claim; each service enforces its own route rules |
| Entitlements (plan features) | subscription-service; consumed by auth-service `/api/auth/authorize` (fail-closed) |
| Revocation | `ver` (token_version) claim checked against the DB by auth-service; `logout-all`, offboard, and password reset/change bump it — every outstanding token dies instantly |
| Service-to-service | Shared `X-Internal-Token` on `/internal/**` (core→job, core→subscription, and the workflow/agent/core triangle — one secret per callee). Never exposed through the gateway; job-service publishes no host port at all |
| Tenant cloud credentials | AES-256-GCM at rest in core-service (`CLOUD_CRED_KEY`), decrypted only to hand to job-service for one call, never persisted there |

### Access-token claims (RS256, 15 min TTL, `kid` pinned)

`sub` (email) · `userId` · `role` · `tenantId` · `tokenType=access` · `status` ·
`ver` · `iss=autoops-auth-service` · `iat` / `exp`

---

## 2. Services

### 2.1 auth-service (:8081)

Identity and token authority. Spring Boot 3.4.2, Spring Authorization Server
(SAS 1.4), MySQL `autoops_auth` (Flyway V1–V11), Redis, BCrypt, Resend
(prod-only; dev prints OTPs to the console).

#### Endpoints

| Method | Path | Access | Purpose |
| --- | --- | --- | --- |
| POST | `/api/auth/register` | public | Create a PENDING admin in a **fresh workspace tenant** + email a verification code (202, no tokens) |
| POST | `/api/auth/register/verify` | public | Confirm the code → activate account → tokens |
| POST | `/api/auth/register/resend` | public | Re-send the verification code (neutral response) |
| POST | `/api/auth/login` | public | Password login (rate-limited, timing-oracle-hardened) |
| POST | `/api/auth/otp/generate` | public | Email a one-time sign-in code (neutral response) |
| POST | `/api/auth/otp/verify` | public | Code → tokens |
| POST | `/api/auth/password/forgot` | public | Email a reset code (neutral response) |
| POST | `/api/auth/password/reset` | public | Code + new password → revokes ALL sessions → fresh tokens |
| POST | `/api/auth/password/change` | bearer | Prove current password → rotate everything → fresh tokens |
| POST | `/api/auth/refresh` | public (refresh token) | Rotate the token pair (reuse detection) |
| POST | `/api/auth/logout` | public (refresh token) | Revoke the presented session (idempotent) |
| POST | `/api/auth/logout-all` | bearer | Revoke all sessions + bump `token_version` |
| POST | `/api/auth/authorize` | gateway Basic auth | Validate token + live `ver`/status + entitlement check |
| GET | `/api/auth/sso/initiate` | public | Start Keycloak OIDC (state + PKCE S256, single-use, Redis) |
| GET | `/api/auth/sso/callback` | Keycloak | Issue tokens; 302 to the SPA with tokens in the **URL fragment** |
| POST | `/api/auth/onboard` | bearer ADMIN | Create a user **in the caller's own tenant** |
| POST | `/api/auth/offboard/{id}` | bearer ADMIN | Disable + kill sessions (404 for other tenants' users) |
| GET | `/api/auth/me` | bearer | Current profile |
| POST | `/api/auth/webhooks/resend` | signed | Resend delivery events (Svix HMAC-SHA256, replay-windowed) |
| GET | `/oauth2/jwks` | public | Public keys (all keystore aliases published) |
| POST | `/oauth2/introspect` | gateway client | RFC 7662 (SAS-issued tokens only — user tokens go through `/authorize`) |

#### Security hardening (all live-verified)

- **Refresh tokens**: `{sessionId}.{48-byte-secret}`, only the SHA-256 stored,
  rotation on every refresh, **reuse detection revokes the whole session
  family** (persisted — `noRollbackFor` on the throwing path), `SELECT … FOR
  UPDATE` serializes concurrent rotations.
- **OTPs**: SecureRandom 6 digits, SHA-256 at rest, constant-time compare,
  5-attempt lockout (persisted), 5-min TTL, sent AFTER_COMMIT.
- **Registration**: fresh tenant per sign-up (client-supplied `X-Tenant-ID`
  ignored — no cross-tenant admin escalation), globally-unique email, Redis
  register lock (check-then-insert race), PENDING until the email is verified.
- **Password login**: per-account + per-IP rate limits; unknown-email and
  OTP-only-account paths burn a dummy BCrypt compare (no timing oracle);
  uniform `login_failed` errors (only a caller with the CORRECT password learns
  an account is unverified).
- **Password reset/change**: bumps `token_version` + revokes every session —
  a hijacker is evicted the moment the owner resets.
- **Tenant scoping**: onboard/offboard restricted to the caller's JWT tenant.
- **Key rotation**: every keystore alias is published in JWKS; only
  `JWT_KEYSTORE_ALIAS` signs; `kid` pinned in the JWS header. Rotate by adding
  a new alias, flipping the env var, and dropping the old alias after 15 min.
- **Ops**: hourly `PurgeService` retention sweep (OTPs 1d, sessions 30d, audit
  180d — `autoops.auth.retention.*`); `auth_events_total{type=…}` Prometheus
  counter (alert on `REFRESH_REUSE` / `RATE_LIMITED` spikes); `ProdSafetyGuard`
  refuses to boot prod with dev defaults; audit log with IP/tenant/session.

#### Database (`autoops_auth`, Flyway)

- `users` — email, BCrypt `password_hash` (NULL for OTP/SSO-only), role/status
  ENUMs, `tenant_id`, `token_version`, `keycloak_subject`
- `otp_entries` — hashed challenges, attempts, lockout, provider delivery status
- `refresh_token_sessions` — hashed secrets, rotation chain, `reuse_detected`
- `auth_audit_log` — 17 event types (ENUM)
- `oauth2_registered_client` / `oauth2_authorization` — SAS (gateway client)

### 2.2 subscription-service (:8082)

Plans, per-tenant subscriptions, entitlement decisions. Spring Boot 3.4.2,
MySQL `autoops_subscription` (Flyway V1 with seeded catalog), Redis (60 s
entitlement cache, evicted on any change). **Billing is stubbed**: subscribing
succeeds without charging; the flow is real (trial → active → cancel →
reactivate) so a payment provider can slot in later.

#### Plan catalog (seeded, mirrors the frontend tier matrix)

| Code | Price/mo | Projects | Nodes | Automations | History | Features |
| --- | --- | --- | --- | --- | --- | --- |
| STARTER | $49 | 5 | 10 | 100 | 30 d | CORE_AUTOMATION |
| TEAM | $99 | 25 | 50 | 500 | 90 d | + AUDIT_LOG, API_ACCESS |
| BUSINESS | $199 | 25 | 500 | 2000 | 180 d | + PREMIUM_TEMPLATES, ADVANCED_RBAC |
| ENTERPRISE | $399 | ∞ | ∞ | ∞ | 730 d | + PRIVATE_TEMPLATES, SSO |

#### Endpoints

| Method | Path | Access | Purpose |
| --- | --- | --- | --- |
| GET | `/api/plans` | public | Active plan catalog (pricing page) |
| GET | `/api/subscriptions/current` | bearer | Caller-tenant's subscription (`{"status":"NONE"}` if none) |
| POST | `/api/subscriptions/subscribe` | bearer ADMIN\|PROVIDER | Create (14-day trial) / change / reactivate — `{"planCode":"TEAM"}` |
| POST | `/api/subscriptions/cancel` | bearer ADMIN\|PROVIDER | Cancel at period end (access continues until then) |
| POST | `/api/entitlements/check` | bearer | `{"feature":"SSO"}` → `{"entitled":bool,"reason":…}`; feature optional (= "any live subscription?") |

Rules: tenant **always** from the JWT claim (never header/body); one
subscription per tenant (DB unique key); time-based transitions (trial expiry,
cancel-at-period-end) computed lazily on read — no scheduler needed while
billing is stubbed.

### 2.3 core-service (:8083)

The business platform: projects, jobs, runs, cloud integrations, approvals,
governance and compliance reporting. MySQL `autoops_core`. Every row is scoped
by the JWT `tenantId` claim — never a header. Workflow definitions and AI
agents used to live here too and now have their own services (2.8, 2.9). Full
detail in **`core-service/README.md`**; the essentials:

- **Subscription gate** — reads are never gated (a tenant can always see and
  export its own data); mutations ask subscription-service live and fail
  **closed** (`403` with the reason code, `503 entitlement_unavailable` if
  subscription-service is down).
- **Quotas** — projects, jobs and cloud integrations here; workflows and agents
  share one `MAX_AUTOMATIONS` budget enforced in their own services. Over-limit
  tenants after a downgrade are grandfathered: existing resources survive, new
  ones are blocked.
- **Still the hub for workflows** — the run engine, approval gate, governance,
  SCM sync and compliance evidence all read workflow definitions from
  workflow-service over a shared-secret `/internal` API, and both split-out
  services write their audit events back here so the trail stays single.
- **Runs** — `QUEUED → RUNNING → SUCCEEDED | FAILED | CANCELED` on a bounded
  async pool; the target's name and definition are snapshotted onto the run so
  history stays truthful after edits. Per-step `retries` / `continueOnError`,
  cron scheduling, cancel between steps, plan-bounded history retention.
- **Governance & compliance** — policies evaluated live against real workspace
  data (violations are computed on read, never stored) and point-in-time
  SOC 2 / ISO 27001 / HIPAA / PCI DSS / GDPR reports, PDF included.

### 2.4 job-service (:8084)

The step execution runtime — the Rundeck-style engine that actually runs each
step core-service hands it. Full detail in **`job-service/README.md`**.

- **Step types**: `command`/`agent`, `script`, `pyscript`, `ssh`, `rest`,
  `terraform` (OpenTofu, real `init` + `plan|apply|destroy`), `kubernetes`
  (real `kubectl`), `awslambda` (SigV4 Invoke), `azurefn`, `test`.
- **Credential verification**: `POST /internal/verify` checks a stored
  integration against the real provider (AWS STS, Entra ID, Microsoft Graph,
  Google OAuth, the cluster's `/version`) with read-only calls.
- **Containment**: this service runs arbitrary commands by design, so it has no
  host port and no gateway route, and requires `X-Internal-Token` on every
  `/internal/**` call. Inside the container, **each step leases its own
  throwaway OS user** (`su-exec`, pool of 8) with a private workspace, so one
  tenant's step cannot read another's decrypted credentials — and steps are
  refused outright rather than run as root. Steps inherit **none** of the
  service's environment (the internal token is not theirs to see), their whole
  process tree is force-killed at the timeout (default 60s, hard cap 10m), and
  captured output is capped at 16 KB.
- **Reproducible image**: digest-pinned bases, version-pinned apk packages,
  hash-locked pip requirements, fixed build timestamp — the same source always
  produces the same image digest.

### 2.4b workflow-service (:8086)

Automation workflow **definitions** — the canvas, its node count and the plan
limits on both. MySQL `autoops_workflow`. Full detail in
**`workflow-service/README.md`**.

- **Owns**: workflow rows, `MAX_AUTOMATIONS` (shared with agents) and
  `MAX_NODES`.
- **Does not own**: running a workflow, run history, approvals, or the
  tenant's complexity rules — those stayed in core-service, which is why the
  run path routes back to :8083.
- **Costs of the split, handled explicitly**: the owning project is confirmed
  over core-service's `/internal` on every write and **fails closed**; run
  stats and `requiresApproval` are fetched per list and **degrade** to
  empty/platform-defaults so a list still renders when core-service is down.

### 2.4d rundeck-service (:8090)

The adapter onto a tenant's **own** Rundeck server. MySQL `autoops_rundeck`.
Full detail in **`rundeck-service/README.md`**.

- **Why an integration, not a rebuild** — AutoOps already has a step runtime,
  a scheduler and run history. What it lacks is Rundeck's defining feature: a
  **node inventory and dispatch model** (node filters, fan-out across hundreds
  of hosts, a per-node result matrix). A customer who already runs Rundeck keeps
  their runbooks, ACLs and fleet where they are and drives them from here.
- **Stores the connection and nothing else.** Projects, jobs, options,
  executions, logs and nodes are read **live** on every request — the Rundeck
  console is a peer writer, so any copy would be wrong the moment someone edits
  a job there. The one exception is a **dispatch receipt**, kept for
  accountability: delete the connection or rotate the token and "who ran this on
  production" still has an answer.
- **The API token is the whole security story.** It is command execution on
  every node a job targets, so: AES-256-GCM under its own `RUNDECK_CRED_KEY`,
  never returned by any endpoint (only a 4-character hint), `https` enforced
  outside dev, and secure option values redacted before a receipt is written.
- **Running a job is a POST**, even though Rundeck exposes abort as a GET —
  both change production state, so a VIEWER may watch a deploy and may not stop
  it.
- **`/internal/rundeck/dispatch`** is built and tested for a future workflow
  step type; core-service does not call it yet.

### 2.4c agent-service (:8087)

AI agents: a persona plus a **closed allow-list of tools** — the project's own
workflows and jobs the agent may operate. MySQL `autoops_agent`. Full detail in
**`agent-service/README.md`**.

- **The allow-list is the security boundary** and now spans three services:
  jobs are verified against core-service, workflows against workflow-service,
  on every write. Validation **fails closed** — a 404, a timeout or a service
  that is down are all refusals.
- **Configuration, not a runtime**: no model provider is wired in, so nothing
  executes an agent yet.

### 2.5 voice-agent (:8085)

**Aegis-01**, the voice agent on the landing page. Full detail in
**`voice-agent/README.md`**.

Real-time duplex audio has to be a direct browser↔ElevenLabs WebSocket, so this
service exists to keep the API key out of that browser: it exchanges the key for
a **signed URL scoped to one conversation and valid for 15 minutes**, and the
page connects with that.

- **`GET /api/voice/config`** → `{enabled, agentName}`. Carries no secret, not
  even the agent id. The page renders no talk button when `enabled` is false,
  so an un-keyed deployment degrades to "no button" rather than a dead one.
- **`POST /api/voice/session`** → `{signedUrl, expiresInSeconds}`.
- **Anonymous, and that is the point** — a landing-page visitor has no account.
  Which means the **rate limiter is what protects the ElevenLabs bill**: 5
  sessions per IP and 120 overall per 10-minute sliding window, in-memory, no
  Redis dependency on the marketing page's critical path. The prod profile
  refuses to start with it disabled.
- Set `ELEVENLABS_API_KEY` + `ELEVENLABS_AGENT_ID` in `./.env`. Enable
  authentication on the agent in the ElevenLabs dashboard so it is reachable
  only through signed URLs.

### 2.6 api-gateway (:8080)

Spring Cloud Gateway MVC (Spring Cloud 2024.0.0). The platform's single entry
point and the **trusted gateway** the tenant model relies on.

- **Routes**: `/api/auth/**`, `/oauth2/**` → :8081 · `/api/plans/**`,
  `/api/subscriptions/**`, `/api/entitlements/**`, `/api/payments/**` → :8082 ·
  `/api/projects/**`, `/api/jobs/**`, `/api/runs/**`, `/api/cloud/**`,
  `/api/approvals/**`, `/api/compliance/**`, `/api/governance/**` (and the rest
  of the business surface) → :8083 · `/api/workflows/**` → :8086 ·
  `/api/agents/**` → :8087. **`POST /api/workflows/{id}/run` is the exception**:
  running is execution, so it is declared BEFORE the workflow-service route and
  goes to :8083. Route order is the rule that makes that work — first predicate
  wins.
  **job-service has no route at all** — it is reachable only from
  core-service, on the internal network.
- **Security**: valid RS256 access token required for everything except auth
  flows, `GET /api/plans`, and health. Validation is local via JWKS —
  the gateway never calls auth-service per request.
- **`TenantHeaderFilter`**: when a valid token is present, `X-Tenant-ID` is
  overwritten with the token's own claim before proxying (spoof-verified).
- **CORS** for `localhost:5173` / `3000`. Fine-grained authorization stays in
  the services — the gateway makes no business decisions.

### 2.7 frontend-web (:5173)

Vite + React. The dev proxy (and the nginx image in the compose stack) send
`/api/**` to the **gateway**.

**Real (wired to backend):** signup with email verification + plan selection,
login (password / email code / forgot-password), transparent single-flight
token refresh, `/auth/callback` SSO landing (reads URL fragment), Billing
(current subscription, plan changes, cancel), plan catalog, and the whole
workspace — projects, workflows, agents, jobs, executions, schedules,
approvals, cloud integrations, governance and compliance.

**Still mock:** the node catalogue, which has no engine behind it yet. The old
mock transport is gone: an unbranched resource name now throws `501
not_implemented` instead of quietly returning fabricated data.

---

## 3. Running locally

Prereqs: Docker Desktop, JDK 21, Maven, Node 18+.

### The whole platform in one command (recommended)

```bash
cp .env.example .env          # optional: real email, social login, secrets
docker compose up -d --build  # mysql, redis + all five services + the SPA
docker compose logs -f auth-service   # watch the OTP codes go by
```

Open **http://localhost:5173** — the browser only ever talks to the frontend
origin, which proxies `/api/**` to the gateway. This stack is self-contained:
its MySQL publishes host port **3308** (debug only) so it never clashes with
the `auth-service/docker-compose.yml` dev infra on 3307. Keycloak is not part
of it — password and OTP login work; the SSO buttons need the separate dev
Keycloak.

### Service by service (bare metal)

```bash
# 1. Infra — MySQL 8.4 (HOST PORT 3307 — many machines run a local mysqld on
#    3306), Redis 6379, Keycloak 8180 (optional, SSO only).
cd auth-service && docker compose up -d

# 2. Services (one terminal each)
cd auth-service          && DB_PORT=3307 mvn spring-boot:run -Dspring-boot.run.profiles=dev
cd subscription-service  && mvn spring-boot:run -Dspring-boot.run.profiles=dev
cd core-service          && mvn spring-boot:run -Dspring-boot.run.profiles=dev
cd workflow-service      && mvn spring-boot:run          # :8086
cd agent-service         && mvn spring-boot:run          # :8087
cd job-service           && mvn spring-boot:run          # optional, :8084
cd voice-agent           && mvn spring-boot:run          # optional, :8085
cd api-gateway           && mvn spring-boot:run

# 3. Frontend
cd frontend-web && npm install && npm run dev     # http://localhost:5173
```

Without `EXECUTION_MODE=remote` (the compose default), core-service runs steps
in **simulated** mode — it sleeps and succeeds rather than executing anything.
Start job-service and set `EXECUTION_MODE=remote` to run steps for real.

Dev notes:

- **OTP / verification codes are printed to the auth-service console**
  (`[DEV ONLY] OTP for … = 123456`) because Resend isn't configured locally.
  Codes expire after 5 minutes.
- Sign up at `/signup` → enter the console code → the selected plan's 14-day
  trial starts automatically → `/app` → **Billing** shows the real subscription.
- The dev profile auto-generates an ephemeral RSA key pair — restart auth-service
  and all outstanding tokens are invalid (by design; prod uses a PKCS#12 keystore).
- MySQL databases: `autoops_auth` (env-created), `autoops_subscription` and
  `autoops_core` (`auth-service/mysql-init/01-create-databases.sql`, first
  container init — a MySQL volume created before `autoops_core` existed needs
  it created by hand, see `core-service/README.md`).
- Swagger: `:8081/swagger-ui.html`, `:8082/swagger-ui.html` ·
  JWKS: `:8081/oauth2/jwks` · Prometheus: `/actuator/prometheus` (bearer).
- **Aegis-01** stays hidden on the landing page until `ELEVENLABS_API_KEY` and
  `ELEVENLABS_AGENT_ID` are in `./.env`. The microphone also needs a secure
  context — `localhost` counts, a plain-http LAN address does not.

## 4. Testing

```bash
cd auth-service          && mvn test    #  40 tests
cd subscription-service  && mvn test    #  21 tests
cd core-service          && mvn test    # 162 tests
cd workflow-service      && mvn test    #  16 tests
cd agent-service         && mvn test    #  19 tests
cd job-service           && mvn test    #  84 tests
cd rundeck-service       && mvn test    #  62 tests
cd voice-agent           && mvn test    #  36 tests
cd api-gateway           && mvn test    #  18 tests
cd frontend-web          && npm test    # 150 tests
```

406 tests, all hermetic — no network, no containers, no cloud account. One
job-service test is POSIX-only and skips on Windows; run that module in a Linux
container to execute it (see `job-service/README.md`).

The persistence suites run on H2 (MySQL mode, `ddl-auto=create-drop`, Flyway
off). The DataJpaTest classes disable test-managed transactions
(`NOT_SUPPORTED`) so **commit/rollback semantics are real** — this is what
catches bugs like security writes being rolled back by thrown exceptions (found
and fixed exactly that way).

job-service tests exercise real OS processes (bash/sh in a container, cmd.exe on
a Windows dev box), drive every cloud runner and credential verifier against a
loopback HTTP stub, prove the internal-token filter rejects a missing, wrong,
truncated or empty token before any controller sees the request, and prove a
step cannot read this service's environment or outlive its own timeout.

The step sandbox itself is an OS property, so it is verified against the built
image rather than in a unit test — the procedure (each step gets its own uid, a
concurrent step gets `Permission denied` on its neighbour's credentials, no
path back to root) is in `job-service/README.md`.

frontend-web runs Vitest + React Testing Library on jsdom with `fetch` stubbed
to throw, so a test that forgets to stub its responses fails instead of
reaching for a real backend. It covers the API transport (including the
single-flight token refresh), every payload mapper, the RBAC matrix, the route
guards, and the sign-in and sign-up flows — 56% of statements and 82% of
branches across `lib/` and `store/`. The 45+ app pages beyond Login and Signup
are not covered yet; see `frontend-web/README.md`.

Live E2E verified through the gateway (curl): register → verify → login →
`/me` → refresh → **reuse-detection family revocation** → password
forgot/reset → subscribe → entitlement allow/deny → auth `/authorize`
feature chain → tenant-spoof rejection → metrics.

## 5. Configuration reference (key env vars)

| Var | Default | Used by |
| --- | --- | --- |
| `DB_HOST` / `DB_PORT` / `DB_USER` / `DB_PASSWORD` | localhost / 3306 (dev profile: 3307) / autoops / autoops | auth, subscription |
| `REDIS_HOST` / `REDIS_PORT` | localhost / 6379 | auth, subscription |
| `JWT_ISSUER` | `autoops-auth-service` | all |
| `JWT_KEYSTORE_PATH` / `_PASSWORD` / `_ALIAS` | dev: ephemeral | auth (prod: required) |
| `ACCESS_TOKEN_TTL` / `REFRESH_TOKEN_TTL` | 15m / 30d | auth |
| `AUTH_JWKS_URI` | `http://localhost:8081/oauth2/jwks` | gateway, subscription |
| `AUTH_SERVICE_URL` / `SUBSCRIPTION_SERVICE_URL` | `:8081` / `:8082` | gateway, auth |
| `GATEWAY_CLIENT_ID` / `GATEWAY_CLIENT_SECRET` | gateway / gateway-secret (dev) | auth `/authorize` + introspection |
| `ENTITLEMENT_FAIL_OPEN` | `false` | auth (fail-closed entitlements) |
| `TENANT_REQUIRE_HEADER` | dev `false` / prod `true` | auth |
| `RESEND_API_KEY` / `RESEND_FROM_EMAIL` / `RESEND_FROM_NAME` / `RESEND_WEBHOOK_SECRET` | placeholders | auth (prod email). The from-address must be on a domain verified in Resend |
| `KEYCLOAK_ISSUER_URI` / `_CLIENT_ID` / `_CLIENT_SECRET` | `:8180/realms/autoops` | auth (SSO) |
| `SSO_SUCCESS_REDIRECT` | dev: `http://localhost:5173/auth/callback` | auth |
| `RETENTION_OTP` / `_SESSIONS` / `_AUDIT` | 1d / 30d / 180d | auth purge |
| `BILLING_PERIOD` / `ENTITLEMENT_CACHE_TTL` | 30d / 60s | subscription |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173,…` | gateway, auth |
| `CORE_SERVICE_URL` | `:8083` | gateway |
| `DB_NAME` | `autoops_core` | core |
| `EXECUTION_MODE` | `simulated` (compose: `remote`) | core — `remote` runs steps for real via job-service |
| `EXECUTION_POOL_SIZE` / `_STEP_TIMEOUT` / `_RETRY_DELAY` | 4 / 60s / 2s | core run engine |
| `SCHEDULER_ENABLED` / `SCHEDULER_POLL_INTERVAL` | `true` / 30s | core cron scheduler (single-instance) |
| `JOB_SERVICE_URL` | `http://localhost:8084` | core |
| **`JOB_INTERNAL_TOKEN`** | `dev-internal-token` | core + job — **change it**; it is the only guard on a service that runs arbitrary commands |
| **`SUBSCRIPTION_INTERNAL_TOKEN`** | `dev-internal-token` | core + subscription `/internal/**` |
| **`CLOUD_CRED_KEY`** | `dev-cloud-cred-key` | core — AES-256-GCM key for stored cloud credentials. Changing it orphans everything already encrypted |
| `STEP_TIMEOUT` / `STEP_MAX_TIMEOUT` / `STEP_OUTPUT_MAX_CHARS` | 60s / 10m / 16000 | job |
| `STEP_SANDBOX` / `STEP_SANDBOX_USERS` / `STEP_SLOT_WAIT` | `true` / 8 / 30s | job — per-step OS user; the pool size is also the concurrency ceiling |
| `STEP_ENV_PASSTHROUGH` | *(empty)* | job — extra env vars steps may inherit. Never a secret |

## 6. Known trade-offs (deliberate)

- **Tokens in `localStorage`** — XSS-exfiltratable; mitigate with a strict CSP
  or move the refresh token to an httpOnly cookie later.
- **Billing is stubbed** — no charges; `BillingProvider` boundary ready for Stripe.
- **Keycloak SSO** is fully implemented (state + PKCE, lazy beans so the
  platform boots without it) but needs an `autoops` realm configured to exercise.
- **`/oauth2/introspect` only knows SAS-issued tokens** (the gateway's own
  client-credentials tokens). Revocation-aware checks for user tokens go
  through `POST /api/auth/authorize` — this is intentional and documented.
- Secrets via env vars — move to a vault for production. The compose stack
  falls back to well-known dev values for `JOB_INTERNAL_TOKEN`,
  `SUBSCRIPTION_INTERNAL_TOKEN` and `CLOUD_CRED_KEY`; set real ones in `.env`
  anywhere that is not a laptop.
- **job-service `ssh` steps have no key material yet** — the runner works, but
  there is no SSH credential type alongside AWS/Azure/GCP/Kubernetes, and each
  step now has its own uid and `HOME`, so a mounted key would not be readable
  anyway. The fix is a credential type, not a mount (see
  `job-service/README.md` → Known gaps).
- **No container resource limits** — concurrency is capped by the step-user
  pool (8), but the compose entry sets no `cpus`/`mem_limit`, so a runaway step
  can still starve the container.
- **The cron scheduler is single-instance** and its runs carry no user token, so
  they skip the entitlement gate; two replicas would double-fire.
- No CI pipeline yet — tests run locally.

## 7. Roadmap

1. Stripe integration behind the existing billing stub.
2. SSH credentials as a first-class cloud-integration type + a key mount for
   job-service; an execution concurrency cap and container resource limits.
3. Keycloak realm setup + SSO end-to-end.
4. CI (build + test on push), image publishing, deploy manifests.
5. httpOnly-cookie refresh tokens + CSP.
6. The node catalogue — the last mock surface in the frontend.
