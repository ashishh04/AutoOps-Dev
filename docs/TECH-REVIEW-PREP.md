# Surviving a Spring Boot expert's review of AutoOps

Everything here was verified against the running stack on 2026-09-24. Commands
are copy-pasteable. Where we have a real weakness it is written down plainly,
because he will find it and the only losing move is to be surprised by it.

---

## 0. Get a token first (do this before he arrives)

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/dev/token \
  -H 'Content-Type: application/json' \
  -d '{"email":"ashish.appalabathula@intertecsys.com"}' \
  | python -c "import sys,json;print(json.load(sys.stdin)['accessToken'])")
```

`/api/auth/dev/token` is `@Profile("dev")` only — `DevTokenController.java:23`.
Say that out loud before he asks, or it looks like a backdoor.

Paste the token into jwt.io on the screen. The claims are the whole tenancy
story in one picture:

```json
{ "sub":"ashish...@intertecsys.com", "userId":2, "role":"ADMIN",
  "tenantId":"intertec-systems-1542f8a3", "tokenType":"access",
  "status":"ACTIVE", "ver":0, "iss":"autoops-auth-service", "exp":0 }
```

---

## 1. The 90-second architecture answer

Lead with the shape, not the feature list.

> Nine Spring Boot services behind one Spring Cloud Gateway. **Schema per
> service** — `autoops_auth`, `autoops_core`, `autoops_workflow`,
> `autoops_agent`, `autoops_alerts`, `autoops_plugin`, `autoops_rundeck`,
> `autoops_subscription` — no service reads another's tables, they talk over
> HTTP. auth-service is the only issuer of identity; everything else is a
> stateless OAuth2 resource server that validates RS256 locally against JWKS,
> so **no service calls auth on the request path**. Plus a Python
> `agent-runtime` for the LangGraph reasoning loop, because that ecosystem is
> Python and pretending otherwise would have cost us more than one container.

Confirm the schema-per-service claim live:

```bash
docker exec autoops-mysql-1 mysql -uroot -prootpass -e "show databases;"
```

**Why a gateway at all, if every service validates its own token?**
Routing, CORS, the rate limiter, and the tenant-header override. The gateway
does *coarse* authn; it deliberately makes **no business decisions** — that's
the comment at the top of `api-gateway/.../config/SecurityConfig.java`.

---

## 2. "Show me how an API actually hits"

Run this on screen. It's the money demo.

```bash
curl -i -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/projects
```

Then narrate the hops. Have these files open in tabs:

| # | Hop | File |
|---|---|---|
| 1 | nginx `/api/` to gateway | `deploy-demo/nginx.demo.conf:54` |
| 2 | CORS, CSRF off, STATELESS | `gateway/config/SecurityConfig.java:60` |
| 3 | JWT decoded against JWKS, `kid`-aware, cached | `SecurityConfig.jwtDecoder()` |
| 4 | **`tokenType=access` validator** | `SecurityConfig.accessTokenOnly()` |
| 5 | Rate limit — *after* authorization | `security/RateLimitFilter.java` |
| 6 | `X-Tenant-ID` overwritten from the claim | `security/TenantHeaderFilter.java` |
| 7 | Route predicate picks core-service:8083 | `gateway/application.yml` |
| 8 | Resource server again + role check | `core/config/SecurityConfig.java:64` |
| 9 | Controller reads tenant from the claim | `ProjectController` then `tenant(jwt)` |
| 10 | Repository is tenant-scoped in the query | `findByIdAndTenantId(...)` |

Point at the response headers he'll see — `X-RateLimit-Limit: 600`,
`X-RateLimit-Remaining: 599`. That's hop 5 proving itself.

### The two design decisions in that chain worth defending

**Rate limiter is registered `addFilterAfter(..., AuthorizationFilter.class)`.**
Before authorization the JWT isn't parsed yet, so every authenticated request
would fall back to IP — and a whole office behind one NAT would share a single
budget. Cost: unauthenticated traffic (webhooks, alert ingest) is keyed on
`request.getRemoteAddr()`, deliberately **not** `X-Forwarded-For`, because that
header is caller-supplied and trusting it lets anyone mint unlimited buckets.

**The limiter fails OPEN.** `RateLimitFilter.doFilterInternal` catches, logs
WARN, and calls `chain.doFilter`. A limiter that 503s when Redis blips has
turned a Redis incident into a total outage — it would be the most effective
DoS in the system. Same reasoning drives `management.health.redis.enabled:
false` in the gateway yml: with it on, a Redis blip fails the liveness probe
and the orchestrator kills the gateway, which is exactly the outage the
fail-open design was avoiding.

He may push on the fixed window. Get ahead of it: the window is part of the
**key**, not just the TTL (`bucketFor()` uses `currentTimeMillis()/window`).
With the window only as an expiry, a burst straddling a boundary is counted
against one bucket whose TTL keeps refreshing — the classic mistake that lets
through roughly 2x the limit. INCR+PEXPIRE is one Lua script so a crash between
them can't leave a TTL-less key pinning a customer at their limit forever.

---

## 3. "Prove tenant isolation" — the demo that wins the room

This is the single most convincing thing you can show. Ask him to pick the
attack himself, then run it:

```bash
# Forge another workspace's tenant header
curl -s -H "Authorization: Bearer $TOKEN" \
     -H "X-Tenant-ID: abc-72fc1cfd" http://localhost:8080/api/projects

