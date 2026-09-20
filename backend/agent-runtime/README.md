# agent-runtime

The reasoning half of AutoOps agents. Python 3.12, FastAPI, LangGraph.

It is a **stateless reducer**: `(state, event) → state'`. agent-service sends the
whole run state on every call and stores whatever comes back; this service keeps
nothing between requests — no database, no cache, no session. Two consecutive
calls can land on two different instances, or on the same instance three days
apart across a redeploy, and neither case is special-cased because neither case
is different.

```
agent-service (Java, control plane)        agent-runtime (this)
──────────────────────────────────         ────────────────────
CRUD, catalog, rollout, entitlements
tenancy, security, Flyway
agent_runs / agent_run_steps
resolves model credentials      ─────────► POST /v1/reduce
drives the loop                 ◄───────── directive: CALL_TOOLS | FINISH | FAIL
executes tools, parks on approvals
writes every audit row                     prompts, phases, context assembly,
                                           tool narrowing, error compaction,
                                           the evidence ledger
```

**The split is not arbitrary.** agent-service owns everything a loop must not
lose — transaction boundaries, the approvals inbox, the audit trail, the tenant
check. This service owns everything that benefits from being disposable. A tool
only ever runs because Java ran it; nothing here can reach a customer's
infrastructure.

## Why an agent is more than a persona here

An agent in the old model was `{instructions, model, tools[]}` driving one
undifferentiated ReAct loop — which is what every competitor ships. Here an
agent is a **graph of phases**, each with its own prompt and its own narrowed
toolbox:

| Phase | Job | Tools it can see |
|---|---|---|
| `TRIAGE` | decide what the run needs; refuse work the tools cannot do | none |
| `GATHER` | collect observations | **read-only only** |
| `HYPOTHESIZE` | conclude, from the ledger | none |
| `PLAN` | propose actions, with blast radius and rollback | none |
| `GATE` | emit the action; Java's approvals take over | mutating |
| `ACT` | absorb the human's verdict | mutating |
| `VERIFY` | prove the state actually moved | read-only |
| `REPORT` | the answer, evidence-enforced | none |

Two consequences that a single loop cannot give you:

- **`GATHER` is never *shown* a destructive tool.** Not instructed to avoid one
  — never sent one. A model that can see a delete tool while diagnosing will
  eventually reach for it, because it is right there and looks like progress.
- **`HYPOTHESIZE` has no tools at all**, so the only way to finish is to say
  what the numbers mean. A model that can still collect will keep collecting.

## This service holds nothing. Keep it that way.

`reduce` is `(state, event) -> state'`. Every run's entire world arrives in the
request and leaves in the response; there is no session, no cache keyed by run
id, and nothing on disk. `ReduceRequest.run_id` and `tenant_id` are
**correlation only** — they exist so a run can be matched against whatever
observability is wired up, and nothing here is stored under them.

That is what makes a run parked on a human approval for two days
indistinguishable from one parked for two milliseconds, and it is why the
service can be restarted, scaled or replaced mid-run without anybody noticing.

**The refactor that would quietly destroy it.** When agent-service needs
something this service computed — a coverage scope, a counter, a verdict — the
obvious-looking move is to have the runtime POST it back:

```python
# NO. This makes the runtime stateful.
requests.post(f"{agent_service}/runs/{run_id}/scope", json=scope)
```

It reads like a small convenience and it is not one. A callback means the
runtime now owns a fact that has to reach another system, so it acquires
retries, ordering, and a failure mode where a parked run depends on a call
having succeeded hours ago. The property above is gone, and nothing fails
loudly enough to notice.

**Everything computed here rides back on `ReduceResponse` instead**, and
agent-service — which already owns the run's lifetime and its transaction —
writes it. `findings` works this way; `subject_scope` will too. If you find
yourself wanting an HTTP client in this service, add a field to the response.

## The evidence ledger

Every tool result agent-service records produces an `agent_run_steps` row, and
**that row's primary key is the citation**. The ledger is an index over rows the
control plane was writing anyway, which is why it needs no table of its own.

`REPORT` must mark every factual claim `[e:<id>]`. `evidence.audit` then checks
the draft:

- a citation to an id this run never issued is caught **exactly**;
- a claim-shaped sentence with no citation is caught **heuristically**.

