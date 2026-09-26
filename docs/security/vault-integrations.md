# Vault Integrations

Every secret AutoOps holds, where it is encrypted, and the one path each is
allowed to travel.

## The built-in vault

`/api/secrets` is a per-tenant key/value store whose values are **write-only**.

```
GET    /api/secrets            metadata only — path, type, who, when
POST   /api/secrets            { "path": "prod/db/password", "type": "OPAQUE", "value": "..." }
PUT    /api/secrets/{id}
DELETE /api/secrets/{id}
```

| Property | Detail |
|---|---|
| Encryption | AES-256-GCM under `CLOUD_CRED_KEY` |
| Read path | **None.** No endpoint returns a value, ever |
| Path | Letters, digits, dots, dashes and slashes; unique per tenant |
| Types | `OPAQUE`, `TLS`, `SSH` |
| Audit | `SECRET_CREATED`, `SECRET_UPDATED`, `SECRET_DELETED` |

The absence of a read endpoint is the design. A vault you can read back over the
API is a vault whose blast radius is one leaked token.

Managing secrets requires the `manageKeys` capability — admin only.

## Cloud integration credentials

A cloud integration stores the credentials a step needs to reach a provider.

| Property | Detail |
|---|---|
| Platforms | `AWS`, `AZURE`, `GCP`, `HUAWEI`, `ORACLE`, `M365`, `KUBERNETES` |
| Encryption | AES-256-GCM under `CLOUD_CRED_KEY`, in core-service |
| Travel | Decrypted for **one call** and shipped with the execute request over the internal network |
| Persistence downstream | None. job-service writes scratch files (0600) and deletes them after each step |

Resolution for a step is explicit: the step's optional `connection` name, else
the tenant's single matching integration. **Ambiguity is an error** — a step
that could have meant either of two AWS accounts fails with a clear message
rather than running blind against one of them.

### Disconnect purges

Disconnecting an integration **destroys the stored credentials**; the record
survives so history still reads. A disconnected row that still holds credentials
is a `CREDENTIAL_HYGIENE` governance violation by construction — the policy is
enforced by design rather than configured.

### One cloud account, one tenant

A cloud account can be claimed by exactly one tenant. The claim table's unique
key is `(platform, kind, fingerprint)`, and the fingerprint is an **HMAC-SHA256
of the identifying value under `CLOUD_CRED_KEY`** — the value itself is never
stored, so the table cannot be read back into a list of customer account
numbers.

Two audit events come out of this: `CONNECTION_CLAIM_REJECTED` when an account
already held by another tenant is refused, and `CONNECTION_QUARANTINED` when
credentials are *proven* to belong to another tenant's account and are
destroyed.

### Verification is a real call

Saving or re-checking an integration performs the provider's cheapest read-only
"who am I" call. Nothing is ever mutated.

| Platform | The check |
|---|---|
| AWS | STS `GetCallerIdentity`, signed for the integration's own region — the global endpoint refuses opt-in regions |
| Azure | Entra ID client-credentials grant, then the ARM subscription lookup |
| M365 | Entra ID grant for Graph, then `/v1.0/organization` |
| GCP | Service-account JWT-bearer grant, then the Resource Manager project lookup |
| Kubernetes | `kubectl get --raw /version` with the kubeconfig in a 0600 scratch file |

Platforms with no live check report `supported: false` rather than pretending to
have verified something. The decrypted secret never crosses the gateway.

Azure verification distinguishes "wrong subscription id" from "no role
assigned": when ARM answers 404 or 403 it asks which subscriptions the app
*can* see, so the error tells you which mistake you made.

## Model provider keys

Stored in core-service, AES-GCM, and fetched **per run, per model** by
agent-service, which caches nothing.

That is a deliberate cost. A cached key would survive a rotation the tenant
believes took effect and outlive a provider they just disabled.

There are two model-provider screens and they are **not** duplicates: one is the
platform-level provider configuration, the other is a tenant bringing its own
key. Credentials were per-tenant before the model *choice* was — a workflow now
resolves the workspace's own default model rather than a platform one.

## Connector configuration

Slack webhook URLs, generic webhook URLs and GitHub tokens are AES-GCM encrypted
and never returned by any endpoint. `POST /api/connectors/{id}/test` performs a
**real call** against the target and records the outcome, so a connector that
looks configured and is not says so.

## The execution engine's own token

The engine's admin token is command execution on every node in the platform, so
it is not stored like a customer credential at all — it comes from an
environment variable and **never touches a database row**.

- `RUNDECK_API_TOKEN` lives only in rundeck-service's environment.
- rundeck-service exposes **no `/api/**` surface** and has no gateway route, so
  no tenant-facing request can reach the service that holds it.
- The engine container publishes no host port.
- The repository ships a **dev token** in a mounted `tokens.properties`.
  Override the file, or bake your own image, anywhere that is not a laptop —
  `ProdSafetyGuard` refuses to boot with it.
- A dispatch receipt records the step type and the engine execution id, never
  the step body and never the credential bundle.

> **Credential injection is a known, named regression here.** Credentials arrive
> as `export` lines at the top of the generated script, because the engine's
> ad-hoc endpoint has no process-environment equivalent — secure options exist
> only for saved jobs, and Key Storage would copy the vault into the engine.
> Mitigated, not solved: tracing is disabled before the exports, values are
> single-quote escaped, and the uploaded script is deleted after the run. **Do
> not run the engine at `loglevel=DEBUG`.**

## Key rotation, and what it costs

Each encryption key is a one-way door:

| Key | Protects |
|---|---|
| `CLOUD_CRED_KEY` | Cloud credentials, vault entries, connector config, model keys, claim fingerprints |
| `RUNDECK_INTERNAL_TOKEN` / `JOB_INTERNAL_TOKEN` | Step execution, for every tenant |
| `RUNDECK_API_TOKEN` | Admin on the execution engine |
| The other internal tokens | Service-to-service calls |
| The JWT keystore | Token signatures |

**Changing an encryption key orphans everything already encrypted with the old
one.** There is no re-wrap path. Rotating `CLOUD_CRED_KEY` means re-entering
every credential — and it also invalidates every claim fingerprint, since those
are HMACs under the same key.

Token signing keys are the exception and rotate cleanly: publish a new alias in
JWKS, flip `JWT_KEYSTORE_ALIAS`, drop the old alias after the 15-minute access
token TTL.

## The honest gaps

- **Secrets are supplied to the platform as environment variables.** There is no
  HashiCorp Vault, AWS Secrets Manager or Azure Key Vault integration for the
  platform's *own* secrets. The compose stack falls back to well-known dev
  values for `JOB_INTERNAL_TOKEN`, `SUBSCRIPTION_INTERNAL_TOKEN` and
  `CLOUD_CRED_KEY`; set real ones anywhere that is not a laptop. Every service's
  `ProdSafetyGuard` refuses to boot on a dev default.
- **SSH has no credential type.** The `ssh` step runner works, but there is no
  SSH credential alongside AWS/Azure/GCP/Kubernetes, and each step now has its
  own uid and `HOME`, so a mounted key would not be readable anyway. The fix is
  a credential type, not a mount.
- **No WinRM transport**, so Windows-only PowerShell cmdlets cannot run — that
  is a transport gap, not a missing module.

## Related

- [AWS & GCP](../integrations/aws-and-gcp.md)
- [State Management](../architecture/state-management.md)
- [Audit Logs](audit-logs.md)