# Same call, no forgery
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/projects
```

**Byte-identical responses.** Verified.

Then explain *why* it's belt-and-braces, which is the part that impresses:

1. `TenantHeaderFilter` wraps the request and **overwrites** `X-Tenant-ID` from
   the token's own claim before proxying. A client can't smuggle a tenant past
   the edge.
2. Even if it could, it wouldn't matter: core-service never reads that header.
   Every controller calls `tenant(jwt)`, which pulls `tenantId` off the claim
   and throws `missing_tenant` if absent (`JobController.java:111`).
3. And even if *that* were wrong, the repositories are scoped in the query —
   `findByIdAndTenantId`, `findByProjectIdAndTenantIdOrderByCreatedAtDesc`.
   There is no `findById` on a tenant-owned entity to accidentally call.

**Cross-tenant object by id returns 404, not 403** — verified. That's
deliberate: a 403 confirms the object exists and leaks the id space.

```bash
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/projects/1
# {"error":"project_not_found","message":"No such project"}
```

---

## 4. "Can I bypass the gateway?"

He will try `curl localhost:8083`. **Let him**, and have the answer ready.

```bash
curl -o /dev/null -w "%{http_code}\n" http://localhost:8083/api/projects   # 401
curl -H "Authorization: Bearer $TOKEN" http://localhost:8083/api/projects  # 200
```

Yes — 200. Do not flinch. The honest, correct answer:

> Every service is an independent resource server, so the gateway is not a
> security singleton — bypassing it costs you the rate limiter and the tenant
> header override, and neither is what enforces isolation. Tenancy comes from
> the signed claim, which I can't forge without auth-service's private key.
> The published port is a **dev-compose convenience**. In the deployed overlay
> it's gone.

Show him:

```bash
grep -n -A3 "internal services" docker-compose.aws.yml
```

```yaml
  # --- internal services; reachable by compose DNS, never from outside ---
  auth-service:     { ports: !reset null }
  core-service:     { ports: !reset null }
  workflow-service: { ports: !reset null }
