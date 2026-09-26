# Shared Responsibility Model

Which security and governance obligations belong to AutoOps as the platform
provider, and which remain with the customer operating a workspace.

The short version: **AutoOps is responsible for the automation control plane.
The customer is responsible for what they automate, who they let automate it,
and everything the automation touches.**

## At a glance

| Area | AutoOps | Customer |
|---|---|---|
| Tenant isolation | ● | |
| Authentication mechanism | ● | |
| Who holds which role | | ● |
| Credential encryption at rest | ● | |
| Which credentials are supplied, and their scope | | ● |
| Approval gate mechanism | ● | |
| Approval thresholds, and deciding requests | | ● |
| Audit trail capture | ● | |
| Audit review | | ● |
| Step execution isolation | ● | |
| What the step actually does | | ● |
| Platform availability | ● | |
| Target infrastructure availability | | ● |
| Encryption key custody (self-hosted) | | ● |
| Data classification | | ● |

## What AutoOps is responsible for

### Tenant isolation

Every business record carries a tenant id taken from the access token's own
claim — never from a header or request body. The gateway overwrites the
`X-Tenant-ID` header with the token's claim before proxying, so a client cannot
smuggle another workspace's identity past the edge.

A request for another tenant's resource returns **404, not 403**, because a 403
would confirm the resource exists.

### Authentication and token handling

AutoOps operates the identity service, holds the only token-signing key, and
publishes the public keys for local validation. It is responsible for:

- RS256 tokens with a 15-minute access lifetime and `kid` pinning;
- refresh-token rotation with reuse detection that revokes the whole session
  family;
- immediate revocation of every outstanding token on logout-all, offboarding, a
  password reset or a password change;
- rate limiting of credential attempts, and removing timing oracles from the
  login path.

### Credential protection

Cloud credentials, vault entries, connector configuration and model provider
keys are encrypted with **AES-256-GCM** at rest. They are never returned by any
endpoint, and are decrypted only to be handed to the step runtime for a single
call — nothing is persisted there. Disconnecting an integration destroys the
stored credentials.

A cloud account can be claimed by **one tenant only**; the claim is keyed on a
keyed hash of the identifying value, so the table cannot be read back into a
list of customer account numbers.

### Execution isolation

The step runtime executes arbitrary commands by design, so it is contained: no
gateway route, a shared internal token on every call, one throwaway OS identity
per step, a private workspace deleted afterwards, no inherited environment, a
wall-clock timeout that kills the whole process tree, and a bounded concurrency
ceiling.

### The audit trail

AutoOps captures a closed catalogue of events — project, job, workflow, agent,
run, approval, credential, webhook, governance and compliance activity — into a
single trail, and makes it queryable. Adding an event type requires a schema
migration, so the catalogue cannot quietly become a free-text field.

### The mechanisms of governance

The approval gate, the policy engine, the compliance checks and the evidence
snapshot are all provided and maintained by AutoOps, and are documented in the
**Governance Policy Catalogue** and the **Control Mapping & Evidence Reference**.

## What the customer is responsible for

### Who holds which role

AutoOps enforces what a role may do. It does not decide **who** should hold it,
and nothing in the platform reviews role assignment.

This is the most commonly misplaced responsibility in the model. A workspace
where everyone is an admin will pass every governance policy and every
compliance control, because segregation of duties is measured on *recorded
decisions* — and if one person requests and a different person approves, the
check passes regardless of how many admins exist.

Onboarding, offboarding and periodic access review are the customer's.

### What the automation does

AutoOps runs what it is given. It does not inspect a job's contents for safety,
scope or correctness. A job that drops a production table will pass every
control if it is approved, versioned and succeeds.

Reviewing automation content is the customer's, and the approval gate is the
place to do it.

### Which credentials are supplied

AutoOps protects the credential it is given. Choosing a credential with the
least privilege the automation needs — and rotating it — is the customer's.

The platform verifies a credential against the real provider with a read-only
call, so it can confirm the credential *works* and which account it belongs to.
It cannot tell you the credential is over-privileged.

### Deciding approvals, and setting the thresholds

The platform queues an approval and records who decided it. It cannot establish
that the decision was considered. Approval thresholds — the complexity node
count and the risky step types — are tenant configuration, and lowering them to
nothing is permitted.

### Reviewing the evidence

Reports are generated on request, not on a schedule, and violations are computed
on read and never stored. Nobody is notified that a policy is violated unless
somebody looks.

Deciding how often to generate reports, retaining them for the period your
obligations require, and acting on what they say is the customer's.

### Data classification

AutoOps does not know whether a run touched personal data, cardholder data or
protected health information. It has no classification model and no way to
acquire one. Any framework report against GDPR, PCI DSS or HIPAA is therefore
measuring automation hygiene against those control references and nothing about
the regulated data itself.

### Target infrastructure

Everything downstream of a step — the cloud accounts, clusters, hosts,
applications and their own availability, patching, backups and monitoring —
belongs to the customer.

## Self-hosted deployments

Where a customer operates the platform itself, several provider responsibilities
transfer with it:

| Responsibility | Note |
|---|---|
| Encryption key custody | `CLOUD_CRED_KEY` and the token keystore. **Changing an encryption key orphans everything already encrypted — there is no re-wrap path** |
| Internal tokens | The step-runtime internal token guards execution for every tenant. The repository ships development defaults; production startup refuses to boot with them |
| Database durability | The reference topology runs one MySQL instance. Replication and point-in-time recovery are the operator's |
| Transport security | TLS termination and certificate lifecycle |
| Platform patching | Image rebuilds and dependency updates |
| Backups | Including the fact that the script catalogue and user table are not in version control |

## Assurance status

Stated here rather than buried: **AutoOps holds no SOC 2, ISO 27001, HIPAA, PCI
DSS or GDPR certification.** No independent assessor has examined the controls
described in this document. They are accurate descriptions of implemented
behaviour, verifiable in the source, and they are not an attestation.

See the **Scope & Limitations Statement** for the full boundary.

## Escalation

| Situation | Route |
|---|---|
| A control described here does not behave as documented | Report it as a defect; a `CREDENTIAL_HYGIENE` violation in particular indicates a platform fault, not a process lapse |
| A governance policy is blocking legitimate work | An admin can move the policy to `MONITOR` — the block is a tenant setting, not a platform decision |
| Evidence is needed for an audit | Generate a compliance report per project and export it; reads are never gated by subscription state |
