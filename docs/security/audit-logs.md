# Audit Logs

What is recorded, where it lives, and why there is only one trail.

## One trail, on purpose

core-service owns the audit log, and services that were split out of it —
workflow-service and agent-service — **write their events back into it** over
`/internal/audit` rather than keeping their own.

That costs a network call on every mutation. It buys the thing that matters: an
agent event sits next to the project and job events it relates to, so "what
happened in this workspace on Tuesday" is one query instead of three joined by
timestamp.

Recording is **best-effort**. An audit write never breaks the mutation it
documents — losing one row is better than refusing the operation that produced
it.

## Reading it

```
GET /api/audit?projectId=3&type=RUN_TRIGGERED&limit=200
```

Requires the `viewAudit` capability — admin only — and the `AUDIT_LOG` plan
feature, which is `TEAM` and up.

A row carries: the event type, the tenant, the actor, the project, the target
type and id, the target's name, and when.

## The event catalogue

The set is **closed**. Adding an event means altering the database ENUM in a
migration *and* adding the constant — a deliberate friction, so the catalogue
cannot quietly become a free-text field.

### Projects and automations

`PROJECT_CREATED` · `PROJECT_UPDATED` · `PROJECT_ARCHIVED` · `PROJECT_RESTORED`
`WORKFLOW_CREATED` · `WORKFLOW_UPDATED` · `WORKFLOW_DELETED` ·
`WORKFLOW_ENABLED` · `WORKFLOW_DISABLED`
`JOB_CREATED` · `JOB_UPDATED` · `JOB_DELETED`

### Agents

`AGENT_CREATED` · `AGENT_UPDATED` · `AGENT_DELETED` · `AGENT_ENABLED` ·
`AGENT_DISABLED` · `AGENT_RUN_STARTED` · `AGENT_RUN_CANCELLED`

An agent loop starting is an audited event in its own right, and so is a human
stopping one.

### Execution and approvals

`RUN_TRIGGERED` · `RUN_CANCELED`
`APPROVAL_REQUESTED` · `APPROVAL_APPROVED` · `APPROVAL_REJECTED` ·
`APPROVAL_SETTINGS_UPDATED`

### Credentials and connections

`CONNECTION_CREATED` · `CONNECTION_CREDENTIALS_UPDATED` · `CONNECTION_VERIFIED` ·
`CONNECTION_DISCONNECTED` · `CONNECTION_ASSIGNED` ·
**`CONNECTION_CLAIM_REJECTED`** (a cloud account already claimed by another
tenant was refused) · **`CONNECTION_QUARANTINED`** (credentials proven to belong
to another tenant's account were destroyed)
`SECRET_CREATED` · `SECRET_UPDATED` · `SECRET_DELETED`
`MODEL_PROVIDER_CREATED` · `_UPDATED` · `_DELETED` · **`MODEL_PROVIDER_VERIFIED`**
(a real call against the vendor proved the stored credential works)

### Integration surface

`WEBHOOK_CREATED` · `WEBHOOK_UPDATED` · `WEBHOOK_DELETED`
`CONNECTOR_CREATED` · `CONNECTOR_DELETED`
`SCM_CONFIGURED` · `SCM_EXPORTED` · `SCM_IMPORTED`
`COMMAND_DISPATCHED`

### Governance, compliance and the catalogue

`GOVERNANCE_POLICY_UPDATED` · `COMPLIANCE_REPORT_GENERATED`
`LIBRARY_CREATED` · `LIBRARY_CLONED` · `LIBRARY_UPDATED`
`BROADCAST_SENT` · **`TEMPLATE_ROLLED_OUT`** (a provider delivered a catalogue
workflow or agent into this workspace) · **`TEMPLATE_REVOKED`** (a provider
withdrew one)

## The authentication trail is separate

auth-service keeps its own `auth_audit_log` with 17 event types, carrying IP,
tenant and session. Sign-in attempts, OTP lifecycle, refresh-token reuse
detection, password changes and SSO events land there.

The split is intentional: identity events belong to the service that is the only
holder of the signing key, and it has its own retention (180 days by default).
Correlate across the two by tenant and actor.

## Retention

| Trail | Default | Setting |
|---|---|---|
| Auth audit | 180 d | `RETENTION_AUDIT` |
| Auth sessions | 30 d | `RETENTION_SESSIONS` |
| OTPs | 1 d | `RETENTION_OTP` |
| Core audit | Not swept | — |
| Run history | Plan `history_days` (30–730) | The plan, read at query time |

An hourly purge service enforces the auth retention. Run history is bounded on
**read** rather than deleted: older runs vanish from lists and 404 by id, but the
rows remain, and raising a plan brings the history back.

## What the audit trail feeds

It is not just a screen.

- **Compliance controls** read it directly — change authorization, segregation
  of duties (requester ≠ approver on recorded decisions), and timely review of
  stale approvals.
- **The `platform` workflow node** reads the workspace's own activity record:
  what ran in this project, what it returned, what failed and what is parked.
  This is the one evidence source that needs no customer credential, because the
  data is already the platform's — which is why it works the same for an AWS
  estate, an on-premises one, or both.
- **Agent evidence.** Every tool result an agent records produces a run-step row,
  and **that row's primary key is the citation**. An agent's report must mark
  every factual claim with a step id. A citation to an id the run never issued
  is caught exactly; a claim-shaped sentence with no citation is caught
  heuristically. The check runs twice, independently, in the reasoning runtime
  and again in the control plane against the run's own step ids, so a fabricated
  id cannot survive even if one check is wrong.

  A report that cannot be fully substantiated still ships, carrying a visible
  `UNVERIFIED` banner naming the lines it could not back up. A flagged report
  during an incident is worth more than no report.

## Findings

Beyond the event log, agent output is stored as **findings**: structured
verdicts with a frozen idempotency key, so the same condition seen on two runs
deduplicates into one row with a disposition instead of two rows that look like
two problems. Each finding carries an honesty flag recording whether its key is
genuinely stable — a dedup that cannot be trusted says so rather than silently
merging two different things.

## Related

- [Role-based Access](role-based-access.md)
- [Approval Gates](../workflows/approval-gates.md)
- [State Management](../architecture/state-management.md)
