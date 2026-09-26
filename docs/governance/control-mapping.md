# Control Mapping & Evidence Reference

Every check AutoOps performs, the exact condition that produces each result, and
how each one maps to SOC 2, ISO/IEC 27001, HIPAA, PCI DSS and GDPR control
references.

Read the **Scope & Limitations Statement** first. In particular: these mappings
express which framework control a check is *relevant to*. They do not claim the
check satisfies that control.

## The eight checks

Every framework report is assembled from this set. There are eight checks; a
given framework uses between six and eight of them.

---

### 1. Change authorization

**Question:** are changes to automation authorized before they run?

**Reads:** every job and workflow in the project, plus the tenant's approval
settings.

| Result | Condition |
|---|---|
| `NOT_APPLICABLE` | The project contains no jobs and no workflows |
| `FAIL` | Risky-type gating is disabled **and** no job requires approval |
| `PASS` | Anything else |

**Evidence recorded:** how many jobs require approval out of the total, how many
workflows are auto-gated out of the total, the complexity threshold in nodes,
and either the active risky step types or the fact that risky-type gating is
disabled.

> **Known weakness.** A project where gating is enabled but no automation
> actually reaches the gate still passes. See Scope & Limitations.

---

### 2. Segregation of duties

**Question:** is the person who requests a change someone other than the person
who approves it?

**Reads:** the most recent 200 approval records for the project.

| Result | Condition |
|---|---|
| `FAIL` | Any resolved approval was decided by its own requester |
| `NOT_APPLICABLE` | No approval has been resolved yet |
| `PASS` | Every resolved approval was decided by someone else |

The comparison is case-insensitive on the account identifier.

**Evidence recorded:** the number of decisions on record and, on failure, how
many were self-decided.

**Supporting platform behaviour:** only admins can decide approval requests, and
this is enforced server-side rather than in the console.

---

### 3. Timely evaluation *(SOC 2 only)*

**Question:** are pending change requests reviewed without undue delay?

**Reads:** pending approvals for the project.

| Result | Condition |
|---|---|
| `WARN` | Any approval has been pending more than **7 days** |
| `PASS` | None has |

This check can never fail — a slow decision is a warning, not a control failure.
The same 7-day threshold drives the `APPROVAL_SLA` governance policy.

---

### 4. Credential protection

**Question:** are secrets used to reach cloud environments encrypted at rest?

**Reads:** all cloud integrations in the **tenant** — note, not just the
project's.

| Result | Condition |
|---|---|
| `NOT_APPLICABLE` | No connected integrations exist |
| `WARN` | Some connected integrations hold no stored credentials |
| `PASS` | Every connected integration stores credentials |

The warning is not about weak encryption — everything stored is encrypted. It
flags integrations relying on **ambient access** instead of a managed
credential, which is outside what the platform can protect or revoke.

**Supporting platform behaviour:** credentials are encrypted with **AES-256-GCM**
at rest, are never returned by any endpoint, and are decrypted only to be handed
to the step runtime for a single call. Vault entries under `/api/secrets` are
write-only: no read path exists.

---

### 5. Access revocation

**Question:** are credentials purged when an integration is decommissioned?

**Reads:** all cloud integrations in the tenant.

| Result | Condition |
|---|---|
| `FAIL` | Any disconnected integration still holds stored credentials |
| `PASS` | None does |

**Evidence recorded:** how many integrations have been revoked and confirmation
that their credentials were purged — or, on failure, how many still hold them.

This is the same condition as the `CREDENTIAL_HYGIENE` governance policy. Under
normal operation it cannot fail, because disconnect purges credentials as part
of the same operation. A failure indicates a platform defect rather than a
process lapse.

---

### 6. Configuration management

**Question:** are automation definitions versioned in source control?

**Reads:** the project's SCM configuration.

| Result | Condition |
|---|---|
| `PASS` | A git repository is configured for the project |
| `FAIL` | None is |

**Evidence recorded:** the configured repository URL and branch.

Presence of configuration is what is checked. Whether a recent export actually
succeeded is **not** checked.

---

### 7. Audit evidence retention

**Question:** is execution history retained long enough to serve as evidence?

**Reads:** the tenant's plan retention depth.

| Result | Condition |
|---|---|
| `PASS` | Retention is unbounded, **or** at least the framework guideline |
| `FAIL` | Retention is below the guideline |

Guidelines by framework:

| Framework | Guideline |
|---|---|
| SOC 2 | 90 days |
| ISO/IEC 27001 | 90 days |
| HIPAA | 180 days |
| PCI DSS | 365 days |
| GDPR | 30 days |

Plan retention: Starter 30 days, Team 90, Business 180, Enterprise 730.

> **Two things an auditor will ask about.** These guidelines are the platform's
> own reading of each framework's expectations, not a quotation from it — check
> them against your own interpretation. And AutoOps bounds history **on read**
> rather than deleting it: older runs vanish from lists and return 404, but the
> rows remain. If your obligation is to *delete* after a period, this control
> does not demonstrate that.

---

### 8. Operations monitoring

**Question:** are executions monitored, and do failures stay within tolerance?

**Reads:** up to the most recent 200 runs in the project from the last
**30 days**.

| Result | Condition |
|---|---|
| `NOT_APPLICABLE` | No finished runs in the window |
| `FAIL` | Failure rate above **25%** |
| `WARN` | Failure rate above **10%** |
| `PASS` | Failure rate at or below 10% |

Only `SUCCEEDED` and `FAILED` runs count as finished.

