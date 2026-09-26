# Scope & Limitations Statement

What an AutoOps compliance report is, what it is not, and exactly which
questions it can and cannot answer. Read this before any other document in this
set.

## The one thing to be clear about first

**AutoOps holds no SOC 2, ISO 27001, HIPAA, PCI DSS or GDPR certification, and
an AutoOps compliance report is not an audit, an attestation, or a certificate.**

No independent auditor has examined these controls. No opinion is expressed by
anyone but the software itself. Nothing in a generated report should be
presented to a regulator, a customer or an insurer as evidence that AutoOps —
or the organisation using it — is certified against any framework.

What a report *is*: a **point-in-time measurement of a small number of
operational controls over the automation activity inside one AutoOps project**,
expressed against the control references of a named framework so that the result
is easy to place in an existing audit programme.

That is a useful thing. It is a much narrower thing than the framework names on
the cover suggest, and this document exists so nobody has to discover the
difference during an audit.

## What "COMPLIANT" means on a report

A report is marked `COMPLIANT` when **no control in the checked subset returned
FAIL**. It does not mean the organisation is compliant with the framework.

Read it as: *"of the six to eight things AutoOps can measure about this
project's automation, none is currently in a failed state."*

`NON_COMPLIANT` is the stronger signal of the two, and it is the one worth
acting on: at least one control that AutoOps *can* measure is failing right now.

## What is measured

Eight distinct checks exist. A framework's report uses between six and eight of
them:

| Framework | Controls in report |
|---|---|
| SOC 2 | 8 |
| ISO/IEC 27001 | 7 |
| HIPAA | 7 |
| PCI DSS | 7 |
| GDPR | 6 |

Each check reads live workspace data at generation time — approvals, cloud
integrations, SCM configuration, plan retention, and the last 30 days of run
outcomes. The full logic, including the exact pass, warn and fail conditions, is
in the **Control Mapping & Evidence Reference**.

Every measurement is derived from platform records. No check asks a human
whether something is true, and no check accepts an assertion from the customer.

## What is not measured

This list matters more than the one above. None of the following is examined,
and a passing report says nothing about any of it:

**Outside the automation plane entirely**
- The customer's own infrastructure, applications, networks or endpoints.
- Physical security, HR processes, vendor management, business continuity,
  incident response procedures, or security awareness training.
- Anything happening in a cloud account *except* through an AutoOps run.

**Inside the platform, but not checked**
- Whether an approval was *considered* rather than rubber-stamped. AutoOps can
  prove a different person clicked Approve; it cannot prove they read anything.
- Whether a job's contents are safe, correct, or appropriately scoped. A job
  that deletes a production database passes every check if it is approved,
  versioned and succeeds.
- Whether the right people hold admin. Role assignment is checked by nobody.
- Data classification. AutoOps does not know whether a run touched personal
  data, cardholder data or protected health information — so a GDPR, PCI DSS or
  HIPAA report is measuring *automation hygiene against those control
  references*, not the handling of the regulated data itself.
- Encryption in transit between a customer's systems and a target.
- Key management practice. The platform encrypts credentials at rest with
  AES-256-GCM; it does not assess how the encryption key itself is stored,
  rotated or escrowed in a given deployment.

**Structural limits of the evidence**
- **Project scope.** A report covers one project. A workspace with five projects
  needs five reports, and a clean report on one says nothing about the others.
- **Tenant-wide credential checks.** Two checks — credential encryption and
  credential revocation — read *all* cloud integrations in the tenant, not just
  the report's project. A failure there can therefore originate outside the
  project named on the cover.
- **Sampling.** Approvals are read from the most recent 200 for the project;
  runs from the most recent 200 within the 30-day window. A workspace busier
  than that is measured on a sample, not a census.
- **30-day window.** The monitoring control sees only the last 30 days.
- **Point in time.** Findings are snapshotted onto the report when it is
  generated and never recalculated. That is deliberate — evidence must not
  change under the auditor — but it means a report is stale the moment anything
  changes.

## Known weaknesses in the checks themselves

Stated plainly, because an auditor will find them anyway.

**Change authorization can pass on a technicality.** The check fails only when
risky-type gating is disabled *and* no job requires approval. A project where
gating is enabled but no automation actually reaches the gate still passes.

**Segregation of duties is only measured where a decision exists.** With no
resolved approvals, the control is Not Applicable rather than failed — absence
of evidence is reported as absence of applicability.

**Audit retention passes on an unbounded plan.** When the subscription places no
limit on history, the control passes. It is also worth knowing that AutoOps
*bounds history on read rather than deleting it*: older runs disappear from
lists and return 404, but the rows remain. If your obligation is to **delete**
data after a period, AutoOps does not satisfy it.

**The monitoring control measures failure rate, not detection.** A project where
everything succeeds passes — including one where nothing meaningful is being
run.

**Not Applicable inflates the score.** The score divides by *applicable*
controls, so an empty project can score 100 having demonstrated nothing. Always
read the control count alongside the score.

## How the score is calculated

```
applicable = total controls − not-applicable controls
score      = round( 100 × (passed + 0.5 × warnings) / applicable )
```

With no applicable controls the score is **100**. A warning is worth half a
pass. The score is a summary of the checks on the page and nothing more; it is
not comparable between frameworks, because the frameworks do not use the same
control set.

## Who can generate one, and who can read one

- Generating a report is a **mutation**, requires the `COMPLIANCE_REPORTS` plan
  feature (Business tier and above), and is recorded in the audit trail as
  `COMPLIANCE_REPORT_GENERATED`.
- **Reading and downloading a report is never gated.** A customer can always
  retrieve and export its own evidence, including after a subscription lapses.
  Losing access to your own audit evidence because of a billing event would be
  the wrong failure.
- Reports are scoped to the tenant. Another workspace's report id returns `404`,
  never `403` — a 403 would confirm that the report exists.

## Data handling in the report itself

A stored report contains: the framework, the project id and name, who generated
it, when, the status and score, and one row per control carrying the reference,
the requirement, the result and a line of evidence.

The evidence lines include the configured SCM repository URL and branch, counts
of jobs, workflows, approvals, integrations and runs, and the tenant's approval
thresholds. **They contain no credentials, no secret values, no run output and
no personal data beyond the account identifiers of requesters and approvers**
already present in the audit trail.

## If you need a certification

This set of documents supports an audit; it does not replace one. For a SOC 2
Type II report or an ISO/IEC 27001 certificate covering AutoOps as a service,
the path is an independent assessor and a scoped engagement — neither of which
has taken place. Any statement to the contrary would be false, and this platform
would rather publish an honest gap than a flattering claim.

## Related documents

- **Governance Policy Catalogue** — the five live policies and what each one
  enforces.
- **Control Mapping & Evidence Reference** — every check, every framework
  reference, every pass/warn/fail condition.
- **Shared Responsibility Model** — which obligations are the provider's and
  which are the customer's.
