# AutoOps Alert Service

The adapter in front of **the alert engine** (port **8091**, Boot 3.4.2 / Java
21). It is to alerts what `rundeck-service` is to execution: the only thing in
the platform that knows the engine's address, and the reason nothing else has
to.

## What it is for

AutoOps could already run anything, on a schedule, a webhook or a human's click.
What it had no source for was the **event** — "the payments database is at 94%
disk". The engine ingests that from 100+ monitoring tools, deduplicates it,
enriches it and correlates it; this service is the face AutoOps puts on the
result. See `backend/keep-runtime/README.md` for the engine itself.

## White-label

Same terms as the execution engine, by construction rather than by policy:

- its URL and API key come from **environment variables**, never a database row;
- the engine container publishes **no host port**, and nothing but this service
  talks to it;
- **no engine JSON reaches a browser.** `AlertMapper` is an allow-list onto
  `AlertView` / `IncidentView`, so the engine's `providerId`, `providerType`,
  `apiKeyRef`, dedup bookkeeping and internal ids stop here. A test asserts it,
  because the failure mode is a field appearing in someone's dev tools;
- no error code or message names the engine. It is "the alert engine", and a
  502 body says so and nothing more.

## Tenant isolation

One engine serves every customer, so the boundary is **a label on every alert,
checked on the way out**. `TenantScope` is that boundary and holds it three
ways:

1. the scope is **computed from the JWT, never accepted from the request** — no
   method takes a tenant id, a filter or a query expression from a caller;
2. it **fails closed** — an alert with no `autoops_tenant` label belongs to
   nobody and no tenant sees it. That is deliberate and it has a cost: *until
   ingest stamps the label, tenants see an empty list.* Showing a customer too
   little beats showing them someone else's outage map;
3. filtering happens **in this process**. The engine's query API takes a CEL
   expression, and building one from anything a caller supplied would turn the
   tenant boundary into a string-escaping problem. It is instead a comparison
   between two values we already hold.

`projectId` can only ever **narrow** a scope. A caller passing someone else's
project id gets nothing, not something.

PROVIDER sees everything — it is the operator role, it already reads every
tenant's usage and audit, and the alert plane is infrastructure it runs.

## The surface

| Method | Path | Notes |
|---|---|---|
| GET | `/api/alerts` | `projectId`, `status`, `severity`, `limit` — all optional, all narrowing |
| GET | `/api/alerts/{fingerprint}` | **404**, never 403, for an alert outside the scope |
| GET | `/api/incidents` | PROVIDER-only — see below |

**Read-only, deliberately.** The key this service holds is provisioned as the
engine's read-only `noc` role, so a bug here cannot mutate a customer's alert
stream. Acknowledging and resolving belong in the tool that raised the alert.
Adding a write means widening that role on purpose in
`docker-compose.keep.yml`, not discovering that it works.

**404 rather than 403** on someone else's alert. A 403 would confirm the
fingerprint exists — the same probe `/api/hooks/{token}` refuses to answer.

**Incidents are PROVIDER-only, and that is a boundary rather than a
convenience.** An incident carries no labels: it is a correlation *over* alerts
and the engine does not copy their labels onto it. The honest options were to
resolve every incident's alerts on every list call, or to not show them to
tenants yet. Guessing from `services` would be a boundary made of a naming
convention.

## No state

No JPA, no Flyway, no datasource, no migration. Every alert is read from the
engine on demand, because an alert's whole value is that it is current. There is
nothing here to back up and no table that can drift out of step with the alert
plane.

The cost is `max-fetch`: filtering happens after the fetch, so an alert outside
that window is invisible even though it exists. That is the price of not handing
the engine a caller-built query, and it is the right trade while the alternative
is a tenant boundary made of escaping.

## Configuration

| Var | Default | Notes |
|---|---|---|
| **`KEEP_API_KEY`** | *(unset)* | the engine's read-only key. Nothing lists without it |
| `KEEP_URL` | `http://keep-backend:8080` | the engine, internal name only |
| `KEEP_READ_TIMEOUT` | `10s` | over 30s refuses to boot under `prod` |
| `KEEP_CONNECT_TIMEOUT` | `3s` | |
| `KEEP_MAX_FETCH` | `1000` | the window scanned, not the page returned |
| `AUTH_JWKS_URI` | localhost | |

`ProdSafetyGuard` refuses to boot under `prod` with a missing or default engine
key, a localhost JWKS URI, or a read timeout long enough to pin request threads
through an engine outage.

## Run

```
docker run --rm -v "$PWD":/app -v "$PWD/../../.m2":/root/.m2 -w /app \
  maven:3.9-eclipse-temurin-21 mvn -B test        # 23 hermetic tests
docker compose -f docker-compose.yml -f docker-compose.keep.yml up -d alert-service
```

There is no `mvn` on the dev machines — use the container. Check the exit code
**on its own line**; piping the build into `tail` reports the exit code of
`tail`, which is always 0.

## Known gaps

- **Nothing stamps `autoops_tenant` yet.** Alerts reach the engine through its
  own provider webhooks, which know nothing about AutoOps projects, so today
  every alert is unlabelled and **every tenant's list is empty while PROVIDER
  sees them all.** This service is correct and the boundary is enforced; what is
  missing is the ingest side that makes it useful. That is the next piece of
  work and it is the one that unlocks the whole module for customers.
- **Incidents have an API and no screen.** Deliberate: see above.
- **`max-fetch` is a window, not pagination.** A tenant whose alerts all fall
  outside the newest 1000 across the platform sees nothing. Fine at current
  volumes, wrong at scale — the fix is an ingest-side tenant index, not a bigger
  number.
- **No caching and no rate limit.** Every list call reaches the engine. The
  console polls; a lot of open tabs during an incident is exactly when the
  engine is busiest.
- **Not in CI.** `.github/workflows/ci.yml` and `build-images.yml` enumerate
  services explicitly and do not list `rundeck-service` either.
