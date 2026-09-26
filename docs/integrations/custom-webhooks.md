# Custom Webhooks

Letting an external system start an automation, and how alerts reach AutoOps
from the tools that raise them.

## Outbound versus inbound

Two different things share the word "webhook", and confusing them wastes an
afternoon:

| Direction | What it is | Where |
|---|---|---|
| **Inbound** | Something calls AutoOps to start a run | `/api/hooks/{token}` — this page |
| **Outbound** | AutoOps calls something else | Connectors, and a workflow's `http` node — see [Slack & Teams](slack-and-teams.md) |

## Creating an inbound hook

```
POST /api/webhooks
{
  "name": "Deploy finished",
  "projectId": 3,
  "targetType": "JOB",
  "targetId": 42
}
```

```
GET    /api/webhooks
PUT    /api/webhooks/{id}      rename, retarget, enable, disable
DELETE /api/webhooks/{id}
```

A hook binds **one token to one target** — a job or a workflow. The token is
generated server-side; you never choose it.

## Firing it

```
curl -X POST http://localhost:8080/api/hooks/9f3c1a7e...

202 Accepted
{ "accepted": true, "runId": 5512 }
```

The run is created with trigger `WEBHOOK`.

Four things about this endpoint are deliberate:

- **It is anonymous.** No bearer token, no tenant header. The gateway lets it
  through unauthenticated, because the caller is a CI system or a monitoring
  tool, not a person.
- **The token is the entire credential.** Treat the URL as a secret. Rotate by
  deleting the hook and creating another; there is no rotate-in-place.
- **The response carries nothing tenant-shaped.** Just `accepted` and a run id.
- **An unknown token and a disabled hook answer identically.** The endpoint
  never confirms that a hook exists — the same reasoning that makes another
  tenant's resource a `404` rather than a `403`.

### Payloads

The fire endpoint takes no body today. A POST from a system that always sends
one is fine — the body is ignored — but there is no way to map fields from it
into the target's inputs.

If the automation needs parameters, the honest options right now are: bake them
into the job, or have the caller use the authenticated
`POST /api/workflows/{id}/run` with an `inputs` object instead.

## Diagnosing a hook

The webhook row records its own outcome: `lastFiredAt` and `lastStatus`.

That is usually the fastest way to separate the two failures that look identical
from the console:

| `lastFiredAt` | Means |
|---|---|
| Never set | The sender is not calling you. Check the URL and the sender's egress |
| Recent, with a failed status | The call landed; the run is the problem |

Creation, update and deletion are audited (`WEBHOOK_CREATED`, `WEBHOOK_UPDATED`,
`WEBHOOK_DELETED`). Every run it starts is audited as `RUN_TRIGGERED` with the
`WEBHOOK` trigger.

## The alert plane

If what you actually want is "something went wrong in my monitoring, tell
AutoOps", that is a different and larger path.

```
a customer's monitoring tool
        │  POST /api/alerts/ingest/{providerType}
        │  X-API-KEY: <signed ingest token>
        ▼
alert-service :8091 ──► the alert engine ──► alert-service ──► console
 stamps the owner        dedup · enrich ·     scoped read
                         correlate
```

AutoOps could already run anything — on a schedule, a webhook or a human's click.
What it had no source for was the **event**: "the payments database is at 94%
disk". The engine is that source.

### Connecting a monitoring source

```
GET  /api/alert-providers                 the catalogue of supported sources
GET  /api/alert-providers/{type}/setup    what to paste into that tool
GET  /api/alert-providers/connected
POST /api/alert-providers
```

Connecting a source mints a **signed ingest token** scoped to one tenant, one
project and one provider type. The customer pastes the resulting URL and key
into their monitoring tool:

```
POST /api/alerts/ingest/datadog
X-API-KEY: <the signed token>
```

This is the public door, and it is unauthenticated in the JWT sense — Datadog
has no AutoOps login, so **the signed token is the credential**, exactly as it
is for `/api/hooks/{token}`. It travels in a header rather than the path, so it
stays out of proxy logs and browser history.