```

Note auth-service, job-service and alert-service are **already** unpublished
even in dev compose (`docker ps` shows a bare `8081/tcp`). That's the pattern;
the others are the exception, not the rule.

### `/internal/**`

`InternalTokenFilter` guards them with a shared secret in `X-Internal-Token`,
constant-time compared. The gateway routes nothing to `/internal/**`, so it's
defence-in-depth inside the compose network.

The detail to volunteer, because it's the kind of thing he'll respect: a blank
expected token **refuses everything**. Without that guard,
`constantTimeEquals("","")` is true, so losing the config would *open* the
internal surface instead of closing it — wrong direction for the one credential
that can roll out and revoke agents.

---

## 5. "What if a service is down?"

Do this live. It takes 20 seconds and it's the best answer in the deck.

```bash
docker stop autoops-subscription-service-1

# a MUTATION
curl -i -X POST http://localhost:8080/api/projects \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"demo"}'
# HTTP/1.1 503
# {"error":"entitlement_unavailable","message":"Subscription check is temporarily unavailable — please retry"}

# a READ
curl -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/projects
# 200

docker start autoops-subscription-service-1
```

The narration:

> Mutations **fail closed** — we will not let someone provision infrastructure
> on an unpaid plan because our billing service is having a bad day. Reads never
> pass through the gate at all: a customer can always see and export their own
> data, even mid-outage. There's an `ENTITLEMENT_FAIL_OPEN=true` escape hatch if
> an operator decides availability matters more that night.

It's a 503, not a 500 — the client is told this is transient and retryable.
That distinction is the whole point.

**Timeouts:** every peer call goes through a `RestClient` with bounded
connect/read timeouts, one bean per peer (`*/config/RestClientConfig.java`).
Nothing uses a default-infinite `RestTemplate`.

**Token propagation:** core-service passes the **end user's bearer** to
subscription-service rather than a service account, so core can only ever ask
about the caller's own tenant. The tenant id is never sent as a parameter —
it's read from the token downstream. Confused-deputy closed by construction.

If he asks about circuit breakers: **we don't have Resilience4j.** Bounded
timeouts plus fail-closed is what we have. Say it plainly, and say it's the next
thing on the list if he's designing for more fan-out.

---

## 6. Own these before he finds them

You get far more credit for naming a flaw than for having none.

### 6.1 The catch-all swallows Spring's own exceptions — 500 instead of 4xx

This is the one he will find, probably within five minutes, and it is real.
All four of these return `{"error":"internal_error"}` with a 500:

```bash
curl -X DELETE ... /api/projects/9005            # should be 405
curl -X POST -d '{"name": ' ... /api/projects    # should be 400
curl -X POST -H 'Content-Type: text/plain' ...   # should be 415
curl ... /api/projects/abc                       # should be 400
```

And the nastiest symptom — **a typo'd path returns 500, not 404**:

```bash
/api/projects/9005/runs   # 200  (correct path)
/api/runs                 # 500  (no such mapping in core-service)
/api/zzz                  # 404  (no gateway route matches)
```

**Cause:** `GlobalExceptionHandler` is a plain `@RestControllerAdvice` with an
`@ExceptionHandler(Exception.class)` catch-all. It does not extend
`ResponseEntityExceptionHandler`, so `HttpRequestMethodNotSupportedException`,
`HttpMessageNotReadableException`, `HttpMediaTypeNotSupportedException`,
`MethodArgumentTypeMismatchException` and `NoResourceFoundException` all fall
into the catch-all. **All 8 services share this handler shape** — verified.

**What to say:**

> Correct, and it's the same bug in all eight services because they share the
> handler shape. The catch-all is doing its job — never leaking a stack trace —
> but it's too greedy: it's catching Spring's own MVC exceptions that already
> carry the right status. The fix is to extend `ResponseEntityExceptionHandler`
> so those keep their 4xx and the catch-all only sees genuinely unexpected
> throwables. One class per service, and trivial to test.

If you want to fix it before he arrives, that's the highest-value hour you could
spend. It is a small, well-contained change.

### 6.2 No optimistic locking

There is no `@Version` field on any core entity. Two concurrent `PUT`s to the
same job are last-write-wins. Uniqueness races *are* caught —
`DataIntegrityViolationException` maps to **409**, not 500, and the message is
deliberately generic because constraint text names columns and values. But
lost-update on a plain field is not defended.

> Deliberate for now: these are human-edited config objects, not a
> high-contention ledger. If he's worried about it, `@Version` on `Job` and
> `Project` is the fix and it's cheap.

### 6.3 Connection budget

```bash
docker exec autoops-mysql-1 mysql -uroot -prootpass \
  -e "show variables like 'max_connections'; \
      select count(*) from information_schema.processlist;"
