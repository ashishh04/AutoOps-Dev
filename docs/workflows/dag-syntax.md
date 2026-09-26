# DAG Syntax

The complete contract for a workflow definition: node types, fields, references
and the validation rules that run before anything executes.

## The document

A workflow is a directed acyclic graph of typed nodes plus a declared input
form. It carries no vendor's schema — this is AutoOps's own contract, and it is
deliberately small enough to read in one sitting.

```json
{
  "version": 1,
  "name": "Incident postmortem",
  "description": "Explains what happened and, if needed, remediates",
  "inputs": [ ... ],
  "nodes": [ ... ],
  "edges": [ ... ]
}
```

`version` is bumped when a field **changes meaning**, not when one is added. It
is sent back on every run so a definition written by a newer console cannot be
silently misread by an older runtime.

## The input form

Each entry in `inputs` is one control on the form an operator fills in before
the run starts.

```json
{
  "variable": "service",
  "label": "Affected service",
  "type": "text",
  "required": true,
  "default": null,
  "options": [],
  "maxLength": 120,
  "hint": "The service that went red"
}
```

| `type` | Control |
|---|---|
| `text` | Single-line |
| `paragraph` | Multi-line |
| `select` | A choice from `options` — **required** for this type |
| `number` | Numeric |
| `boolean` | Toggle |

`variable` must be a valid identifier. It becomes a reference name inside
prompts, and anything else makes `{{#start.x#}}` ambiguous to parse.

## Node types

The set is **closed**. An unknown type is refused at parse time rather than
skipped — a workflow that quietly omits a step is one that reports success
having not done the work.

### `start`

Exactly one per workflow. Contributes the run's inputs to the scope, keyed by
their `variable` names. It executes rather than being skipped, so the run log
shows it and a graph with a start node and nothing else is still a legal,
traceable run.

### `llm`

One model call.

| Field | Meaning |
|---|---|
| `prompt` | A list of `{role, text}`; `role` is `system`, `user` or `assistant`. **Required** |
| `model` | Overrides the run's default model. Absent means the workspace default |
| `temperature`, `maxTokens` | Per-node overrides |

**Contributes:** `text`, `prompt_tokens`, `completion_tokens`.

### `platform`

Reads the workspace's own record — the only evidence source in the platform
that needs **no customer credential**, because the data is already the
platform's. It reads the same whether the customer runs AWS, on-premises
VMware, or both, because all of it was automated through one control plane.

| Field | Meaning |
|---|---|
| `source` | `timeline` (what ran in this project) or `incidents` (what is open right now) |
| `windowHours` | `1`–`168` for `timeline`. An integer, **or a reference** resolved at run time |

**Contributes:** `timeline` and `summary` for the timeline source; `incidents`
for the incident source. All rendered as text, not JSON — the consumer is a
model reading a narrative, and a wall of nested objects costs tokens and reads
worse than the same facts one event per line.

The window ceiling is an honesty guard, not a performance one. A model handed a
month of history will find a correlation in it, because in a month something
always happened before something else.

### `job`

Runs an automation — a job, or another workflow — through the control plane.
This is the node that makes a workflow able to **do** something rather than only
decide something.

| Field | Meaning |
|---|---|
| `target` | `JOB` or `WORKFLOW` |
| `targetId` | The row id in that table. **Required** |
| `args` | Values for the target's own input form; references resolve first |

**Contributes:** `status`, `output`, `runId`.

Ids rather than names, on purpose: a name is a thing a customer renames, and a
workflow that silently stops running the automation it was built against is
worse than one that fails on a missing id.

It **dispatches; it does not execute.** Credential resolution, the approvals
gate and the audit trail all stay exactly where they already were.

> **An approval fails this node rather than parking the run.** Parking a graph
> mid-flight means persisting where it got to, which means a second answer to
> "what has this run already done" — and when the two disagree the cost is a
> destructive step performed twice. The message names the approval so an
> operator can decide it and re-run.

