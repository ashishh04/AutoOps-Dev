# Slack & Teams

Getting AutoOps to tell someone something — what exists today, and what does
not.

## What exists today

| Surface | Status |
|---|---|
| In-app notifications, with per-member mutes | ✅ Delivered |
| Email (verification codes, sign-in codes) | ✅ Via Resend |
| Slack incoming webhook as a stored, testable connector | ✅ Stored, encrypted, test-callable |
| Teams via a generic webhook connector | ✅ Stored, encrypted, test-callable |
| **Automatic run-event delivery to Slack or Teams** | ❌ **Not wired yet** — see below |

Read that last row before building on this page. Connectors are configured,
encrypted and provably reachable; **nothing in the run engine posts to them
automatically yet.** A run failure produces an in-app notification, not a Slack
message.

The honest options here were to document the gap or to leave you to discover it
after wiring a channel and waiting for an alert that never comes.

## Connectors

```
GET    /api/connectors
POST   /api/connectors
POST   /api/connectors/{id}/test
DELETE /api/connectors/{id}
```

Three kinds:

| Kind | Config | Validation |
|---|---|---|
| `SLACK_WEBHOOK` | `{"url": "https://hooks.slack.com/..."}` | Must be `https` |
| `GENERIC_WEBHOOK` | `{"url": "https://..."}` | Must be `https` |
| `GITHUB` | `{"repo": "owner/name", "token": "..."}` | Repo must match `owner/name` |

Configuration is **AES-GCM encrypted and never returned by any endpoint**. Plain
`http` is rejected outright — a webhook URL is a bearer credential in a query
path, and sending one in the clear gives it away.

Creating and deleting a connector are audited as `CONNECTOR_CREATED` and
`CONNECTOR_DELETED`.

### Setting up Slack

1. In Slack, create an **Incoming Webhook** for the target channel.
2. `POST /api/connectors` with kind `SLACK_WEBHOOK` and the URL.
3. `POST /api/connectors/{id}/test`.

### Setting up Teams

Microsoft Teams has no first-class connector kind. Use `GENERIC_WEBHOOK` with an
incoming-webhook URL from the Teams channel.

Be aware of the payload difference: the Slack kind posts Slack's
`{"text": "..."}` shape. The generic kind posts a plain JSON ping, which Teams
accepts for a connectivity check but which is not an Adaptive Card. Rich Teams
formatting would need a Teams-specific kind, and that does not exist yet.

## Test is a real call

`POST /api/connectors/{id}/test` **actually posts** to the configured target and
records `lastTestOk` and `lastTestAt` on the row.

- Slack and generic webhooks receive a one-line message identifying itself as an
  AutoOps connection test.
- GitHub connectors do a repository lookup, with the token as a bearer if one is
  stored. A `404` is reported specifically as "repo not found or token lacks
  access", because those are the two mistakes people actually make and they need
  different fixes.

A connector that looks configured and is not says so, rather than failing
silently on the day it matters.

## In-app notifications

This is the channel that is genuinely delivered today.

```
GET /api/notifications
GET /api/notifications/unread-count
GET /api/notifications/preferences
PUT /api/notifications/preferences
```

Notifications are published per tenant with a kind, a title, a body and a deep
link into the console. Members mute kinds individually; the unread badge honours
the mute, because a preference that left the badge counting things you had
muted would make the preference useless.

A failure reading the preference table **does not blank the inbox** — on error
everything stays visible. Showing too much beats showing nothing.

Approvals publish here: raising one notifies the workspace with a link straight
to the Approvals screen, and deciding one notifies the requester with the
outcome and who decided.

## GitHub is a different thing

The `GITHUB` connector is not a notification channel — it is for **SCM sync**.
Workflows and jobs export to and import from a repository, audited as
`SCM_CONFIGURED`, `SCM_EXPORTED` and `SCM_IMPORTED`.

An internal SCM import carries the requesting user's own token alongside the
internal call (`X-Access-Token`) so the plan gate still applies to an operation
that is technically service-to-service.

SCM configuration also backs two things beyond convenience: the `SCM_REQUIRED`
governance policy (active projects must have git sync; Monitor by default,
blocking in Enforced mode) and the version-control control in compliance
reports.

## Alerts come the other way

If what you want is AutoOps reacting to Slack or a monitoring tool rather than
posting to it, that is the alert plane, and it ingests from 100+ tools through a
dedicated engine. See [Custom Webhooks](custom-webhooks.md).

## Related

- [Custom Webhooks](custom-webhooks.md)
- [Audit Logs](../security/audit-logs.md)
- [Approval Gates](../workflows/approval-gates.md)