One repair prompt, then the report ships either way — carrying a visible
`UNVERIFIED` banner naming the lines it could not substantiate. It never fails
the run: a flagged report during an incident is worth more than no report.
agent-service repeats the exact check independently against the run's own step
ids, so a fabricated id cannot survive even if this service's check is wrong.

The heuristic errs toward silence. It exempts hedges, recommendations and
"this was not measured" — see the note in `evidence.py`, and the golden case
that put it there.

## Layout

```
agent_runtime/
  app/
    main.py       FastAPI: /v1/reduce, /v1/agents, /v1/vendors, /health
    reduce.py     the reducer — the whole service in one function
    state.py      the wire contract; STATE_VERSION lives here
    models.py     LangChain chat models per vendor (mirrors Java's ModelVendor)
    toolbox.py    per-phase tool narrowing
    evidence.py   the ledger and its two checks
  graph/
    prompts.py    every prompt, versioned (Factor 2)
    phases.py     the phase kit
    kit.py        phases -> a compiled LangGraph
    crews.py      CrewAI, for the hypothesize phase of hard triage only
  agents/
    aws/public_exposure_auditor.py       RD-149 — correlates three security audits
    aws/cost_anomaly_investigator.py     RD-141 — explains a bill movement
    aws/idle_resource_reclaimer.py       RD-142 — the only agent that changes anything
    aws/incident_rca_analyst.py          RD-210 — nine alarms, two incidents, one cause
    m365/offboarding_auditor.py          RD-201 — did the leavers actually leave
    m365/privileged_access_auditor.py    RD-202 — who can do the most damage
    generic/single_phase.py              the legacy-compatibility loop
evals/            golden cases + the replay harness
```

### The shipped agents

| Agent | Phases | Tools | Why an agent rather than a report |
|---|---|---|---|
| `aws.public_exposure_auditor` | 4, read-only | S3 public access · security-group ingress · IAM credential hygiene | The finding is in the *intersection* — a public bucket, an open port with something live behind it, and a stale unused key is a chain, not three lists |
| `aws.cost_anomaly_investigator` | 4, read-only | Cost Explorer delta · idle resource inventory | Cost Explorer says which *service* rose; only correlation says which *resources* explain it, and how much it does **not** explain |
| `aws.idle_resource_reclaimer` | **all 8** | idle resource inventory · unused volume reclaim *(mutating)* | Deciding which idle disk is waste and which is a migration in progress is the judgement. Deleting it is the easy part |
| `aws.incident_rca_analyst` | 4, read-only | CloudWatch alarm state · CloudTrail change timeline | Two consoles each hold half the answer. The question is the join: which of the nine red things are one incident, and what touched a resource in that cluster four minutes before it went red |
| `m365.offboarding_auditor` | 4, read-only | licence assignment · mailbox rules | Offboarding is three jobs in two systems and nothing checks all three happened. A leaver disabled on day one looks finished — while their seat bills for a year and their inbox forwards to a personal address |
| `m365.privileged_access_auditor` | 4, read-only | privileged access · licence assignment | A list of admins is an org chart. A tier-zero role holder with no MFA registered is a finding |

The two auditors are read-only and collect all their tools in **one** turn before
reasoning with the tools removed.

`aws.idle_resource_reclaimer` is the one that exercises the whole kit, and it is
why the kit has eight nodes. `GATHER` is never *shown* the delete tool, so the
candidate list cannot be contaminated by it. `PLAN` writes a proposal carrying the
exact volume ids, the blast radius and the rollback. `GATE` emits **one** action
and Java parks the run on a human. `ACT` routes a rejection to `REPORT`, never
back to `PLAN`. `VERIFY` re-runs the read-only inventory, because an automation
that exited zero is not evidence a disk is gone — the disk being absent from a
fresh listing is.

Every one of those transitions is pinned by a golden case, including the
rejection: that case supplies exactly ONE model reply for its final step, so a
graph that ever re-entered `PLAN` would run out of recorded replies and fail the
build.

### Why the domains are what they are

The limit has never been the reasoning — it is which credentials a job step can
be handed. `StepCredentials` gives a `pyscript` step AWS, Azure or GCP and
nothing else, `SshRunner` needs keys at `/home/autoops/.ssh` that nothing mounts,
and there is no WinRM transport at all.

An **Azure** connection supplies `AZURE_TENANT_ID` / `AZURE_CLIENT_ID` /
`AZURE_CLIENT_SECRET`, which is exactly Microsoft Graph's client-credentials
contract — and the execution image already ships `requests`. So Microsoft 365
and Entra ID needed no new plumbing, which is why the identity agents exist.