### `http`

One HTTP call.

| Field | Meaning |
|---|---|
| `method` | Default `GET` |
| `url` | **Required**; references resolve |
| `headers` | Map; values resolve |
| `body` | References resolve |

**Contributes:** `status`, `body` (truncated past a size cap, with a visible
marker).

A non-2xx is **returned, not raised**: an API answering 404 is often the answer
a workflow is asking for, and a node that cannot observe it cannot branch on it.
A transport failure — DNS, TLS, timeout — is a different thing, and does fail
the node.

### `template`

String assembly between nodes, for reshaping one node's output into another's
input.

| Field | Meaning |
|---|---|
| `template` | The string, with references. **Required** |

**Contributes:** `text`.

### `condition`

A branch. Equality against a literal, and nothing more.

| Field | Meaning |
|---|---|
| `when` | A reference. **Required** |
| `equals` | The value to compare against |

**Contributes:** `result` — the string `"true"` or `"false"` — and `value`, what
the left-hand side resolved to.

This is deliberately **not** an expression language. A workflow that can run
arbitrary code is a workflow whose blast radius cannot be read off the canvas,
and every branch anyone has actually needed is this one.

### `end`

At least one per workflow. Collects the run's declared outputs by reference.

```json
{
  "id": "done",
  "type": "end",
  "outputs": [
    { "variable": "report", "from": "{{#writer.text#}}" }
  ]
}
```

These outputs are the run's **deliverable**. The engine trace is the run's log,
and the console shows the two separately.

## Edges

```json
{ "source": "decide", "target": "remediate", "branch": "true" }
```

`branch` is `"true"` or `"false"` on the two arms of a condition, and absent
everywhere else.

## References

`{{#node.field#}}` reads any **already-executed** node's output. Every node
writes its result into a scope keyed by node id, and that is the entire data
model — no globals, no mutable workflow variables, no assignment.

The hash delimiters exist to avoid a collision. Job steps use `{{Name}}` for
run-input substitution, which runs over step text before execution; a workflow
body passing through that substitution would have its own references eaten.

**An unresolved reference is an error, not an empty string.** The failure names
the node an author has to go and fix, and what the referenced node actually
produced:

```
node 'writer' references {{#start.serverity#}}, but 'start' produced: service, hours.
```

A missing variable substituted with nothing is how a prompt silently becomes
`Severity:\nAffected service:\n` and the model writes a confident report about
an incident it was told nothing about.

## Validation

Structural, once, before anything executes. All of these are save-time errors:

| Rule | Message |
|---|---|
| Node ids are unique | `duplicate node ids: [...]` |
| Exactly one `start` | `a workflow needs exactly one start node, found N` |
| At least one `end` | `a workflow needs at least one end node` |
| Every edge endpoint exists | `edge references unknown node(s): [...]` |
| No cycles | `the graph contains a cycle: a -> b -> a` |
| Node ids are identifiers | `node id 'my node' is not a valid identifier` |
| Type-specific required fields | `llm node 'writer' has no prompt` |

Cycles are **refused, not bounded by a recursion limit**. A limit turns a cycle
into a run that burns tokens for a while and then fails with a message about
recursion, which tells the author nothing about their graph. Refusing names the
nodes involved, at save time.

## Plan limits

| Limit | Enforced by |
|---|---|
| `MAX_NODES` per workflow | workflow-service, counting the `nodes` array **server-side** from the canvas JSON |
| `MAX_AUTOMATIONS` | Shared between workflows and agents — an autonomous operator is an automation |

A client-supplied node count would be a way around the limit, so it is never
trusted.

## Related

- [First Workflow](../getting-started/first-workflow.md) — a worked example.
- [Triggers & Schedules](triggers-and-schedules.md)
- [Approval Gates](approval-gates.md)
- [Handling Failures](handling-failures.md)
