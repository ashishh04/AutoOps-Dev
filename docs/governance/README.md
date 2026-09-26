# Governance & Compliance

The policy set AutoOps publishes to customers. Four documents, written from the
implementation rather than from a template — every threshold, condition and
control reference below is verifiable in `core-service`.

Read them in this order. The first defines the boundary of every claim in the
other three.

| # | Document | Answers |
|---|---|---|
| 1 | [Scope & Limitations Statement](scope-and-limitations.md) | What a compliance report is, what it is not, and what it does not measure |
| 2 | [Governance Policy Catalogue](governance-policy.md) | The five live policies, their thresholds, and what each one enforces |
| 3 | [Control Mapping & Evidence Reference](control-mapping.md) | Every check and its pass/warn/fail conditions, mapped to SOC 2, ISO 27001, HIPAA, PCI DSS and GDPR |
| 4 | [Shared Responsibility Model](shared-responsibility.md) | Which obligations are the platform's and which are the customer's |

## The rule these documents follow

**AutoOps holds no SOC 2, ISO 27001, HIPAA, PCI DSS or GDPR certification.**

Nothing here may state or imply otherwise. What the platform does is evaluate a
small set of operational controls over automation activity, against the control
*references* of those frameworks, using live workspace data. That distinction is
the whole value of this set: it is a document an auditor cannot catch out.

A claim belongs in these files only if it is true of the code today. When a
control changes, the document changes in the same commit.

## Building the PDFs

```
cd frontend
npm run policy-pdfs
```

Output lands in `docs/governance/pdf/`:

| File | |
|---|---|
| `autoops-scope-and-limitations.pdf` | |
| `autoops-governance-policy.pdf` | |
| `autoops-control-mapping.pdf` | |
| `autoops-shared-responsibility.pdf` | |
| `autoops-governance-pack.pdf` | All four, with a contents page — the one to send |

The markdown is the source of truth; the renderer
(`frontend/scripts/policy-pdfs.mjs`) adds a cover page, the
not-a-certification notice, and page furniture, and **nothing else**. It must
never introduce a claim that is not in the markdown.

Each cover carries the document slug, revision, issue date, classification,
owner and the path to its own source file — so a reader holding only the PDF can
find the text it was generated from.

Bump `REVISION` in the renderer when the *content* changes, not when the
renderer does.

## What to hand to whom

| Situation | Send |
|---|---|
| A security questionnaire or vendor review | `autoops-governance-pack.pdf` |
| "Are you SOC 2 certified?" | `autoops-scope-and-limitations.pdf` — it answers plainly, on the cover |
| A customer configuring their workspace | `autoops-governance-policy.pdf` |
| An auditor asking how a control is evaluated | `autoops-control-mapping.pdf` |
| "Who is responsible for X?" | `autoops-shared-responsibility.pdf` |

## Keeping them true

These documents go stale silently, which is the failure mode that matters. The
things most likely to drift:

- the platform default complexity threshold and risky step types;
- the failure budget window, threshold and minimum run count;
- the approval SLA and stale-approval windows;
- plan retention depths, and the per-framework retention guidelines;
- the control catalogue for any framework, and the eight check conditions;
- the scoring formula.

All of them live in `ComplianceService`, `GovernanceService`,
`GovernancePolicy` and `WorkflowComplexity`. A change to any one is a change to
these documents.
