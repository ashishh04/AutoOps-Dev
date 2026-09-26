# REST API

Everything the console does, AutoOps does over the same HTTP API. There is no
private back channel.

## Base URL and routing

One entry point: the gateway at `http://localhost:8080` in the reference stack.
It routes by path prefix, and **route order is the rule** — the first matching
predicate wins.

| Prefix | Service |
|---|---|
| `/api/auth/**`, `/oauth2/**` | auth-service |
| `/api/plans/**`, `/api/subscriptions/**`, `/api/entitlements/**`, `/api/payments/**` | subscription-service |
| `/api/projects/**`, `/api/jobs/**`, `/api/runs/**`, `/api/cloud/**`, `/api/approvals/**`, `/api/audit/**`, `/api/webhooks/**`, `/api/hooks/**`, `/api/secrets/**`, `/api/connectors/**`, `/api/governance/**`, `/api/compliance/**`, `/api/notifications/**` | core-service |
| `/api/workflows/**` | workflow-service |
| `/api/agents/**`, `/api/agent-runs/**` | agent-service |
| `/api/alerts/**`, `/api/incidents/**`, `/api/alert-providers/**` | alert-service |

**`POST /api/workflows/{id}/run` is the exception.** Running is execution, so it
is declared *before* the workflow-service route and goes to core-service.

The step runtime has no route at all. It is reachable only from core-service on
the internal network.

## Conventions

| | |
|---|---|
| Auth | `Authorization: Bearer <access token>` — see [Authentication](authentication.md) |
| Tenancy | From the token claim. Never send `X-Tenant-ID`; the gateway overwrites it |
| Content type | `application/json` |
| Async triggers | `202 Accepted` with an id; poll for the result |
| Not yours | `404`, not `403` |
| Errors | `{ "error": "<code>", "message": "<human text>" }` |

Branch on the `error` **code**, never the message. Codes are stable; messages
are written for people and get reworded.

## Errors you will actually meet

| Status | Code | Means |
|---|---|---|
| 400 | `invalid_definition`, `invalid_schedule`, `invalid_path`, `missing_tenant` | The request is wrong |
| 401 | — | Missing, expired, or non-access token |
| 403 | `no_subscription`, `trial_expired`, `subscription_expired`, `subscription_past_due` | The subscription gate |
| 403 | `quota_exceeded` | A plan limit; the message carries the max |
| 403 | `policy_scm_required`, `policy_failure_budget` | A governance policy in Enforced mode |
| 404 | `job_not_found`, `workflow_not_found`, `agent_not_found`, `approval_not_found` | Gone, or not yours |
| 409 | `workflow_exists`, `agent_exists`, `approval_pending`, `run_finished` | State conflict |
| 429 | — | Rate limited; see [Rate Limits](rate-limits.md) |
| 503 | `entitlement_unavailable`, `workflow_unavailable`, `tool_validation_unavailable` | A dependency is down and the path **fails closed** |

A `503` here is a deliberate refusal rather than a crash. An unreachable
workflow-service is never reported as "no such workflow": collapsing the two
would let an outage look like a deletion.

## Surface

### Projects

```
GET    /api/projects
POST   /api/projects
GET    /api/projects/{id}
PUT    /api/projects/{id}
POST   /api/projects/{id}/archive
POST   /api/projects/{id}/restore
```

### Jobs

```
GET    /api/projects/{projectId}/jobs
POST   /api/projects/{projectId}/jobs
GET    /api/jobs/{id}
PUT    /api/jobs/{id}
DELETE /api/jobs/{id}
POST   /api/jobs/{id}/enable | /disable
POST   /api/jobs/{id}/run                    202
```

### Workflows

```
GET    /api/projects/{projectId}/workflows
POST   /api/projects/{projectId}/workflows
GET    /api/workflows/{id}
PUT    /api/workflows/{id}
DELETE /api/workflows/{id}
POST   /api/workflows/{id}/enable | /disable
GET    /api/workflows/{id}/inputs            the published input form
GET    /api/workflows/{id}/readiness         what this workflow still needs
POST   /api/workflows/{id}/run               202   -> core-service
```

List and get responses carry `runsTotal`, `successRate`, `lastRunAt`,
`avgDurationMs` and `requiresApproval`. Those are fetched from core-service per
request and **degrade** rather than fail — a workflow list still renders when
core-service is down, with empty stats and platform-default complexity rules.
Note the direction of that fallback: the defaults keep complex workflows gated.

