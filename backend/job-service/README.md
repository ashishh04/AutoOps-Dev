# AutoOps Job Service

The **step execution runtime** (port **8084**, Boot 3.4.2 / Java 21) — the Rundeck-style engine that actually runs job/workflow steps. core-service keeps orchestration (runs, scheduler, history, quotas) and hands each step here over HTTP; this service executes it and returns the captured output.

## Security model (read this first)

This service **executes arbitrary commands by design**. Containment:

- **Internal only** — no host port in compose, no API gateway route. Only other containers on the platform network can reach it.
- **Shared internal token** — every `/internal/**` call must carry `X-Internal-Token` (env `JOB_INTERNAL_TOKEN`, same value in core-service). The prod profile refuses to start with the dev default.
- **One OS user per step** — PID 1 runs as root *for one reason*: to drop privileges. Every step is exec'd through `su-exec` as a throwaway `autoops-stepN` user that owns nothing in the image. Two concurrent steps hold different uids, so one tenant's step cannot read another's decrypted kubeconfig, service-account key, or `/proc/<pid>/environ` — the kernel refuses, rather than a file mode that the same uid could simply change. If the pool is ever unavailable, steps are **refused**; tenant code is never run as root.
- **No inherited environment** — a step sees `PATH`, `HOME`, `LANG`, `TZ`, `TMPDIR` and whatever its own runner sets. `JOB_INTERNAL_TOKEN` and everything else in this container's environment is stripped before the child starts (it used to be readable with a one-line `env` step). Site-wide extras go in `autoops.jobs.env-passthrough` — never a secret.
- **Private workspace** — each step gets its own directory, owned by its step user, with `HOME` and `TMPDIR` pointing into it. It is deleted when the step ends, and anything the step left running under its user is killed by owner.
- **Bounded** — per-step wall-clock timeout (default 60s, hard cap 10m) that force-kills the whole process **tree**, so a backgrounded child cannot outlive the step; capped output capture (16KB, merged stdout+stderr); and at most `sandbox.user-count` (8) steps at a time — beyond that a step waits `lease-timeout` and then fails honestly instead of piling up.

| Setting | Default | Purpose |
|---|---|---|
| `STEP_SANDBOX` | `true` | Per-step OS user. `false` is a dev-box choice; prod refuses to start without it |
| `STEP_SANDBOX_USERS` | `8` | Pool size = concurrency ceiling. Keep core-service's `EXECUTION_POOL_SIZE` at or below it |
| `STEP_SLOT_WAIT` | `30s` | How long a step waits for a free slot |
| `STEP_ENV_PASSTHROUGH` | *(empty)* | Extra env vars steps may inherit, comma-separated (e.g. `HTTPS_PROXY`) |
| `autoops.jobs.sandbox.allow-root-steps` | `false` | Run steps as root when isolation is unavailable instead of refusing them. **Test suites and CI only** — the suite runs in a root container with no step-user pool |

The container also needs an init process (`init: true` in compose, `docker run --init`): a timed-out step's grandchildren are reparented to PID 1, and a JVM does not reap processes it never spawned — without one they accumulate as zombies until the container runs out of PIDs.

## Step types

