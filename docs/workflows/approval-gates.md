# Approval Gates

When a run stops and waits for a person, who can decide, and what happens on
each side of the decision.

## One inbox

Every approval in AutoOps — a job someone flagged, a workflow judged complex, an
agent that wants to change something — lands on the **same Approvals screen**.

There is deliberately no separate approval surface for agents. A second place to
look would be a second place to *forget* to look on the day something ran that
should not have.

## What gets gated

### Jobs — an explicit flag

A job carries `requiresApproval`. When it is set, a manual run by a non-admin
raises an approval instead of starting.

### Workflows — an automatic rule

There is **no per-workflow toggle**. A workflow is gated when it is judged
*complex*, and complexity is two conditions, either of which is enough:

| Condition | Platform default |
|---|---|
| Node count at or above a threshold | `5` |
| Contains a node whose type is in the risky set | `terraform`, `kubernetes`, `k8s`, `awslambda`, `azurefn`, `ssh` |

Infrastructure-grade steps touch real cloud resources, so a fat-fingered run
hurts. That is the entire justification for the risky set, and it is why the set
is what it is.

Both knobs are per-tenant:

```
GET /api/approvals/settings
PUT /api/approvals/settings     { "complexNodeThreshold": 3,
                                  "riskyTypes": "terraform,ssh" }
```

- No stored row means the **platform defaults** apply.
- `riskyTypes` set to empty disables risky-type gating entirely.
- Changing these is admin-only and audited as `APPROVAL_SETTINGS_UPDATED`.

The node type is read from the definition server-side, falling back to the
designer's node id when a node carries no explicit `type` — mirroring exactly
what the execution engine does, so the gate and the engine cannot disagree about
what a node is.

> The complexity arithmetic is implemented twice — once in core-service and once
> in workflow-service, which needs it to compute `requiresApproval` for its own
> list responses. Both take `(definition, nodeCount, rules)` as **data**, and the
> rules themselves live in exactly one place. The two copies can drift on the
> arithmetic and on nothing else.

### Admins skip the gate

An admin pressing Run on a gated target runs it. They *are* the approval; making
them file a request with themselves would be theatre, and the audit trail
already records who ran what.

## The flow

```
Operator presses Run
        │
        ▼
  Approval created (PENDING)          ──► in-app notification to the workspace
        │
        ├─ admin approves ──► the run STARTS, in the same transaction,
        │                     gated by the APPROVER's own token
        │
        └─ admin rejects  ──► nothing runs; the decision is recorded
```

Three details worth knowing:

- **The run starts under the approver's token.** The subscription gate is
  evaluated against the person who said yes, not the person who asked.
- **The requester's form inputs travel with the approval.** A workflow's inputs
  are validated at *request* time, so a form with a missing required field is
  refused immediately rather than parked in a queue and discovered to be
  unrunnable days later. If the stored inputs turn out to be unreadable at
  approval time, the run proceeds without them rather than failing the decision.
- **One pending approval per target.** A second request for the same job or
  workflow while one is outstanding is refused with `409 approval_pending`.

## The API

```
GET  /api/approvals?projectId=3      newest 200
POST /api/approvals/{id}/approve     admin only
POST /api/approvals/{id}/reject      admin only
```

Only admins decide. Operators request; the backend enforces this, so a console
that offered the button would produce 403s.

Every transition is audited: `APPROVAL_REQUESTED`, `APPROVAL_APPROVED`,
`APPROVAL_REJECTED`.

## Agents and approvals

An agent never decides on its own whether something needs a human. It asks the
control plane, which answers `RUN` or `APPROVAL`, and the agent service never
re-derives that rule. Re-implementing it there would create a second copy, and
the day it drifts an agent runs unattended something the console swore needed a
person.

When a human is needed:

1. The run parks in `AWAITING_APPROVAL`, holding the approval id and the id of
   the tool call that raised it.
2. An admin approving it **in the normal inbox starts the run** — and the loop
   then attaches to *that* run rather than starting a second one.
3. A poller picks the verdict up (default every 15 s). A poller rather than a
   callback, because the control plane cannot reach a *specific paused loop*,
   and a run parked before the last restart has no loop to call back into.
4. A rejection routes the agent to its `REPORT` phase — never back to `PLAN`.
   The agent does not get to re-propose after being told no.

The approval timeout defaults to **2 days**, measured from the run's start, so
it covers a weekend and expires slightly early rather than slightly late.

If a model asked for several tools in one turn and the second of three needs
approval, the transcript parks holding the results collected so far. The resume
path works out what is still outstanding by comparing the assistant turn's tool
calls against the results already recorded — no extra bookkeeping column, and no
result emitted twice.

## Workflows park differently — they do not park

A workflow's `job` node hitting a gated target **fails the run** with the
approval named, instead of parking the graph.

That is a real difference from agents, and it is deliberate. Parking a graph
mid-flight means persisting where it got to, which means a checkpointer, which
means a second answer to "what has this run already done" — and when the two
disagree, the cost is a destructive step performed twice. An agent can park
because its transcript *is* its state; a workflow has no equivalent.

Decide the approval, then re-run.

## Governance around approvals

Two policies watch the approval process itself:

| Policy | Checks | Modes |
|---|---|---|
| `RISKY_APPROVAL` | Derived from the approval settings — enforced exactly when risky-type gating is on | Derived, not configurable |
| `APPROVAL_SLA` | Pending approvals decided within 7 days | Monitor / Disabled only |

`APPROVAL_SLA` has no Enforced mode on purpose: enforcing "decide faster" by
blocking runs punishes the wrong person.

Approvals also back two compliance controls directly: **change authorization**
(gating exists on jobs and risky workflows) and **segregation of duties**
(requester ≠ approver on recorded decisions).

## What is not gated

- **Scheduled runs.** The cron scheduler is not blocked by the approval gate,
  and scheduled runs carry no user token.
- **Reads.** Nothing about viewing data goes through an approval.

## Related

- [Triggers & Schedules](triggers-and-schedules.md)
- [Role-based Access](../security/role-based-access.md)
- [Audit Logs](../security/audit-logs.md)
