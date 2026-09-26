# Kubernetes

Running `kubectl` against a tenant's cluster, and how the execution engine
reaches hosts more generally.

## Connecting a cluster

```
POST /api/cloud/connections
{
  "name": "prod-eks",
  "platform": "KUBERNETES",
  "projectId": 3,
  "credentials": { "kubeconfig": "apiVersion: v1\nclusters:\n..." }
}
```

The kubeconfig is encrypted with AES-256-GCM under `CLOUD_CRED_KEY` on arrival.

Saving runs a **real verification call** — `kubectl get --raw /version` with the
kubeconfig written to a 0600 scratch file — and returns the context, the API
server address, the server version and the platform. Nothing is mutated; it is
the cheapest read the cluster offers.

That is also the fastest way to find out a kubeconfig has an expired exec
credential or points at a cluster that no longer exists, before a step does.

## The `kubernetes` step

```json
{ "type": "kubernetes", "label": "List pods", "value": "get pods -A" }
```

The step's value is the `kubectl` argument line. For anything that takes a
manifest, put the verb on the first line and the manifest after it:

```json
{
  "type": "kubernetes",
  "label": "Apply",
  "value": "apply\napiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: settings\ndata:\n  mode: strict"
}
```

The manifest is piped to `kubectl` on stdin.

Each step gets the kubeconfig decrypted into a **0600 scratch file in its own
workspace**, which is deleted when the step ends. Under `EXECUTION_MODE=remote`,
the step also holds its own throwaway OS uid, so a concurrent step belonging to
another tenant gets `Permission denied` from the kernel rather than a file mode
it could change.

**Ambiguity is an error.** If the tenant has two Kubernetes integrations and the
step names neither, the step fails with a clear message instead of picking one.
Set the step's `connection` field.

## `kubernetes` is a risky node type

`kubernetes` (and its designer alias `k8s`) is in the platform's default risky
set, alongside `terraform`, `awslambda`, `azurefn` and `ssh`. A workflow
containing one is judged **complex**, which means it is gated on an admin
approval regardless of its node count.

That is the whole justification for the set: infrastructure-grade steps touch
real cloud resources, so a fat-fingered run hurts. The set is per-tenant and can
be narrowed — or emptied to disable risky-type gating entirely — in the approval
settings. See [Approval Gates](../workflows/approval-gates.md).

## Terraform against a cluster

A `terraform` step runs real OpenTofu and can manage Kubernetes resources like
any other provider. Cloud credentials for AWS, Azure and GCP are injected as
provider environment variables; a Kubernetes provider block pointing at a
kubeconfig is on you to supply through the config itself.

## How steps reach hosts, in general

`kubectl` talks to an API server, so a cluster needs no agent on any node. Two
other transports are worth knowing about, because they are where the current
limits are.

### `ssh`

```json
{ "type": "ssh", "label": "Restart", "value": "deploy@web-01 systemctl restart app" }
```

Runs through the system ssh client in `BatchMode` — **key authentication only**,
never a password prompt.

> **There is no SSH credential type yet.** The runner works, but there is no SSH
> credential alongside AWS/Azure/GCP/Kubernetes, and the compose stack mounts no
> keys. Under `EXECUTION_MODE=remote` each step also has its own uid and `HOME`,
> so a mounted key would not be readable anyway. The fix is a credential type,
> not a mount.

### Windows

There is **no WinRM transport**. Windows-only PowerShell — `Get-ADUser`, the
Exchange shell — cannot run, and that is a transport gap rather than a missing
module. Of the provider catalogue's 213 scripts, 24 need WinRM specifically.

The fix is a Windows execution node, and it is a known piece of outstanding
work rather than a configuration mistake.

## Fleet dispatch and node inventory

AutoOps runs every step on an execution engine it operates itself
(`EXECUTION_MODE=rundeck`, the default), and that engine is invisible to
customers by construction: its URL and admin token are environment variables
rather than database rows, the adapter in front of it exposes no `/api/**`
surface at all, the engine container publishes no host port, and nothing
tenant-facing names it.

Isolation there is **one engine project per AutoOps project**, with a computed,
sanitized, uniquely-keyed name — so a hostile workspace name cannot smuggle a
path segment or an ACL glob into it, and even a careless change to the naming
function fails loudly rather than collapsing two projects onto one.

What that engine brings that AutoOps did not have is a **node inventory and
dispatch model** — node filters, fan-out across hundreds of hosts, a per-node
result matrix. What stays in AutoOps is orchestration: the approval gate,
per-step retries, `continueOnError`, cancel-between-steps and run history. Steps
are dispatched **one at a time, synchronously**, precisely so none of that moves
out of the product.

Schedules are never pushed into the engine either. Two schedulers over one
runbook is two answers to "why did this run".

## Related

- [AWS & GCP](aws-and-gcp.md)
- [Execution Engine](../architecture/execution-engine.md)
- [Approval Gates](../workflows/approval-gates.md)