| Type | What runs | Value format |
|---|---|---|
| `command`, `agent` | One-line shell command in this container (bash) | `echo hello && df -h` |
| `script` | Multi-line shell script (temp file, bash/sh) | full script body |
| `pyscript` | Python script (python3 in the image) | full script body |
| `powershell` (alias `pwsh`) | PowerShell Core script. Runs the cross-platform modules — AWS.Tools, Az, PowerCLI, Microsoft.Graph, SqlServer. Does **not** run Windows-only cmdlets (`Get-ADUser`, the Exchange shell): those need a real Windows host over WinRM, which is a transport, not a missing module. Cloud credentials arrive as env vars, so `Connect-AzAccount` and AWS.Tools authenticate without further setup | full script body |
| `ssh` | Command on a remote host via the system ssh client (BatchMode — key auth only; keys must be mounted at `/home/autoops/.ssh`, which the compose stack does **not** do yet — see Known gaps) | `user@host systemctl restart app` |
| `rest` | HTTP call (2xx/3xx = success; status+body in the log) | `https://api.x.com/health` or `POST https://api.x.com/deploy` + body on next lines |
| `terraform` | Real `init` + `plan\|apply\|destroy` (step's `action`, default apply) in a scratch workspace, via OpenTofu (Terraform-CLI-compatible). Credentials from the tenant's AWS/Azure/GCP integration are injected as provider env vars (`AWS_*`, `ARM_*`, `GOOGLE_*`); provider-free configs run without any | HCL — the `main.tf` content |
| `kubernetes` | Real `kubectl` against the tenant's KUBERNETES integration (its kubeconfig, decrypted per call, written to a 0600 scratch file) | `get pods -A`, or `apply` + manifest on next lines |
| `awslambda` (alias `lambda`) | Real Lambda **Invoke** call, SigV4-signed with the tenant's AWS integration (no SDK). Region from the step's `region`, the ARN, or the integration; the function's CloudWatch log tail lands in the run log. Optional step fields: `invocationType` (`Event` = async), `qualifier` (alias/version), `endpoint` (LocalStack-style override) | `my-function` or a full ARN on line 1, JSON payload on the lines after |
| `azurefn` (alias `azurefunction`) | Real HTTP-trigger call. Method defaults to POST with a body, GET without; the key comes from the AZURE integration's `functionKey` or a `?code=` already in the URL (anonymous functions need neither, and a `?code=` is masked in the log) | `https://app.azurewebsites.net/api/Fn` or `POST <url>` on line 1, JSON body after |
| `test` | Always succeeds, echoes its value | anything |

### Library scripts and the layout they expect

A step that carries a `scriptPath` (core-service sets one when the step is a
reference to a library script — see `LibraryStepResolver`) is not written to a
flat temp file. It is written **at that path inside the step's workspace**, and
the shared library tree is placed beside it:

```
<workspace>/
  Scripts/<Category>/<Title>.ps1     the step's body
  Modules/IT-Automation-Common.psm1  copied from /opt/autoops/library
  Config/config.sample.json
```

This exists because every one of the catalog's 213 PowerShell automations opens
with `Import-Module (Join-Path $PSScriptRoot '..\..\Modules\IT-Automation-Common.psm1')`
— a repository layout that existed on the author's machine and nowhere in this
platform. Written flat, each script failed on its **first statement**: pwsh
installed, runner correct, automation logic never reached.

The layout alone is not sufficient, because on Linux a backslash is an ordinary
filename character rather than a separator, so that `Join-Path` produces one
absurd filename however the directories are arranged. `ScriptLayout.modulePreamble()`
handles that in the session instead of rewriting the script text: it wraps
`Import-Module` so a path that fails is retried by the module's base name,
resolved out of the workspace's own `Modules/`. A module that genuinely does not
exist still fails — the fallback is a retry, not a swallow.

The script itself is written **byte-for-byte as authored** and a separate
`autoops-run.ps1` launcher invokes it. Setup cannot be prepended to the body: a
`param()` block and its `[CmdletBinding()]` / `[OutputType()]` attributes must be
the first statement in a PowerShell file, so anything above them fails at PARSE
time with `Unexpected attribute 'OutputType'`. 119 of the catalog's 213 scripts
are `param()` scripts, so this is most of the library, not an edge case.

The launcher also carries the step's **arguments**. Those scripts take
parameters and `-NonInteractive` turns a missing mandatory one into an immediate
failure rather than a prompt — a step that could not pass arguments could not run
a single catalog script. A step's `args` string is tokenized by `ArgumentLine`
and appended after `-File <path>`; `ProcessSupport` execs an argv array, so there
is no shell to inject into.

The tree is **copied per step, never mounted**. A shared module directory would
be a channel between two tenants' steps, and a writable one would let a step
rewrite what the next step imports.

`AUTOOPS_DATA_ROOT` points the module's logs, approval artifacts and reports at
the step's own workspace, so they die with the step. Its Windows default
(`%ProgramData%`) is both absent here and shared — wrong twice.

**Not provisioned: a populated `Config/config.json`.** `Get-AutomationConfig`
throws without one, and roughly 116 of the catalog's scripts treat that as
fatal (the other 93 catch it and continue, because they only read). Shipping a
placeholder would make those scripts run against invented SMTP relays and ITSM
endpoints, which is worse than a clear refusal. Generating it per run from the
tenant's real notification channels and connections is the next piece of work.

**Credential flow**: credentials live AES-GCM-encrypted in core-service (`CLOUD_CRED_KEY`); core-service resolves the step's integration (step's optional `connection` name, else the tenant's single match — ambiguity is an error), decrypts, and sends the bundle with the execute call over the internal network. Nothing is persisted here; scratch files are deleted after each step.

Non-zero exit code / HTTP ≥ 400 / timeout ⇒ the step fails and the run stops there, with the output captured up to that point.

## API

Both endpoints sit behind `InternalTokenFilter` — anything under `/internal/**` without a matching `X-Internal-Token` gets a 401 and never reaches a controller. `/actuator/**` is deliberately outside the guard so compose health checks and Prometheus keep working.

`POST /internal/execute` `{tenantId, stepType, label, value, raw, timeoutSeconds, credentials}` → `{success, output, error, exitCode, durationMs, executor}`. One call = one step, synchronous.

`POST /internal/verify` `{tenantId, platform, data}` → `{supported, verified, message, accountId, accountName, details}`. Checks a stored cloud integration against the **real** provider with its cheapest read-only "who am I" call — nothing is ever mutated:

| Platform | The check | What comes back |
|---|---|---|
| `AWS` | STS `GetCallerIdentity` (signed for the integration's own region — the global endpoint refuses opt-in regions) | identity, account number, ARN, user id, region |
| `AZURE` | Entra ID client-credentials grant, then the ARM subscription lookup | subscription name + id; when ARM says 404/403 it asks which subscriptions the app *can* see, so "wrong id" and "no role assigned" are told apart |
| `M365` | Entra ID grant for Graph, then `/v1.0/organization` | organization, tenant id, country, client id |
| `GCP` | Service-account JWT-bearer token grant, then the Resource Manager project lookup | project name/id/number, state, service account |
| `KUBERNETES` | `kubectl get --raw /version` with the kubeconfig in a 0600 scratch file | context, API server, version, platform |

Platforms with no live check report `supported=false` rather than pretending. core-service calls this from `VerificationClient`; the decrypted secret never crosses the gateway.

`job_steps_total{type,outcome}` and `job_credential_verifications_total{platform,outcome}` count every execution and every verification (Prometheus at `/actuator/prometheus`).

## Wiring (core-service side)

- `EXECUTION_MODE=remote` selects `JobServiceStepExecutor` (compose default); unset = `simulated` (no real execution, e.g. bare `mvn spring-boot:run` dev).
- `JOB_SERVICE_URL` (default `http://localhost:8084`), `JOB_INTERNAL_TOKEN` shared secret, `EXECUTION_STEP_TIMEOUT` per-step budget.
- `VerificationClient` calls `/internal/verify` with the same token whenever a tenant saves or re-checks a cloud integration.

## Known gaps

- **`ssh` steps have no key material.** The runner is complete, but no key is mounted and there is no SSH credential type in core-service (unlike AWS/Azure/GCP/KUBERNETES), so an `ssh` step fails with exit 255. Note that the sandbox makes a mounted key harder, not easier: each step has its own `HOME` and its own uid, so a key at `/home/autoops/.ssh` is neither found nor readable. The fix is an SSH credential type that arrives in the credential bundle like every other platform, written into the step's own workspace — not a mount.
- **The script library has no per-tenant configuration.** `Config/config.json` is
  not generated, so the ~116 catalog scripts that require `Get-AutomationConfig`
  fail with the module's own "copy config.sample.json and populate it" message.
  The data the file needs (SMTP relay, Teams webhook, ITSM endpoint, protected
  object lists) already exists in core-service as notification channels,
  secrets and governance policies; wiring those into a per-run config file is
  the remaining step. A placeholder file is deliberately NOT shipped.
- **No cloud PowerShell modules are installed.** The image ships `pwsh` but not
  `Az.*`, `Microsoft.Graph.*`, `AWS.Tools.*`, `VMware.PowerCLI` or
  `ExchangeOnlineManagement`, and `#Requires -Modules X` fails a script BEFORE
  its first statement. Measured against the catalog: **35** scripts need no
  module and run today, **154** need modules that are installable on Linux, and
  **24** need Windows-only ones. Installing the first group is the single
  highest-leverage change left — `Az.Accounts` (49 scripts),
  `Microsoft.Graph.Authentication` (31), `ExchangeOnlineManagement` (25),
  `AWS.Tools.Common` (22), `Posh-SSH` (18) and `Az.Compute` (17) cover most of it.
- **A multi-line `script` (bash) step reports the exit code of its LAST command.**
  So a step whose first command fails and whose last succeeds is recorded as
  SUCCEEDED. That is ordinary shell behaviour, but it is the exact failure mode
  `PowerShellRunner` goes out of its way to prevent for pwsh, and the same
  reasoning was never applied here. Prepending `set -euo pipefail` would fix it
  and would change the behaviour of existing customer scripts, so it is called
  out rather than done silently.
- **Windows-only PowerShell still cannot run.** `Get-ADUser`, the Exchange
  management shell and most Windows Server cmdlets need a Windows host over
  WinRM. That is the same missing transport as `ssh` above, and it bounds which
  slice of the catalog is reachable today.
- **Steps have no CPU or memory ceiling.** The compose entry sets no `cpus`/`mem_limit`, so one runaway step can starve the container. Concurrency itself is now bounded by the step-user pool.
- **The dev token is the default.** Compose falls back to `dev-internal-token` and nothing sets `SPRING_PROFILES_ACTIVE=prod`, so `ProdSafetyGuard` never fires locally. Set a real `JOB_INTERNAL_TOKEN` (and the prod profile) anywhere this is more than a laptop.

## Run

```
mvn test             # 84 hermetic tests — no network, no Spring context needed
mvn spring-boot:run  # local dev on :8084 (sandbox inactive: steps share your user)
```

Coverage: real process execution against the local OS (bash/sh in the container, cmd.exe on a Windows dev box); the cloud-function runners and every credential verifier against a loopback HTTP stub; the internal-token filter (missing / wrong / prefix / empty token, guarded vs. actuator paths, and both controllers proven unreachable without the token); environment scrubbing (a real child process cannot read a variable this JVM holds); process-tree kill on timeout; kubectl argument splitting; and workspace lifecycle.

One test is POSIX-only (the backgrounded-grandchild kill) and skips on Windows. To run everything, run the suite in a Linux container:

```
docker run --rm -v "$PWD:/src" -w /src maven:3.9-eclipse-temurin-21 mvn -B test
```

### Verifying the sandbox for real

Unit tests cannot prove OS-level isolation — that needs the image. Against a running container:

```
docker run -d --name jobsbx --init -e JOB_INTERNAL_TOKEN=probe -p 18084:8084 <image>
# 1. each step gets its own throwaway user:      value: "id"
# 2. no platform secrets are visible:            value: "env | grep -c TOKEN"   -> 0
# 3. a concurrent step cannot read another's:    value: "cat /tmp/autoops-step-*/home/*"
#                                                -> Permission denied
# 4. no path back to root:                       value: "/sbin/su-exec root id"
#                                                -> setgroups: Operation not permitted
```

## Reproducible builds — a plain-English guide

This service ships as a **Docker image** (a sealed box containing the app plus every tool it needs — Java, Python, kubectl, terraform, and so on). We build that box in a **reproducible** way. This section explains what that means and how to do it, no prior knowledge assumed.

### What "reproducible" means (the cake analogy)

Imagine a recipe so exact that **any** kitchen, on **any** day, bakes a cake that is identical down to the last crumb. Weigh two of those cakes and they match perfectly. If one ever comes out different, you know for certain that *something* changed — a different ingredient, a substituted brand, someone tampering.

Our Docker image works the same way. Every finished image has a **digest** — a long fingerprint that looks like this:

```
sha256:44cc25e1bae57c00988d49828d12c666c7539fd25eefc9537c324e6f987f87e0
```

Because the build is reproducible, **the same source code always produces the same fingerprint**. Build it today, build it next year on a different computer — same fingerprint.

### Why anyone should care

- **Trust & security.** You can *prove* the image running in production was built from exactly this source code and nothing was secretly slipped in. If the fingerprint doesn't match, don't trust it.
- **No "it works on my machine".** Everyone — every developer, the CI server, production — gets a byte-for-byte identical image.
- **Audits & compliance.** "Show me this is the exact software you claim" becomes a one-line check.

### What we locked down (the "ingredient list")

A normal build quietly lets small things drift — the current time, "whatever version was latest today", the order files happen to land in. Any of those changes the fingerprint. We pinned every one of them:

| Ingredient | How it's pinned | Where |
|---|---|---|
| The two base boxes we build on (Maven, Java) | By exact fingerprint, not a floating label | `Dockerfile` (`FROM … @sha256:…`) |
| OS tools (bash, python, curl, ssh, kubectl, opentofu) | Exact version numbers | `Dockerfile` (`apk add … =version`) |
| Python libraries (requests, boto3 + everything they pull in) | Exact versions **and** fingerprints | `requirements.txt` |
| The Java app file (the `.jar`) | A fixed date instead of "now" | `pom.xml` (`project.build.outputTimestamp`) |
| The user accounts | The "password last changed" day, which Alpine sets to **today**, is rewritten to the fixed date | `Dockerfile` (the `sed` on `/etc/shadow`) |
| The final packaging step | A fixed date + stripped of build-time metadata | `build-reproducible.sh` |

The single fixed date we use everywhere is **1 January 2025** (in computer form, the number `1735689600`, or day `20089` counting from 1970). It appears in `pom.xml`, `build-reproducible.sh` and the `/etc/shadow` fix in the `Dockerfile` — **if you ever change one, change the others to match.**

> The `/etc/shadow` line is easy to miss and worth understanding: creating a user records the day the account's password last changed. That is the build date, so yesterday's build and today's build of *identical source* produced different images. The `sed` pins it.

### How to build the image

One command, from inside the `job-service` folder:

```
./build-reproducible.sh
```

It prints a line like `image digest: sha256:9191…94e0`. That fingerprint is the whole point.

To also upload (push) the image to a registry so servers can pull it:

```
./build-reproducible.sh myregistry.example.com/job-service:1.0 --push
```

> **Important:** use the script, **not** a plain `docker build`. A plain build stamps the image with the current time and adds extra metadata, so its fingerprint changes on every run — it is *not* reproducible. The script exists precisely to switch all of that off. (It needs Docker with `buildx`, which ships with modern Docker Desktop; the first run sets up a one-time helper and takes a little longer.)

### How to prove it's really reproducible

Run the build **twice** and compare the two `image digest:` lines. They must be identical:

```
./build-reproducible.sh   # note the digest it prints
./build-reproducible.sh   # note the digest again — same as the first
```

Same source in → same fingerprint out. We verified this on the current source: two builds with the BuildKit cache pruned in between (so the second genuinely re-ran apk, pip and Maven) both produced `sha256:44cc25e1bae57c00988d49828d12c666c7539fd25eefc9537c324e6f987f87e0`.

The digest is expected to change whenever the recipe does — a new apk pin, a new dependency, a source change. What must never happen is the *same* source producing two different digests. (It did, until the `/etc/shadow` date was pinned: builds on different days diverged.)

### The honest fine print

- Reproducibility holds **for a given set of build tools**. A future major version of Docker's builder could, in principle, package things slightly differently and shift the fingerprint. That's a property of the tool, not our recipe; pin the builder version in CI if you need to rule it out.
- To refresh a pinned ingredient later (e.g. a security update to a base box or a library), update its pin in the file shown above and re-run the build — you'll get a new, and equally reproducible, fingerprint.
