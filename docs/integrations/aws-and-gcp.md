# AWS & GCP

Connecting a cloud account, proving it works, and what a step can do with it.

## Supported platforms

| Platform | Live verification | What steps can use it for |
|---|---|---|
| `AWS` | ✅ STS `GetCallerIdentity` | `terraform`, `awslambda`, `pyscript` (boto3), `powershell` (AWS.Tools) |
| `GCP` | ✅ Resource Manager project lookup | `terraform`, `pyscript` |
| `AZURE` | ✅ Entra ID grant + ARM subscription lookup | `terraform`, `azurefn`, `pyscript`, `powershell` (Az) |
| `M365` | ✅ Entra ID grant + Graph `/v1.0/organization` | `pyscript` (Graph), `powershell` (Microsoft.Graph) |
| `KUBERNETES` | ✅ `kubectl get --raw /version` | `kubernetes` — see [Kubernetes](kubernetes.md) |
| `HUAWEI`, `ORACLE` | ❌ Reported as `supported: false` rather than pretending | `terraform`, `pyscript` |

## Connecting an account

```
POST /api/cloud/connections
{
  "name": "prod-aws",
  "platform": "AWS",
  "projectId": 3,
  "credentials": { "accessKeyId": "...", "secretAccessKey": "...", "region": "eu-west-1" }
}
```

Credentials are encrypted with AES-256-GCM under `CLOUD_CRED_KEY` the moment
they arrive. They are decrypted only to be handed to the step runtime for one
call, and are never persisted there.

Saving triggers a **real, read-only** verification call — the provider's
cheapest "who am I". Nothing is ever mutated. The response carries the account
identity back, which is how you find out you pasted staging credentials into the
production connection before a step does.

AWS verification is signed for the **integration's own region**, because the
global STS endpoint refuses opt-in regions. Azure verification distinguishes
"wrong subscription id" from "no role assigned": when ARM answers 404 or 403 it
asks which subscriptions the app can actually see.

## One account, one tenant

A cloud account can be claimed by exactly one tenant. The claim is keyed on
`(platform, kind, fingerprint)`, where the fingerprint is an HMAC-SHA256 of the
identifying value under `CLOUD_CRED_KEY` — the account number itself is never
stored, so the table cannot be read back into a list of customer accounts.

Trying to connect an account another tenant already holds is refused and audited
as `CONNECTION_CLAIM_REJECTED`. Credentials *proven* to belong to another
tenant's account are destroyed and audited as `CONNECTION_QUARANTINED`.

## How a step gets the credentials

```
core-service                      the step runtime
────────────                      ────────────────
resolve the step's integration
  step's `connection` name, else
  the tenant's single match
decrypt                    ──────► execute with the bundle
                                     AWS_*  / ARM_* / GOOGLE_* env vars
                                     scratch files at 0600, deleted after
```

**Ambiguity is an error.** If the step names no connection and the tenant has
two AWS integrations, the step fails with a clear message rather than picking
one. Set the step's `connection` field.

## What each step type does with a cloud account

### `terraform`

Real OpenTofu (Terraform-CLI-compatible): `init` then `plan`, `apply` or
`destroy` according to the step's `action` (default `apply`), in a scratch
workspace. The step's value is the `main.tf` content. Credentials are injected
as provider environment variables — `AWS_*`, `ARM_*`, `GOOGLE_*` — so a
standard provider block needs no extra configuration. Provider-free configs run
without any integration at all.

### `awslambda`

A real `Invoke`, SigV4-signed by hand (no SDK). Line 1 is the function name or a
full ARN; the lines after are the JSON payload. The region comes from the step's
`region`, the ARN, or the integration, in that order. The function's CloudWatch
log tail lands in the run log.

Optional step fields: `invocationType` (`Event` for async), `qualifier` (alias
or version), `endpoint` (a LocalStack-style override).

### `azurefn`

A real HTTP-trigger call. Line 1 is the URL, optionally prefixed with a method;
the lines after are the body. The method defaults to `POST` with a body and
`GET` without. The key comes from the Azure integration's `functionKey` or a
`?code=` already in the URL — anonymous functions need neither, and a `?code=`
is **masked in the log**.

### `pyscript`

Python 3 with `requests` in the image, and cloud credentials in the environment.
This is what most real automation uses: boto3 against AWS, and — because an
Azure connection supplies exactly Microsoft Graph's client-credentials contract
(`AZURE_TENANT_ID` / `AZURE_CLIENT_ID` / `AZURE_CLIENT_SECRET`) — Graph against
Microsoft 365 with no new plumbing.

### `powershell`

PowerShell Core with the cross-platform modules: AWS.Tools, Az, PowerCLI,
Microsoft.Graph, SqlServer. `Connect-AzAccount` and AWS.Tools authenticate from
the injected environment without further setup.

It does **not** run Windows-only cmdlets (`Get-ADUser`, the Exchange shell).
Those need a real Windows host over WinRM, which is a transport gap rather than
a missing module.

> `powershell` steps are **refused** under the default `rundeck` execution mode,
> at the boundary rather than deep inside somebody's script, for exactly that
> reason. They run under `EXECUTION_MODE=remote`. The fix is a Windows
> execution node.

## The script catalogue, measured

The provider catalogue holds 213 PowerShell automations. Their real coverage,
counted rather than estimated, against the job-service image
(`EXECUTION_MODE=remote`):

| | Scripts |
|---|---|
| Run today | 35 |
| Need modules that are not in the image | 154 |
| Need WinRM to a Windows host | 24 |

A catalogue item's requirements are shown before a customer runs it, along with
what granting them involves.

## Agents against cloud accounts

The limit on what an agent can do has never been the reasoning — it is which
credentials a step can be handed. That is why the shipped agents are the ones
they are:

| Agent | Reads |
|---|---|
| `aws.public_exposure_auditor` | S3 public access, security-group ingress, IAM credential hygiene |
| `aws.cost_anomaly_investigator` | Cost Explorer deltas, idle resource inventory |
| `aws.idle_resource_reclaimer` | Idle inventory, unused volume reclaim *(the one that mutates)* |
| `aws.incident_rca_analyst` | CloudWatch alarm state, CloudTrail change timeline |
| `m365.offboarding_auditor` | Licence assignment, mailbox rules |
| `m365.privileged_access_auditor` | Privileged access, licence assignment |

The value in each is the **join**, not the listing. Cost Explorer says which
*service* rose; only correlation says which *resources* explain it, and how much
it does not explain. A list of admins is an org chart; a tier-zero role holder
with no MFA registered is a finding.

Read-only agents collect all their tools in one turn and then reason with the
tools removed. The one mutating agent is the only one whose graph reaches the
approval gate — and its `GATHER` phase is never *shown* the delete tool, so the
candidate list cannot be contaminated by it.

## Disconnecting

`DELETE /api/cloud/connections/{id}` disconnects. **The stored credentials are
destroyed**; the record survives so history still reads. A disconnected row that
still held credentials would be a `CREDENTIAL_HYGIENE` governance violation, so
the purge is enforced by construction rather than by policy.

## Related

- [Kubernetes](kubernetes.md)
- [Vault Integrations](../security/vault-integrations.md)
- [Execution Engine](../architecture/execution-engine.md)
