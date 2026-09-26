# Governance Policy Catalogue

The five policies AutoOps evaluates against a workspace, what each one measures,
and exactly what happens when one is violated.

Read the **Scope & Limitations Statement** first — it defines what these
measurements can and cannot be used to claim.

## How the policy engine works

Three properties shape everything below.

**Violations are computed on read and never stored.** There is no violation
table, no acknowledgement workflow and no way for a violation to become stale.
Fixing the cause clears the finding on the next read. The corollary is that
there is no history of past violations — if you need that, generate compliance
reports, which *are* snapshotted.

**Policies are evaluated against live workspace data**, scoped to the tenant
from the access token. Nothing is self-attested.

**Only two policies can block anything, and they block only manual runs.** See
"Enforcement" below, which is the section most worth reading twice.

## Modes

| Mode | Behaviour |
|---|---|
| `ENFORCED` | Violations are reported **and** block manual runs in the violating project |
| `MONITOR` | Violations are reported. Nothing is blocked |
| `DISABLED` | The policy is skipped entirely and reports no violations |

Not every policy supports every mode — see the catalogue.

Changing a mode is **admin-only**, requires the `GOVERNANCE` plan feature
(Business tier and above), and is recorded in the audit trail as
`GOVERNANCE_POLICY_UPDATED`. Reading the governance summary is never gated.

## The catalogue

### 1. Risky operations require approval

| | |
|---|---|
| Code | `RISKY_APPROVAL` |
| Scope | Workflows with risky or complex steps |
| Configurable | **No** — derived from the tenant's approval settings |
| Modes | `ENFORCED` when risky-type gating is on, `DISABLED` when it is off |

This policy has no independent switch. It reports the *state* of the approval
gate rather than a violation of it, which is why its violation list is always
empty: a configuration is either in place or it is not, and calling that a
violation would be a category error.

A workflow is gated when it meets **either** condition:

- its node count is at or above the tenant's complexity threshold
  (platform default **5**), or
- it contains a node whose type is in the tenant's risky set (platform default
  `terraform`, `kubernetes`, `k8s`, `awslambda`, `azurefn`, `ssh`).

Both knobs are per-tenant. Emptying the risky set disables risky-type gating,
and this policy then reports `DISABLED`.

Jobs are gated by an explicit per-job `requiresApproval` flag rather than by
complexity.

> An **admin** running a gated target runs it directly. They are the approval;
> requiring them to file a request with themselves would be theatre, and the
> audit trail already records who ran what.

### 2. Definitions versioned in git

| | |
|---|---|
| Code | `SCM_REQUIRED` |
| Scope | All active projects |
| Configurable | Yes |
| Modes | `ENFORCED` / `MONITOR` / `DISABLED` — default **`MONITOR`** |

Every active project must have a git repository configured for definition sync.
A project without one is reported as: *"No git repository configured for this
project."*

Archived projects are not evaluated.

In `ENFORCED` mode, a manual run in a project with no SCM configuration is
refused with `403 policy_scm_required`.

### 3. Revoked integrations retain no credentials

| | |
|---|---|
| Code | `CREDENTIAL_HYGIENE` |
| Scope | Cloud integrations |
| Configurable | **No** — always `ENFORCED` |
| Modes | `ENFORCED`, permanently |

Disconnecting a cloud integration destroys its stored credentials; the record
survives so history still reads. A disconnected integration that still holds
credentials is therefore a violation **by construction** — under normal
operation the state cannot occur.

That is why this policy cannot be switched off. It is not a rule the platform
asks you to follow; it is an assertion about the platform's own behaviour, and a
violation here means something is wrong with AutoOps rather than with your
practice.

Violations name the integration and its platform.

### 4. Run failure rate stays under 25% (30 days)

| | |
|---|---|
| Code | `FAILURE_BUDGET` |
| Scope | All active projects |
| Configurable | Yes |
| Modes | `ENFORCED` / `MONITOR` / `DISABLED` — default **`MONITOR`** |

A project violates the budget when, over the last **30 days**:

- it has at least **4 finished runs** (below that, the rate is noise rather than
  signal), **and**
- more than **25%** of them failed.

Only `SUCCEEDED` and `FAILED` runs count as finished. Cancelled and in-flight
runs are excluded from both sides of the ratio.

Violations read: *"7 of 20 runs failed in the last 30 days (35%)."*

