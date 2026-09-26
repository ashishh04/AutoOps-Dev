# Execution Engine

What happens between pressing **Run** and reading the output.

## Two halves, on purpose

AutoOps splits orchestration from execution, and the split is the whole
security story.

| | core-service (`:8083`) | The step runtime |
|---|---|---|
| Owns | Runs, history, the scheduler, quotas, approvals, the audit trail | Executing one step |
| Reachable from | The gateway | **Nothing outside the platform network** |
| Holds | Encrypted cloud credentials | Nothing. Credentials arrive per call and are never persisted |

The step runtime runs arbitrary commands by design. So it publishes **no
gateway route**, and requires a shared `X-Internal-Token` on every
`/internal/**` call — a missing, wrong, truncated or empty token is rejected
before any controller sees the request. That token guards execution for every
tenant on the platform, so it is the one secret most worth setting properly.

## The run lifecycle

```
QUEUED ──► RUNNING ──┬──► SUCCEEDED
                     ├──► FAILED
                     └──► CANCELED
```

One `runs` row per execution of a job (its `steps[]`) or a workflow (its
`nodes[]`). Triggering returns `202 Accepted` immediately; execution proceeds
asynchronously on a bounded pool (`EXECUTION_POOL_SIZE`, default 4).

**The target's name and definition are snapshotted onto the run.** There is no
foreign key back to the job or workflow, and that is deliberate: history has to
stay truthful after the target is edited or deleted. A run that said
`SUCCEEDED` last March must still show what actually executed last March.

## The step seam

`StepExecutor` is one interface with three implementations, selected by
`EXECUTION_MODE`. Switching runtime is one variable and a restart.

| Mode | Steps go to | Status |
|---|---|---|
| `rundeck` | rundeck-service, onto the Rundeck engine AutoOps operates itself | **Compose default** |
| `remote` | job-service, the in-house runtime | The rollback path |
| `simulated` | Nowhere — sleeps 300–1500 ms and succeeds | Bare-metal dev default |

`simulated` is not a dry run. It reports success for work that never happened,
which is fine on a laptop and wrong anywhere a customer can see it. A step value
of `"simulate":"fail"` fails deterministically, as a demo and test hook.

**One step at a time, synchronously — in both real modes.** The engine could
orchestrate a whole job itself, and that would move orchestration out of
AutoOps, taking the approval gate, per-step retries, `continueOnError` and
cancel-between-steps with it. Those are the product. The engine is the hands;
AutoOps stays the brain, which is exactly what the `StepExecutor` seam already
assumed.

## The Rundeck engine (`EXECUTION_MODE=rundeck`)

One Rundeck instance, operated by AutoOps, runs every step for every tenant —
and customers never see it.

**White-label by construction, not by policy.** Its URL and admin token come
from environment variables, never a database row. rundeck-service exposes **no
`/api/**` surface at all** and the gateway routes nothing to it; its only caller
is core-service over `X-Internal-Token`. The engine container publishes no host
port. Nothing tenant-facing renders the word Rundeck. The per-tenant connection
screens from the first iteration were **deleted, not hidden** — a hidden
endpoint behind the gateway is still an endpoint.

**The isolation boundary is one Rundeck project per AutoOps project:**

```
autoops-{sanitized tenantId}-{projectId}
```

Held three ways: the name is **computed, never accepted** from a request; the
tenant id is sanitized to `[a-z0-9-]` and length-bounded, so a hostile workspace
name cannot smuggle a path segment or an ACL glob into it; and a unique key on
the Rundeck name means even a careless change to the naming function fails
loudly rather than collapsing two projects onto one. Provisioning is lazy and
idempotent — a 409 means the project exists, which is the desired state.

Two behaviours were preserved from job-service deliberately, and both were found
by running against real job definitions rather than by review:

- **No `set -euo pipefail` on a customer's body.** The obvious hardening is
  wrong here: the automation library pipes into `head` and `grep -q`, which
  close the pipe early and SIGPIPE the upstream command (exit 141). Under
  `pipefail` that is a failed step for a script that has worked for years. Only
  sequences the service itself generates turn on `set -e`.
- **An empty step body fails.** Without the guard the translator emits a script
  of nothing, exits 0, and the run log reads `ok` for a job that did nothing.

**Timeouts abort rather than abandon.** A step past `RUNDECK_STEP_TIMEOUT`
(default 10 m) has its engine execution aborted — the failure job-service's
process-tree kill existed to prevent.

> **A named regression, mitigated rather than solved.** Credentials are `export`
> lines at the top of the generated script. job-service passed them as process
> environment and wrote them nowhere; Rundeck's ad-hoc endpoint has no
> equivalent — secure options exist only for saved jobs, and Key Storage would
> copy the vault into the engine. Tracing is disabled before the exports, values
> are single-quote escaped, and the uploaded script is deleted after the run.
> **Do not run the engine at `loglevel=DEBUG`.**

