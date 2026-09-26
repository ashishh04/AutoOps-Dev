# First Workflow

Build a workflow that takes an operator's input, reads the workspace's own
history, asks a model to explain it, and — if the answer says so — runs a real
automation.

> Workflows are authored in the **provider console** and delivered into a
> customer workspace as sealed copies. If you are a customer reading this, you
> will *run* the workflow below rather than build it; the anatomy is still
> worth knowing, because it is what the run screen is showing you.

## The shape we are building

```
start ──► history ──► classify ──► decide ──┬─(true)──► remediate ──► writer ──► done
                                            └─(false)──────────────────┘
```

Five node types appear here: `start`, `platform`, `llm`, `condition`, `job`
and `end`. The full set and every field is in
[DAG Syntax](../workflows/dag-syntax.md).

## 1. Declare the input form

The `start` node is the form an operator fills in before the run begins. Its
fields become references the rest of the graph can read.

```json
{
  "inputs": [
    { "variable": "service", "label": "Affected service", "type": "text", "required": true },
    { "variable": "hours",   "label": "Look back (hours)", "type": "number", "default": 24 }
  ]
}
```

A variable name must be a valid identifier — it becomes a reference name inside
prompts, and anything else makes `{{#start.service#}}` ambiguous to parse.

## 2. Read what actually happened

The `platform` node reads **the workspace's own record**: the automations that
ran in this project, what they returned, what failed, what is parked waiting for
a human. It is the one evidence source in the platform that needs no customer
credential, because the data is already the platform's — it works identically
for an AWS estate, an on-premises VMware one, or both at once.

```json
{
  "id": "history",
  "type": "platform",
  "title": "Recent activity",
  "source": "timeline",
  "windowHours": "{{#start.hours#}}"
}
```

It contributes two fields: `timeline` (one event per line) and `summary`. Both
are rendered as text rather than handed over as JSON — the consumer is a model
reading a narrative, and a wall of nested objects costs tokens and reads worse
than the same facts one event per line.

`source` is either `timeline` (what ran, from core-service) or `incidents`
(what is open right now, from the alert plane). The window is `1`–`168` hours
for `timeline`, and it is a reference here on purpose: the window is the single
most important knob for correlation, so the operator asking the question should
be able to set it, not the author who wrote the workflow months ago.

## 3. Ask the model

```json
{
  "id": "classify",
  "type": "llm",
  "title": "Needs remediation?",
  "prompt": [
    { "role": "system", "text": "You are an SRE. Answer only from the timeline you are given. Reply with exactly one word: YES or NO." },
    { "role": "user",   "text": "Service: {{#start.service#}}\n\nTimeline:\n{{#history.timeline#}}" }
  ]
}
```

An `llm` node contributes `text`, `prompt_tokens` and `completion_tokens`. A
second one later in the graph (`writer`) turns the same timeline into the prose
report the operator actually receives.

Which model runs is resolved per workspace. A workflow does not hard-code a
vendor: it uses the workspace's own default model, with the tenant's own
credentials, fetched per run and cached nowhere. Credentials were per-tenant
long before the model *choice* was — a workflow that names no model now
inherits the workspace default rather than a platform one.

## 4. Branch

`condition` is deliberately **not** an expression language. A workflow that can
run arbitrary code is one whose blast radius cannot be read off the picture.
A condition is one reference compared to one value:

```json
{
  "id": "decide",
  "type": "condition",
  "when": "{{#classify.text#}}",
  "equals": "YES"
}
```

Its two outgoing edges carry `"branch": "true"` and `"branch": "false"`. The
node itself contributes `result` (the string `"true"` or `"false"`) and
`value` (what the left-hand reference resolved to).

This is also why the prompt above insists on one word. A condition compares
what the model actually returned, so the classifier and the explanation belong
in different nodes.

## 5. Do something about it

The `job` node is what makes a workflow able to **do** rather than only decide.
It runs an automation — a job, or another workflow — through the control plane,
which means credential resolution, the approvals gate and the audit trail all
stay exactly where they already were.

```json
{
  "id": "remediate",
  "type": "job",
  "target": "JOB",
  "targetId": 42,
  "args": { "Service": "{{#start.service#}}" }
}
```

`targetId` is an id, not a name, on purpose: a name is a thing customers
rename, and a workflow that silently stops running the automation it was built
against is worse than one that fails loudly on a missing id.

The node contributes `status`, `output` and `runId`. It **dispatches; it does
not execute** — the call goes to the control plane, which resolves credentials,
applies the approvals gate and writes the audit row exactly as it does for a
person pressing Run. Everything AutoOps can actually perform (the script
library, PowerShell, SSH, Terraform, cloud accounts) is reachable from a
workflow through this node and nowhere else.

> **If the target needs an approval, this node fails the run rather than
> parking it.** Parking a graph mid-flight means persisting where it got to,
> which means a second answer to "what has this run already done" — and when
> the two disagree the cost is a destructive step performed twice. The failure
> message names the approval so an operator can decide it and re-run.

## 6. Finish

```json
{
  "id": "done",
  "type": "end",
  "outputs": [
    { "variable": "report",  "from": "{{#writer.text#}}" },
    { "variable": "service", "from": "{{#start.service#}}" }
  ]
}
```

The `end` node's outputs are the run's **deliverable** — what the customer came
for. The engine trace (node timings, the input they typed, retries) is the
run's **log**, and the console shows the two separately. They used to be one
blob, which meant a customer opened their report and read node timings first.

## Variable references

Every node writes its result into a scope keyed by node id, and any node may
read any **already-executed** node's output with `{{#node.field#}}`.

That is the whole data model. There are no globals, no mutable workflow
variables and no assignment — a canvas where any node can rewrite any value is
a canvas whose behaviour cannot be read off the picture.

The hash delimiters are not decoration. Job steps use `{{Name}}` for run-input
substitution, which happens over step text before execution; a workflow body
passing through that substitution would have its own references eaten. The two
syntaxes cannot collide, which is the point.

**An unresolved reference is an error, not an empty string.** A missing
variable substituted with nothing is how a prompt silently becomes
`Severity:\nAffected service:\n` and the model writes a confident report about
an incident it was told nothing about. A run that stops with

```
node 'writer' references {{#start.serverity#}}, but 'start' produced: service, hours.
```

costs a retype. A run that continues costs a wrong postmortem nobody knows is
wrong.

## Run it

Press **Run**, or:

```
curl -X POST http://localhost:8080/api/workflows/12/run \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"inputs":{"service":"payments-api","hours":6}}'
```

`202 Accepted` comes back with a run id. If the workflow is judged complex or
risky, the run parks in the approvals inbox first — see
[Approval Gates](../workflows/approval-gates.md).

## What gets validated, and when

The graph is validated **structurally, once, before anything executes**. A
missing start, an edge pointing at a node that does not exist, a cycle, an
unknown node type, an `llm` node with no prompt, a `job` node naming no
automation — all of these are save-time errors in the author's face, not a
half-finished run at 3am.

An unknown node type is refused rather than skipped. A workflow that quietly
omits a step is one that reports success having not done the work, and that is
the failure this platform can least afford.

## Next

- [DAG Syntax](../workflows/dag-syntax.md) — every node type and field.
- [Triggers & Schedules](../workflows/triggers-and-schedules.md) — cron and
  webhooks.
- [Handling Failures](../workflows/handling-failures.md) — retries,
  `continueOnError`, timeouts and cancellation.
