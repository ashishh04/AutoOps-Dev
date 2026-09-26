# Core Concepts

Eight nouns explain almost everything in AutoOps. Learn these and the console
stops needing explanation.

## Tenant

A **tenant** is a workspace, and it is the hard boundary in the platform. Every
business row carries a `tenant_id`, and that id comes from the `tenantId` claim
in your access token — **never** from a header or a request body. The API
gateway overwrites the `X-Tenant-ID` header with the token's own claim before
proxying, so a client cannot smuggle another workspace's id past the edge.

Signing up creates a *fresh* tenant. You join an existing one by being
onboarded into it by its admin, which is the only path that exists.

## Project

A **project** groups everything operational: jobs, workflows, agents, runs,
schedules, webhooks, cloud integrations and compliance reports all belong to
exactly one. Most teams run one project per environment (`Production`,
`Staging`) or per customer.

Projects are archived, not deleted — archiving frees the project quota, and
restoring re-checks it.

## Job

A **job** is an ordered list of **steps**, and it is the thing you build
yourself. A step has a type, a label and a value:

```json
{
  "name": "Rotate log files",
  "steps": [
    { "type": "command",  "label": "Check space", "value": "df -h" },
    { "type": "pyscript", "label": "Rotate",      "value": "import shutil\n..." },
    { "type": "rest",     "label": "Notify",      "value": "POST https://hooks.example/done" }
  ]
}
```

The step types that exist today:

| Type | Runs |
|---|---|
| `command` / `agent` | A one-line shell command |
| `script` | A multi-line shell script |
| `pyscript` | A Python script |
| `powershell` / `pwsh` | A PowerShell Core script (cross-platform modules only). **Refused** under the default execution mode — see [Execution Engine](../architecture/execution-engine.md) |
| `ssh` | A command on a remote host over the system ssh client |
| `rest` | An HTTP call — 2xx/3xx is success |
| `terraform` | Real OpenTofu `init` + `plan`/`apply`/`destroy` |
| `kubernetes` | Real `kubectl` against the tenant's cluster integration |
| `awslambda` / `lambda` | A real SigV4-signed Lambda `Invoke` |
| `azurefn` | A real Azure Functions HTTP trigger call |
| `library` | A script from the provider's catalog, with arguments |
| `test` | Always succeeds; echoes its value |

Steps also carry per-step reliability settings — `retries` and
`continueOnError`. See [Handling Failures](../workflows/handling-failures.md).

## Workflow

A **workflow** is a directed graph of typed nodes: it can call a model, read
the workspace's own history, run an automation, make an HTTP call, branch on a
condition, and produce a structured output. Where a job is a straight line of
steps, a workflow is a thing that **decides**.

See [DAG Syntax](../workflows/dag-syntax.md) for the full node contract.

## Agent

An **agent** is a persona plus a **closed allow-list of tools** — the specific
jobs and workflows in its own project that it is permitted to operate. That
allow-list is the security boundary, and it is re-validated against the owning
service on every run: a job that has been deleted or moved out of the project
is not a tool any more.

Agents are not a single ReAct loop. An agent is a graph of **phases**, each
with its own prompt and its own narrowed toolbox:

| Phase | Job | Tools it can see |
|---|---|---|
| `TRIAGE` | Decide what the run needs; refuse work the tools cannot do | none |
| `GATHER` | Collect observations | **read-only only** |
| `HYPOTHESIZE` | Conclude, from the evidence ledger | none |
| `PLAN` | Propose actions, with blast radius and rollback | none |
| `GATE` | Emit the action; the approvals inbox takes over | mutating |
| `ACT` | Absorb the human's verdict | mutating |
| `VERIFY` | Prove the state actually moved | read-only |
| `REPORT` | The answer, evidence-enforced | none |

Two properties fall out of that shape and cannot be had from one undifferentiated
loop: `GATHER` is never *shown* a destructive tool (not instructed to avoid one
— never sent one), and `HYPOTHESIZE` has no tools at all, so the only way for it
to finish is to say what the numbers mean.

Every factual claim in an agent's report must cite a step id from that run. A
citation to an id the run never issued is caught exactly; a claim-shaped
sentence with no citation is caught heuristically. A report that cannot be fully
substantiated still ships — carrying a visible `UNVERIFIED` banner naming the
lines it could not back up.

## Who authors what

This is the part that surprises people, and it is deliberate.

| | Authored by |
|---|---|
| Jobs, scripts, schedules, webhooks, integrations | **The customer** |
| Workflows and agents | **The provider** |

Workflows and agents are designed in the provider console and **rolled out** to
a customer's workspace as sealed copies. A customer runs what it has been given
and builds jobs and scripts of its own. `authorAutomation` is `false` for every
client role including `admin` — not a tier to be unlocked. The backend enforces
the same rule, so a console that offered the button would only produce 403s.

Components a delivered agent depends on are hidden: a workflow shipped only
because an agent needs it is sealed, unlisted and not runnable by the customer
directly.

## Run

A **run** is one execution of a job or a workflow.

```
QUEUED  ──►  RUNNING  ──►  SUCCEEDED
                     ├──►  FAILED
                     └──►  CANCELED
```

Triggering returns `202 Accepted` immediately and the run proceeds
asynchronously on a bounded pool. Poll `GET /api/runs/{id}` for
`stepCompleted` / `stepTotal` and the live log.

The target's **name and definition are snapshotted onto the run**, so history
stays truthful after the job is edited or deleted. There is no foreign key back
to the target for exactly that reason.

A run distinguishes two things the console shows separately:

- the **output** — the deliverable, the thing a human asked for;
- the **log** — the engine's trace: node timings, step attempts, what was fed
  in.

Four things can trigger a run: `MANUAL`, `SCHEDULE`, `WEBHOOK`, `AGENT`.

## Approval

Some runs stop and wait for a person. An approval is raised when the target is
judged complex or risky, and it lands in one inbox — the Approvals screen.

There is deliberately **no separate approval surface for agents**. An agent's
approval is an ordinary approval in the ordinary inbox. A second place to look
would be a second place to *forget* to look on the day something ran that
should not have.

See [Approval Gates](../workflows/approval-gates.md).

## Plan, quota and the subscription gate

A tenant's plan decides what it may do and how much of it.

| Code | Projects | Nodes | Automations | History | Adds |
|---|---|---|---|---|---|
| `STARTER` | 5 | 10 | 100 | 30 d | Core automation |
| `TEAM` | 25 | 50 | 500 | 90 d | Audit log, API access |
| `BUSINESS` | 25 | 500 | 2000 | 180 d | Premium templates, advanced RBAC, governance, compliance reports |
| `ENTERPRISE` | ∞ | ∞ | ∞ | 730 d | Private templates, SSO |

Two rules govern how the gate behaves, and both matter:

- **Reads are never gated.** A tenant can always see and export its own data.
  Only *doing new things* requires a live subscription.
- **Mutations fail closed.** If subscription-service is unreachable, a mutation
  returns `503 entitlement_unavailable` rather than being allowed through.

Downgrading grandfathers what already exists: resources over the new limit
survive, but creating another is refused with `quota_exceeded`.

Workflows and agents share **one** `MAX_AUTOMATIONS` budget. An autonomous
operator is an automation; a separate bucket would silently double every plan's
allowance.

> Billing is stubbed in this build. Subscribing succeeds without charging, and
> the whole lifecycle — trial → active → cancel → reactivate — is real, so a
> payment provider can slot in behind it later.