Known gaps in this mode: `powershell` steps are **refused** at the boundary
(`pwsh` on Linux lacks the Windows-only cmdlets the library is written against,
so installing it would mean failing deep inside someone's script instead of
clearly at the edge — the fix is a Windows execution node); `notify` and
`approval` step types are unimplemented, and always were, in either runtime;
cloud credential verification still lives in job-service, so job-service cannot
be deleted yet; and the engine is single-instance on a file database.

## job-service (`EXECUTION_MODE=remote`)

The in-house runtime, and the rollback path. Everything below describes this
mode.

## Inside job-service: one step, one OS user

PID 1 runs as root for exactly one reason — to drop privileges. Every step is
exec'd through `su-exec` as a throwaway `autoops-stepN` user that owns nothing
in the image.

- **Isolation is a kernel property, not a file mode.** Two concurrent steps hold
  different uids, so one tenant's step cannot read another's decrypted
  kubeconfig, service-account key or `/proc/<pid>/environ`. A file mode would be
  changeable by the same uid; a different uid is refused by the kernel.
- **If the step-user pool is unavailable, steps are refused.** Tenant code is
  never run as root as a fallback.
- **No inherited environment.** A step sees `PATH`, `HOME`, `LANG`, `TZ`,
  `TMPDIR` and whatever its own runner sets. `JOB_INTERNAL_TOKEN` and everything
  else in the container's environment is stripped before the child starts — it
  used to be readable with a one-line `env` step. Site-wide extras go in
  `STEP_ENV_PASSTHROUGH`, and never a secret.
- **Private workspace.** Each step gets its own directory with `HOME` and
  `TMPDIR` pointing into it. It is deleted when the step ends, and anything left
  running under that user is killed by owner.
- **Bounded.** A per-step wall-clock timeout (default 60 s, hard cap 10 m)
  force-kills the whole process **tree**, so a backgrounded child cannot outlive
  its step. Captured output is capped at 16 KB (merged stdout+stderr). At most
  `STEP_SANDBOX_USERS` (8) steps run at once; beyond that a step waits
  `STEP_SLOT_WAIT` and then fails honestly rather than piling up.

The container needs an **init process**. A timed-out step's grandchildren are
reparented to PID 1, and a JVM does not reap processes it never spawned —
without an init they accumulate as zombies until the container runs out of PIDs.

The image itself is reproducible: digest-pinned bases, version-pinned apk
packages, hash-locked pip requirements and a fixed build timestamp, so the same
source always produces the same image digest.

## How a step gets credentials

Credentials live AES-256-GCM-encrypted in core-service under `CLOUD_CRED_KEY`.
They are decrypted **for one call** and shipped with the execute request over
the internal network. job-service persists nothing; scratch files are deleted
after each step.

Resolution is explicit: core-service uses the step's optional `connection` name,
otherwise the tenant's single matching integration. **Ambiguity is an error** —
a step that could have meant either of two AWS accounts fails with a clear
message instead of running blind against one of them.

## The library step

A step that references a catalog script carries a `scriptPath`, and is written
**at that path inside the step's workspace** with the shared library tree beside
it:

```
<workspace>/
  Scripts/<Category>/<Title>.ps1      the step's body, byte-for-byte as authored
  Modules/IT-Automation-Common.psm1   copied from /opt/autoops/library
  Config/config.sample.json
```

The tree is **copied per step, never mounted**. A shared module directory would
be a channel between two tenants' steps, and a writable one would let a step
rewrite what the next step imports.

The script body is never modified. A `param()` block and its `[CmdletBinding()]`
attributes must be the first statement in a PowerShell file, so prepending setup
fails at *parse* time — a separate `autoops-run.ps1` launcher invokes the script
instead and carries its arguments. Arguments are tokenized and passed as an argv
array, so there is no shell to inject into.

## Metrics

| Metric | Counts |
|---|---|
| `core_runs_total{status,trigger}` | Terminal runs |
| `core_gate_checks_total` | Every subscription-gate decision |
| `job_steps_total{type,outcome}` | Every step executed |
| `job_credential_verifications_total{platform,outcome}` | Every credential check |

Prometheus scrapes `/actuator/prometheus` on each service (bearer-protected).

## Where run time actually goes

Measured, not assumed: an agent run is roughly **two-thirds model time**. The
orchestration layer is not the bottleneck, and tuning `EXECUTION_POOL_SIZE` will
not change it.

## Related

- [State Management](state-management.md) — what is stored where, and what is not
  stored at all.
- [Handling Failures](../workflows/handling-failures.md) — retries,
  `continueOnError`, timeouts, cancellation.
- [High Availability](high-availability.md) — what is safe to run twice.