What is still blocked, and on what:

| Domain | Blocked on |
|---|---|
| Active Directory, Exchange on-premises, Windows Server | no WinRM transport, and no credential type a step can be handed for one |
| Linux | `ssh` runs, but no key material is mounted into the execution image |
| VMware, Network, SQL Server | need a credential for an arbitrary endpoint; `StepCredentials` only resolves cloud connections |

None of these needs an agent to be designed. Each needs one transport, and the
agents follow the day it lands.

### The automations behind them

The tools are catalog workflows under `backend/agent-service/workflows/`, whose
steps are `pyscript` — boto3 or `requests` on the execution host, with the
tenant's own cloud credential in the environment. Their bodies are real `.py` files under
`workflows/_authoring/bodies/`, assembled into the published JSON by
`generate.py`; `generate.py --check` fails the build if the two drift.

`tests/test_workflow_bodies.py` runs them against a stubbed AWS. That is where
the judgement in them is pinned: that a denied read is never reported as a clean
result, that an open port with nothing behind it is not an exposure, and that a
volume re-attached between the audit and the approval is skipped however it was
approved.

## Agents are Python modules, and that seals them properly

A rolled-out JSON agent physically copies its `instructions` into the customer's
own database row, protected only by no API exposing it. Anyone with a database
credential has the product.

Here, an agent module exports an `AgentSpec` with a **public `Manifest`** and a
**private persona + graph**. Only the manifest is published; the customer's row
holds a reference:

```json
{"kind": "PYTHON", "ref": "linux.server_health_check", "version": "1.0.0", ...}
```

`Manifest.to_json` structurally cannot emit a persona, and a test asserts it.
The cost is real and worth naming: a new agent can no longer be rolled out
independently of a deploy — shipping one means shipping this image.

### Adding an agent

1. Write `agent_runtime/agents/<domain>/<name>.py` exporting `AGENT: AgentSpec`.
2. Register it in `agent_runtime/agents/__init__.py` — one import, one line.
   Deliberately explicit, so `git log` on that file is the catalog's deployment
   history.
3. Mark each `ToolRef` as read-only where it is. **Unmarked means mutating**, on
   both sides of the wire.
4. `pytest`, then publish:
   `python backend/agent-service/agents/_schema/publish.py --runtime http://localhost:8089 --dry-run`

## Running it

```bash
python -m venv .venv && .venv/bin/pip install -e ".[dev]"
uvicorn agent_runtime.app.main:app --port 8089 --reload

pytest                          # unit tests + golden cases
python -m evals.replay check    # golden cases alone, with a readable diff
```