### Runs

```
GET    /api/projects/{projectId}/runs        newest 200, retention-bounded
GET    /api/runs/{id}                        with the log
POST   /api/runs/{id}/cancel                 409 run_finished if terminal
```

`GET /api/runs/{id}` is the polling endpoint: `status`, `stepCompleted`,
`stepTotal`, the log, and the output.

**Run history is bounded by the plan's `history_days`.** Older runs vanish from
lists and `404` by id. This never fails closed — if subscription-service is
down, history is unbounded until it recovers.

### Agents

```
GET    /api/projects/{projectId}/agents
POST   /api/projects/{projectId}/agents
GET    /api/agents/{id}
PUT    /api/agents/{id}
DELETE /api/agents/{id}
POST   /api/agents/{id}/enable | /disable    disable is the kill switch
PUT    /api/agents/{id}/model
POST   /api/agents/{id}/runs                 202, run starts PENDING
GET    /api/agents/{id}/runs
GET    /api/agent-runs/{runId}               the run plus every step, in order
POST   /api/agent-runs/{runId}/cancel
```

There is deliberately **no approve endpoint here**. An agent's approval is an
ordinary approval in the ordinary inbox.

### Approvals

```
GET    /api/approvals?projectId=
POST   /api/approvals/{id}/approve           admin only
POST   /api/approvals/{id}/reject            admin only
GET    /api/approvals/settings
PUT    /api/approvals/settings               admin only
```

### Cloud integrations

```
GET    /api/cloud/connections
POST   /api/cloud/connections                verifies against the real provider
DELETE /api/cloud/connections/{id}           disconnect — purges credentials
POST   /api/cloud/connections/{id}/verify
```

### Secrets, connectors, webhooks

```
GET/POST/PUT/DELETE  /api/secrets            values are WRITE-ONLY
GET/POST/DELETE      /api/connectors
POST                 /api/connectors/{id}/test    a real call
GET/POST/PUT/DELETE  /api/webhooks
POST                 /api/hooks/{token}      public, anonymous, 202
```

### Alerts and incidents

```
GET    /api/alerts?projectId=&status=&severity=&limit=
GET    /api/alerts/{fingerprint}
GET    /api/alert-providers
GET    /api/alert-providers/{type}/setup
POST   /api/alerts/ingest/{providerType}     public, signed X-API-KEY
GET    /api/incidents
POST   /api/incidents/{id}/status | /comment | /assign | /investigate
```

### Governance, compliance, audit

```
GET    /api/governance/summary
PUT    /api/governance/policies/{policy}     admin, { "mode": "..." }
GET    /api/projects/{projectId}/compliance/reports
POST   /api/projects/{projectId}/compliance/reports   { "framework": "SOC2" }
GET    /api/compliance/reports/{id}
GET    /api/compliance/reports/{id}/download          PDF
GET    /api/audit?projectId=&type=&limit=
```

Framework parsing is lenient — `SOC 2` and `pci-dss` both work.

### Billing

```
GET    /api/plans                            PUBLIC — no token needed
GET    /api/subscriptions/current            {"status":"NONE"} if none
POST   /api/subscriptions/subscribe          { "planCode": "TEAM" }
POST   /api/subscriptions/cancel             at period end
POST   /api/entitlements/check               { "feature": "SSO" }
```

### Notifications

```
GET    /api/notifications
GET    /api/notifications/unread-count
POST   /api/notifications/{id}/read
POST   /api/notifications/read-all
GET/PUT /api/notifications/preferences
```

## Two things there is no API for

- **Authoring workflows and agents.** Those are designed by the provider and
  rolled out to a workspace. `authorAutomation` is false for every client role
  including admin, and the backend enforces it — calling the endpoint yields a
  403, not an exception to the rule.
- **Creating a provider account.** The role exists and is granted directly in
  the database. An endpoint that mints platform operators is an endpoint worth
  attacking.

## Versioning and specs

There is no `/v1` prefix. Swagger UI is served by auth-service and
subscription-service at `/swagger-ui.html` in non-production profiles; it is
**off under `prod`**.

Treat this page and the service READMEs as the contract. If an endpoint is not
documented, it is either internal (`/internal/**`, shared-secret only, never
routed through the gateway) or provider-only.

## Related

- [Authentication](authentication.md)
- [Rate Limits](rate-limits.md)
- [Websockets](websockets.md)