**This is where an alert gets its owner.** The token names the tenant and the
project, so the labels are stamped here, from a signed value, **last and
unconditionally** — a payload that already carries those keys cannot claim
another tenant's alerts, and a caller cannot omit them. Everything downstream
can trust the label because nothing else can write it.

The provider type in the token wins over the one in the path: they are minted
together, so a mismatch means a token is being reused for a source it was not
issued for. Every failure — bad signature, unknown project, wrong source — gets
**one identical answer**, so nobody can probe which half was wrong.

### Reading alerts and incidents

```
GET  /api/alerts?projectId=3&status=firing&severity=critical&limit=100
GET  /api/alerts/{fingerprint}
GET  /api/incidents
GET  /api/incidents/{id}
POST /api/incidents/{id}/status | /comment | /assign
POST /api/incidents/{id}/investigate
GET  /api/incidents/{id}/investigation
```

Alerts are a feed; incidents are a workflow with state someone owns, which is
why they are separate surfaces that change for different reasons.

`POST /api/incidents/{id}/investigate` is a POST even though it reads nothing:
it spends money and runs commands on a customer's estate. It sits behind the
viewer rule for the same reason — watching an incident is reading, launching an
investigation is not.

An alert outside your scope returns **404, never 403** — a 403 would confirm the
fingerprint exists. It is the same refusal `/api/hooks/{token}` makes.

### Tenant isolation

One engine serves every customer, so the boundary is a label on every alert,
checked on the way out. It is computed from the JWT and **never accepted from
the request**: no method takes a tenant id, a filter or a query expression from
a caller. Filtering happens in-process, because the engine's query API takes a
CEL expression and building one from caller input would turn the tenant boundary
into a string-escaping problem.

An alert is recognised as a tenant's own two ways: the stamped label, or the
**monitoring source it arrived through** — a source was connected by exactly one
tenant, so ownership of the source is ownership of its alerts. Both rules are
fail-closed: an alert with no label, from a source nobody connected, matches
neither and stays invisible. Showing a customer too little beats showing them
someone else's outage map.

`projectId` can only ever **narrow** a scope. A caller passing someone else's
project id gets nothing, not something.

No engine JSON reaches a browser: the mapper is an allow-list onto the view
types, so provider ids, API key references, dedup bookkeeping and internal ids
stop at the service. A test asserts it, because the failure mode is a field
appearing in someone's dev tools. No error message names the engine either — it
is "the alert engine", and a 502 body says that and nothing more.

### Known gaps, stated up front

- **Alerts that bypass the ingest door have no owner.** Anything posted straight
  into the engine's own provider webhooks carries no AutoOps label and belongs to
  nobody, so no tenant sees it. That is the fail-closed behaviour working, but it
  is worth knowing if alerts appear to vanish: check they came through
  `/api/alerts/ingest/{providerType}` with a signed key.
- **`KEEP_MAX_FETCH` is a window, not pagination.** Filtering happens after the
  fetch, so an alert outside the newest 1000 platform-wide is invisible even
  though it exists. Fine at current volumes, wrong at scale — and the fix is an
  ingest-side tenant index, not a bigger number.
- **No caching and no rate limit** on the alert path. The console polls, and a
  lot of open tabs during an incident is exactly when the engine is busiest.

### Reading alerts from a workflow

A `platform` node with `source: "incidents"` reads what is open right now,
scoped to the run's tenant and project by the service that answers. That is the
supported way to bring live incidents into an automation without handing it a
vendor credential.

See [DAG Syntax](../workflows/dag-syntax.md).

## Outbound calls from a workflow

The `http` node makes an arbitrary call mid-workflow, with references resolving
in the URL, headers and body. A non-2xx is **returned rather than raised**, so
you can branch on `{{#check.status#}}`; a transport failure fails the node.

## Related

- [Triggers & Schedules](../workflows/triggers-and-schedules.md)
- [Slack & Teams](slack-and-teams.md)
- [Event Bus & Streams](../architecture/event-bus-and-streams.md)