```

**82 of 151 used at idle.** Eight services times Hikari's default 10, plus
overhead. Scale any service to two replicas and you will meet
`Too many connections`.

> Known. Hikari is on defaults, which is the wrong default for eight services
> against one MySQL. Pool sizes need setting explicitly per service and
> `max_connections` raised before we scale out horizontally.

Naming this unprompted is a strong move — it shows you think past the demo.

### 6.4 `/actuator/prometheus` is behind auth at the gateway

Only `health` and `info` are `permitAll`. Scraping has to hit the container on
the compose network. Fine in the current deployment, worth saying you know.

---

## 7. Points in your favour — make sure he sees them

Don't let these go unmentioned; they're exactly what an expert checks first.

- **`spring.jpa.open-in-view: false`** in every service. The Boot default is
  `true` and it's a well-known anti-pattern (lazy loads in the view layer,
  connections held for the whole request). Turning it off is a deliberate,
  informed choice. He will notice.
- **`ddl-auto: validate` plus Flyway.** Never `update`. The schema is owned by
  migrations and the app refuses to start if the entities disagree.
- **Forward-only migrations, expand/contract for anything destructive.**
  `backend/MIGRATIONS.md` explains why: MySQL has no transactional DDL, so an
  undo script is only correct in the case where you don't need it. Hand him
  that file — it reads like it was written by someone who has been burned.
- **`@EnableMethodSecurity` plus a role check at the edge:** VIEWER is
  read-only, enforced once in `SecurityConfig` for every POST/PUT/PATCH/DELETE
  under `/api/**`, so no controller has to remember it.
- **Refresh tokens cannot be used as access tokens.** Both are RS256-signed by
  the same key; the `tokenType=access` validator is what separates them.
  Demo: `curl -H "Authorization: Bearer $REFRESH" .../api/projects` gives 401.
- **N+1 avoided explicitly.** `JobController.list` does one batch stats query
  for the whole list (`runService.statsForProject`) rather than per-row
  lookups. The comment says so.
- **94 test classes**, including `SecurityConfigTest`, `RateLimitFilterTest`
  and `TenantHeaderFilterTest` — the security rules are unit-tested, and
  `accessTokenOnly()` is package-private specifically so it's testable without
  a JWKS fetch.

---

## 8. The two async questions he'll ask

### "Where does the work actually happen?"

`POST /api/jobs/{id}/run` returns **202 Accepted**, not 200. The run row is
persisted `QUEUED` and execution is handed to a bounded
`ThreadPoolTaskExecutor`. The client polls the run.

### "How do you know the worker doesn't see an uncommitted row?"

This is the good one. `RunService.submitAfterCommit()`:

```java
if (TransactionSynchronizationManager.isSynchronizationActive()) {
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
            @Override public void afterCommit() { submit.run(); }
        });
} else {
    submit.run();
}
```

> The engine only sees the run after its row is committed. The QUEUED
> notification rides the same hook — announcing a run that a rollback then
> erased would be reporting something that never happened.

### The follow-up: "why two thread pools?"

`ExecutionConfig` — and this is a bug we actually hit:

> A workflow run holds its thread while waiting on the runtime. If that workflow
> contains a `job` node, the runtime calls back to dispatch an automation, which
> needs a thread of its own. On one pool of four, four concurrent workflows
> occupy every thread, the jobs they spawn queue behind them forever, and each
> workflow waits for a job that can never start. Nothing errors — everything
> just stops. So workflow runs got their own pool. A workflow can now starve
> only other workflows, never the automations it depends on.

Telling him about a deadlock you diagnosed and fixed is worth more than any
clean answer.

### "What about multiple instances running the same cron?"

`SchedulerLeaseService` — DB-lease leader election. One atomic
`UPDATE scheduler_lease SET holder=?, expires_at=? WHERE name=? AND (holder=? OR expires_at<?)`.
90-second TTL so a crashed leader is replaced within two missed polls. Plain
SQL, no advisory locks, so it's portable to the H2 test DB.

---

## 9. Rapid-fire bank

| He asks | You say |
|---|---|
| Why not Feign? | `RestClient` with explicit per-peer timeout beans. Fewer magic proxies, timeouts visible at the config site. |
| Why no service discovery / Eureka? | Compose DNS plus env-var base URLs. Nine services on one host; Eureka would be infrastructure without a problem to solve. |
| Why no Kafka? | The only async work is run execution, already durable in the `runs` table with a bounded worker pool. A broker earns its place when we need fan-out or cross-service replay; today it's a second thing to operate. |
| Session state? | None. `SessionCreationPolicy.STATELESS` everywhere, CSRF disabled because there is no cookie to forge. |
| How do you revoke a token? | The `ver` claim against `users.token_version`. Bump the column and every outstanding token for that user is dead — that's how the ADMIN token was killed when the provider account was promoted. |
| Token lifetime? | 15 min access, refresh rotation via `RefreshTokenSession`. |
| Key rotation? | JWKS is `kid`-aware and can carry several keys at once; `JwtService` sets the `kid` header explicitly for exactly that reason. |
| Where are secrets? | `CredentialCrypto` encrypts at rest; `SecretService` never returns plaintext to a client. |
| Why `!reset null` rather than a second compose file? | Overlay composition — the base file stays the dev default and the AWS overlay states only the differences. |
| Why is there a Python service? | LangGraph and the agent tooling are Python. One container beats reimplementing that ecosystem in Java, and it sits behind the same internal-token boundary as everything else. |

---

## 10. Tone

Three things to hold onto:

1. **When he finds a bug, agree fast and specifically.** "Yes — and it's in all
   eight services because they share the handler. Here's the fix." That reads as
   ownership. Defending it reads as not understanding it.
2. **Every design decision here has a reason, and the reasons are in the code.**
   The comments in `RateLimitFilter`, `ExecutionConfig`, `MIGRATIONS.md` and the
   gateway yml are genuinely strong. Open them rather than paraphrasing — a
   reviewer trusts a comment written before he arrived far more than an
   explanation invented while he watches.
3. **"I don't know, let me check"** costs you nothing. Guessing wrong in front
   of someone who knows the answer costs you the rest of the review.
