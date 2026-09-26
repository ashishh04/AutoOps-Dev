# AutoOps Documentation

The source of truth for the docs rendered at `/docs` in the console. Each file
here is one page; the console reads them at build time, so editing a file is
editing the site.

Every page describes **what the platform actually does today**, including the
parts that are not finished. Where something is missing or degraded, it is said
plainly rather than omitted — a documentation set that only lists good news is
the one that gets disbelieved at 3am.

## Getting Started

| Page | |
|---|---|
| [Quickstart Guide](getting-started/quickstart.md) | Stack up, workspace created, first run — about fifteen minutes |
| [Installation & Setup](getting-started/installation-and-setup.md) | Topology, every environment variable, the secrets you must change |
| [Core Concepts](getting-started/core-concepts.md) | Tenant, project, job, workflow, agent, run, approval, plan |
| [First Workflow](getting-started/first-workflow.md) | A graph that decides, then acts |

## Architecture

| Page | |
|---|---|
| [Execution Engine](architecture/execution-engine.md) | What happens between pressing Run and reading the output |
| [Event Bus & Streams](architecture/event-bus-and-streams.md) | How work moves — and why there is no message broker |
| [State Management](architecture/state-management.md) | Where every durable fact lives, and what holds nothing |
| [High Availability](architecture/high-availability.md) | What scales, what does not, what happens when a dependency dies |

## Workflows

| Page | |
|---|---|
| [DAG Syntax](workflows/dag-syntax.md) | Every node type, field, reference and validation rule |
| [Triggers & Schedules](workflows/triggers-and-schedules.md) | Manual, cron, webhook, agent — and what each one skips |
| [Approval Gates](workflows/approval-gates.md) | When a run waits for a person, and who may decide |
| [Handling Failures](workflows/handling-failures.md) | Retries, `continueOnError`, timeouts, cancellation |

## Security & RBAC

| Page | |
|---|---|
| [Authentication (OIDC)](security/authentication-oidc.md) | Tokens, claims, revocation, SSO, key rotation |
| [Role-based Access](security/role-based-access.md) | Four authorization layers, and where the console and backend differ |
| [Vault Integrations](security/vault-integrations.md) | Every secret the platform holds and the one path each may travel |
| [Audit Logs](security/audit-logs.md) | The closed event catalogue, retention, and what reads it |

## Integrations

| Page | |
|---|---|
| [AWS & GCP](integrations/aws-and-gcp.md) | Connecting accounts, live verification, what each step type does |
| [Kubernetes](integrations/kubernetes.md) | `kubectl` steps, risky-type gating, fleet dispatch |
| [Slack & Teams](integrations/slack-and-teams.md) | Connectors, in-app notifications, and what is not wired yet |
| [Custom Webhooks](integrations/custom-webhooks.md) | Inbound triggers and the alert plane |

## API Reference

| Page | |
|---|---|
| [REST API](api/rest-api.md) | Routing, conventions, error codes, the whole surface |
| [Authentication](api/authentication.md) | Tokens, refresh, service clients |
| [Rate Limits](api/rate-limits.md) | 600/min per tenant, and every other throttle |
| [Websockets](api/websockets.md) | The one socket that exists, and how to poll well |

## Governance & Compliance

A separate set, written for customers and auditors rather than for operators,
and published as PDFs — see [`governance/`](governance/README.md).

| Page | |
|---|---|
| [Scope & Limitations Statement](governance/scope-and-limitations.md) | What a compliance report is, what it is not, and what it does not measure |
| [Governance Policy Catalogue](governance/governance-policy.md) | The five live policies, their thresholds, and what each enforces |
| [Control Mapping & Evidence Reference](governance/control-mapping.md) | Every check, mapped to SOC 2, ISO 27001, HIPAA, PCI DSS and GDPR |
| [Shared Responsibility Model](governance/shared-responsibility.md) | Which obligations are the platform's and which are the customer's |

These four are **not** part of the six-category console docs site; they are
distributed as documents. `cd frontend && npm run policy-pdfs` rebuilds them
into `docs/governance/pdf/`.

## Editing these docs

- The first `# Heading` is the page title; the first paragraph after it is the
  summary shown on the docs landing page. Both are read automatically — do not
  add front matter.
- Links between pages are ordinary relative markdown links and are rewritten to
  console routes at render time.
- Supported markdown: headings, paragraphs, bullet and numbered lists, tables,
  fenced code blocks, blockquotes, bold, inline code and links. Anything beyond
  that renders as plain text rather than failing.
- A new page must be added to `frontend/src/data/docs.js` to appear in the
  console — ordering is explicit there rather than alphabetical.

## Related reading in this repository

Service-level detail lives with each service, and goes deeper than these pages
do:

```
backend/core-service/README.md       projects, runs, governance, compliance
backend/job-service/README.md        the step sandbox, every step type
backend/rundeck-service/README.md    the execution engine adapter
backend/agent-service/README.md      the agent loop, tools, approvals
backend/agent-runtime/README.md      phases, evidence, the shipped agents
backend/workflow-service/README.md   workflow definitions and the split
backend/alert-service/README.md      the alert plane and its boundary
backend/auth-service/README.md       identity
```