**Evidence recorded:** the run count in the window, how many succeeded, how many
failed, the failure percentage, and confirmation that every run keeps a
step-level log.

The 25% threshold is shared with the `FAILURE_BUDGET` governance policy, so the
two cannot disagree.

---

## Framework mappings

The same eight checks, expressed against each framework's own control
references.

### SOC 2 — 8 controls

| Ref | Control | Check |
|---|---|---|
| CC8.1 | Change authorization | Change authorization |
| CC5.3 | Segregation of duties | Segregation of duties |
| CC7.3 | Timely evaluation | Timely evaluation |
| CC6.1 | Credential protection | Credential protection |
| CC6.5 | Access revocation | Access revocation |
| A1.2 | Configuration recoverability | Configuration management |
| CC4.1 | Audit evidence retention | Audit evidence retention |
| CC7.2 | Operations monitoring | Operations monitoring |

### ISO/IEC 27001 — 7 controls

| Ref | Control | Check |
|---|---|---|
| A.8.32 | Change management | Change authorization |
| A.5.3 | Segregation of duties | Segregation of duties |
| A.8.24 | Use of cryptography | Credential protection |
| A.8.10 | Information deletion | Access revocation |
| A.8.9 | Configuration management | Configuration management |
| A.8.15 | Logging | Audit evidence retention |
| A.8.16 | Monitoring activities | Operations monitoring |

References are to the Annex A control set of ISO/IEC 27001:2022.

### HIPAA — 7 controls

| Ref | Control | Check |
|---|---|---|
| §164.308(a)(4) | Access authorization | Change authorization |
| §164.308(a)(3) | Workforce security | Segregation of duties |
| §164.312(a)(2)(iv) | Encryption and decryption | Credential protection |
| §164.308(a)(3)(ii)(C) | Termination procedures | Access revocation |
| §164.312(c)(1) | Integrity | Configuration management |
| §164.312(b) | Audit controls | Audit evidence retention |
| §164.308(a)(1)(ii)(D) | Activity review | Operations monitoring |

> AutoOps has no knowledge of whether any run touched protected health
> information. This report measures automation hygiene against HIPAA control
> references; it does not evaluate PHI handling.

### PCI DSS — 7 controls

| Ref | Control | Check |
|---|---|---|
| Req 6.5.1 | Change control | Change authorization |
| Req 6.4.2 | Separation of duties | Segregation of duties |
| Req 8.6.2 | Credential protection | Credential protection |
| Req 8.2.5 | Access revocation | Access revocation |
| Req 6.3.2 | Configuration inventory | Configuration management |
| Req 10.5.1 | Log retention | Audit evidence retention |
| Req 10.4.1 | Log review | Operations monitoring |

> The same caveat applies: cardholder data is not identified, classified or
> tracked by AutoOps.

### GDPR — 6 controls

| Ref | Control | Check |
|---|---|---|
| Art. 25 | Data protection by design | Change authorization |
| Art. 32(4) | Processing under authorization | Segregation of duties |
| Art. 32(1)(a) | Encryption | Credential protection |
| Art. 5(1)(e) | Storage limitation | Access revocation |
| Art. 30 | Records of processing | Audit evidence retention |
| Art. 32(1)(d) | Regular testing | Operations monitoring |

> GDPR is the narrowest mapping of the five — six controls, with no
> configuration-management control. Note especially that Art. 5(1)(e) is mapped
> to *credential* storage limitation, not to the storage limitation of personal
> data, which AutoOps cannot see.

## Coverage summary

| Check | SOC 2 | ISO 27001 | HIPAA | PCI DSS | GDPR |
|---|---|---|---|---|---|
| Change authorization | ✓ | ✓ | ✓ | ✓ | ✓ |
| Segregation of duties | ✓ | ✓ | ✓ | ✓ | ✓ |
| Timely evaluation | ✓ | — | — | — | — |
| Credential protection | ✓ | ✓ | ✓ | ✓ | ✓ |
| Access revocation | ✓ | ✓ | ✓ | ✓ | ✓ |
| Configuration management | ✓ | ✓ | ✓ | ✓ | — |
| Audit evidence retention | ✓ | ✓ | ✓ | ✓ | ✓ |
| Operations monitoring | ✓ | ✓ | ✓ | ✓ | ✓ |
| **Total** | **8** | **7** | **7** | **7** | **6** |

## Scoring

```
applicable = total controls − not-applicable controls
score      = round( 100 × (passed + 0.5 × warnings) / applicable )
status     = NON_COMPLIANT if any control FAILed, else COMPLIANT
```

With no applicable controls the score is **100**, which is why the control count
must always be read alongside it.

Scores are **not comparable across frameworks**: the control sets differ in size
and composition, so a GDPR score of 83 and a SOC 2 score of 83 do not describe
the same thing.

## Generating a report

```
POST /api/projects/{projectId}/compliance/reports
     { "framework": "SOC2" }

GET  /api/projects/{projectId}/compliance/reports      list, newest 100
GET  /api/compliance/reports/{id}                      detail
GET  /api/compliance/reports/{id}/download             PDF
```

Framework parsing is lenient — `SOC 2`, `soc2` and `pci-dss` all resolve.

Generation requires the `COMPLIANCE_REPORTS` plan feature (Business and above)
and is audited as `COMPLIANCE_REPORT_GENERATED`. Reading and downloading are
never gated: a customer can always export its own evidence.

Findings are snapshotted onto the report at generation and never recalculated,
so a report remains stable evidence of what was true at that moment.