`GET /health` is unauthenticated (compose's healthcheck holds no secret) and
reports the agent registry, prompt version and state version — the fastest way
to tell whether the deployment is the one you think it is.

Everything under `/v1` requires `X-Internal-Token`. api-gateway does not route
here at all; the only caller is agent-service.

## Tracing (LangSmith)

Off by default, opt-in per environment, and it can never fail a run. With
`AGENT_RUNTIME_LANGSMITH_ENABLED=true` and a key, every model call is traced with
the phase it belongs to, the prompt version that produced it, and the agent ref
and version.

```bash
AGENT_RUNTIME_LANGSMITH_ENABLED=true
AGENT_RUNTIME_LANGSMITH_API_KEY=ls-...
AGENT_RUNTIME_LANGSMITH_PROJECT=autoops-agents      # one per environment
AGENT_RUNTIME_LANGSMITH_ENDPOINT=https://api.smith.langchain.com
AGENT_RUNTIME_LANGSMITH_HIDE_IO=false               # structure without content
```

**One trace per reduce, one thread per run.** A reduce is a single graph
traversal, so it is one trace with each phase nested inside it — `TRIAGE`,
`GATHER`, `HYPOTHESIZE` and the rest appear as named child runs, in order. A run
spans many reduces, because it stops every time it wants a tool and can sit on an
approval for two days between them; those are stitched into one LangSmith thread
from the `session_id` on the root run, derived from the run id. `trace_id` on the
reduce response is that thread.

**One project, every customer in it.** The project is per ENVIRONMENT, not per
tenant: an agent is authored once and delivered to every customer who buys it, so
"is this version misbehaving everywhere or only at one site?" is the first
question asked of a regression — and it cannot be asked at all if the ten sites
are ten projects. The tenant is a tag (`tenant:<id>`) and a metadata field
instead, which is what the trace list filters on. An untenanted run — an eval, a
replay — is tagged `tenant:none` so it can be filtered out of a customer
investigation. Per-tenant CONSENT is a different axis from per-tenant storage;
see `LANGSMITH_HIDE_IO` below.

**Where the callbacks hang is load-bearing, not tidiness.** LangChain resolves a
run's parent from the callback manager inherited through a context variable, and
a config that names `callbacks` explicitly REPLACES that manager. Pass a tracer
again on each model call and every phase becomes its own top-level trace; pass an
empty list — which this code did before — and the call is not traced at all. So
the tracer is attached once, at `graph.invoke`, and `RunContext.config` carries
the run name, tags and metadata but deliberately no callbacks.
`tests/test_tracing.py` pins both halves, including the LangChain semantic itself,
so a future release that changes it fails here in a second rather than as a
project that quietly stopped showing phases.

**What never reaches a trace.** The tenant's model credential arrives on the
descriptor and leaves with the response. Nothing in `tracing.py` reads
`agent.credentials`; the metadata is assembled field by field rather than dumped
from the descriptor, and a test asserts the key cannot appear in the payload. For
a customer whose infrastructure detail must not leave the estate at all,
`LANGSMITH_HIDE_IO=true` keeps the shape of every run — phases, timings, token
counts, errors — and drops the message bodies.

## Evals

`evals/` replays recorded runs. Two modes, answering different questions:

- **`check`** — the regression gate. Replays golden cases with their recorded
  model replies, so nothing varies except our own code. Free, deterministic, runs
  in `pytest`. A prompt edit that changes how a real recorded run behaves fails
  the build.
- **`compare`** — the judgement call. Re-reduces a real run's state (straight out
  of `agent_runs.transcript`) against a live model, and prints what changed.
  Costs tokens, gives a different answer every time, and is therefore a manual
  act rather than a test.

## Known gaps

- **There is no Linux agent, because SSH cannot authenticate in the compose
  stack.** `RD-079-linux-server-health-check` is an `ssh` workflow and
  `SshRunner` needs key-based auth provisioned at `/home/autoops/.ssh`, which
  `docker-compose.yml` does not mount. The workflow is correct and stays in the
  tree; no agent is built on it until something can run it. Every shipped agent
  uses `pyscript`/boto3 for exactly this reason.
- **Huawei has no adapter here.** Its ModelArts endpoint has no LangChain
  binding, so agent-service keeps Huawei-backed agents on its own Java loop
  rather than letting them arrive and fail. `GET /v1/vendors` publishes what
  this build can serve so the two sides cannot disagree.
- **The Dockerfile's base image is pinned by tag, not digest**, unlike every
  other service here. A digest must be read from a real `docker pull` — an
  invented one fails the build outright rather than degrading to the tag. The
  command to get it is in the Dockerfile.
- **`VERIFY` is declared but not enforced.** `aws.idle_resource_reclaimer`
  declares it and a test now requires every state-changing agent to. What is
  still missing is the consequence: a run whose verification comes back
  UNCHANGED reports that in its prose, but still finishes `SUCCEEDED`. Making
  an unproven effect a distinct run outcome is the next step, not an oversight.
- **The cost figures in `RD-136` are list prices from a table in the script.**
  AWS exposes no "what is this volume costing me" API, and the Pricing API
  needs its own permission and is us-east-1 only. Every field built from the
  table carries its basis in its own NAME (`est_monthly_usd_list_price`), an
  unknown volume type reads `unpriced` rather than being guessed, and both
  agents' personas require the basis to travel with the number. It is the right
  order of magnitude for deciding what to clean up and the wrong number for a
  finance report, which is exactly how it is labelled.
- **CrewAI is an optional extra and is NOT in the default image.** `crewai`
  requires `crewai-tools`, which pulls a browser-automation stack, `pytube` and
  `youtube-transcript-api` — a large dependency surface to acquire, in a service
  that reasons about production infrastructure, for a feature no shipped agent
  uses yet. `graph/crews.py` imports it lazily and `panel()` falls back to the
  ordinary single-model `hypothesize` node when it is missing, so every agent
  runs correctly without it. An image that serves a crew-backed agent installs
  `.[crew]`; the fallback is covered by `tests/test_crews.py`.