In `ENFORCED` mode, a manual run in a violating project is refused with
`403 policy_failure_budget`, and the message carries the actual numbers so the
operator knows what to fix.

### 5. Approvals decided within 7 days

| | |
|---|---|
| Code | `APPROVAL_SLA` |
| Scope | Pending approval requests |
| Configurable | Yes |
| Modes | `MONITOR` / `DISABLED` only — default **`MONITOR`** |

Any approval still `PENDING` more than **7 days** after it was requested is a
violation, named by target with the age in days and the requester.

**There is deliberately no `ENFORCED` mode.** Enforcing "decide faster" by
blocking runs would punish the operator waiting on a decision rather than the
admin who has not made it. Attempting to set it returns `400 invalid_mode`.

## Enforcement — what actually gets blocked

This is the section that matters when someone asks "so is it enforced?"

**Enforcement applies to `MANUAL` runs only.** A run triggered by the cron
scheduler, by a webhook, or by an agent is **not** blocked by any governance
policy.

For the scheduler this is a known, accepted trade-off, and it is the same one
the approval gate makes: a scheduled run carries no user token, so it cannot be
evaluated against a user's entitlements and is not put through the gate. If a
policy must hold for scheduled work, the enforcement belongs inside the job.

Only `SCM_REQUIRED` and `FAILURE_BUDGET` can block. `RISKY_APPROVAL` routes a
run into the approvals inbox rather than refusing it; `CREDENTIAL_HYGIENE`
describes platform behaviour; `APPROVAL_SLA` has no enforced mode.

| Policy | Blocks a manual run? | Error code |
|---|---|---|
| `RISKY_APPROVAL` | No — queues an approval instead | — |
| `SCM_REQUIRED` | Yes, in `ENFORCED` | `policy_scm_required` |
| `CREDENTIAL_HYGIENE` | No | — |
| `FAILURE_BUDGET` | Yes, in `ENFORCED` | `policy_failure_budget` |
| `APPROVAL_SLA` | No | — |

## The governance summary

`GET /api/governance/summary` returns, for the whole tenant:

- **Compliance score** — the mean of the latest compliance report score for each
  *active* project. Projects that have never generated a report are excluded;
  with none at all, the score is absent rather than zero. An absent score means
  "not measured", and the console says so rather than showing a bare dash.
- **Policies enforced** — how many are currently in `ENFORCED`.
- **Open violations** — the total across all policies not `DISABLED`.
- **Quota usage** — the **worst** utilisation percentage across active projects,
  automations, jobs and connected cloud integrations against the plan's limits.
  Worst rather than average, because the binding constraint is what matters.
- **Platform automations** — the real state of four platform behaviours:
  approval gating, the scheduled job runner, credential purge, and git
  definition sync, each with its live scope.

## Configuring the gate

```
GET  /api/approvals/settings
PUT  /api/approvals/settings   { "complexNodeThreshold": 3,
                                 "riskyTypes": "terraform,ssh" }

GET  /api/governance/summary
PUT  /api/governance/policies/{policy}   { "mode": "ENFORCED" }
```

- No stored approval-settings row means the platform defaults apply.
- `riskyTypes` set to empty disables risky-type gating — and with it, the
  `RISKY_APPROVAL` policy.
- Both endpoints are admin-only; changes are audited as
  `APPROVAL_SETTINGS_UPDATED` and `GOVERNANCE_POLICY_UPDATED`.

## Recommended baseline

Not a requirement, and not what ships by default. This is what we would set for
a production workspace, with the reasoning:

| Setting | Recommendation | Why |
|---|---|---|
| Risky-type gating | Leave enabled with the default set | Infrastructure-grade steps touch real resources |
| Complexity threshold | 5 nodes (default) | Lower it if your workflows are small and dense |
| `SCM_REQUIRED` | `ENFORCED` once every project has a repository | Enforce *after* compliance, never before, or you block work to make a point |
| `FAILURE_BUDGET` | `MONITOR` first, `ENFORCED` after a stable month | Enforcing on a noisy project blocks the runs that would fix it |
| `APPROVAL_SLA` | `MONITOR` | It has no other mode, and the signal is about staffing rather than about the platform |

Move policies to `ENFORCED` one at a time, and only after the corresponding
violation list has been empty for a while. A policy switched on over a live
violation blocks production work immediately.
