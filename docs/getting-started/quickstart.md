# Quickstart Guide

Bring the whole AutoOps platform up on one machine, sign in, and watch a real
automation execute — in about fifteen minutes.

## What you need

| | |
|---|---|
| Docker Desktop | The whole stack runs as one `docker compose` project |
| Disk | ~6 GB for images, plus the MySQL volume |
| Ports | `5173` (console), `8080` (API gateway), `3308` (MySQL, debug only) |

You do **not** need JDK 21, Maven or Node to run the platform. Those are only
needed to build a service outside its container — see
[Installation & Setup](installation-and-setup.md).

## 1. Start the stack

```bash
cp .env.example .env          # optional: real email, SSO, and your own secrets
docker compose up -d --build
```

The first build is slow because every service is compiled inside its image.
Afterwards, `docker compose up -d` is seconds.

Watch the platform come up:

```bash
docker compose ps
docker compose logs -f auth-service
```

> **Boot order is not instant.** `docker compose restart` does **not** re-apply
> `depends_on`, so for the first minute or two after a restart the gateway can
> answer `500` for services that have not finished starting. That is a race, not
> a failure — give it two minutes before investigating.

## 2. Create your workspace

Open **http://localhost:5173** and choose **Get Started**.

Registration creates a **brand-new tenant** with you as its `ADMIN`. There is no
way to register into somebody else's workspace: the `X-Tenant-ID` header a
client sends is ignored at sign-up, precisely so that a stranger cannot make
themselves an admin of your tenant.

You will be asked for a verification code. Locally there is no Resend key, so
the code is printed to the auth-service console:

```bash
docker compose logs auth-service | grep "DEV ONLY"
# [DEV ONLY] OTP for you@example.com = 481920
```

Codes expire after five minutes; `Resend` issues a new one.

Selecting a plan starts a **14-day trial immediately**. No card is charged —
billing is stubbed in this build (see [Core Concepts](core-concepts.md)).

## 3. Create a project

A **project** is the unit everything else belongs to: jobs, workflows, agents,
runs, schedules, webhooks and compliance reports are all scoped to one. Most
teams use one project per environment or per customer.

From the console: **Projects → New project**. Or over the API:

```bash
curl -X POST http://localhost:8080/api/projects \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"name":"Production","description":"Live estate"}'
```

## 4. Run something

Create a **job** — an ordered list of steps — in your new project, with one
`command` step:

```json
{
  "name": "Disk check",
  "steps": [
    { "type": "command", "label": "Free space", "value": "df -h" }
  ]
}
```

Press **Run**. The trigger returns `202 Accepted` straight away; execution is
asynchronous. The run screen polls `GET /api/runs/{id}` and shows the step
counter and the captured output as it arrives.

A finished run looks like this:

```
[step 1/1] Free space
Filesystem      Size  Used Avail Use% Mounted on
overlay          59G   21G   36G  37% /
SUCCEEDED in 412ms
```

If the output says the step was simulated, `EXECUTION_MODE` is not `remote` —
see [Installation & Setup](installation-and-setup.md#execution-mode).

## 5. Where to go next

- [Core Concepts](core-concepts.md) — projects, jobs, workflows, agents, runs,
  and which of them you author yourself.
- [First Workflow](first-workflow.md) — build a graph that decides, then acts.
- [Connecting a cloud account](../integrations/aws-and-gcp.md) — before a step
  can touch AWS, GCP or Azure, the tenant needs a verified integration.

## If something does not work

| Symptom | Cause |
|---|---|
| Console loads, every API call 500s | Services still booting — wait 2 minutes |
| `401` on everything after an auth-service restart | In dev the RSA key pair is ephemeral; every existing token dies on restart. Sign in again |
| No verification code arrives | Expected locally. Read it from the auth-service log |
| Steps succeed suspiciously fast, output is fake | `EXECUTION_MODE=simulated` |
| A mutation returns `403 no_subscription` | The trial was never started, or it expired. Reads are never gated; only *doing new things* needs a live subscription |
