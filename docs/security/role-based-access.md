# Role-based Access

Who can do what, where each rule is enforced, and the one place the console and
the backend do not yet agree.

## The layers

Authorization in AutoOps is four independent checks, and a request has to pass
all of them:

| Layer | Question | Enforced by |
|---|---|---|
| Authentication | Is this a valid, unexpired access token? | api-gateway, locally against JWKS |
| Tenancy | Which workspace? | The `tenantId` claim, written into `X-Tenant-ID` by the gateway |
| Role | May this role do this? | Each service, on its own routes |
| Entitlement | Does the plan allow it? | subscription-service, consulted live |

The gateway **makes no business decisions**. It authenticates and it pins the
tenant; everything finer lives in the service that owns the data.

## Roles in the token

The `role` claim is one of:

| Role | Meaning |
|---|---|
| `PROVIDER` | The operator of the platform. Sees every tenant's usage, audit and the alert plane |
| `ADMIN` | Workspace administrator |
| `CLIENT` | Ordinary workspace member |
| `VIEWER` | Read-only member |

**`VIEWER` is enforced at the edge of core-service, once.** Every role except
`VIEWER` may change state; a viewer's mutating request is refused before it
reaches a controller, so there is no per-controller rule to forget.

## The console's capability matrix

The console has a finer-grained model than the token does — `admin`, `operator`
and `viewer` personas mapped onto a capability table:

| Capability | admin | operator | viewer |
|---|---|---|---|
| `manageMembers` | ✅ | — | — |
| `manageBilling` | ✅ | — | — |
| `manageGovernance` | ✅ | — | — |
| `manageKeys` | ✅ | — | — |
| `authorScript` | ✅ | ✅ | — |
| `runWorkflow` | ✅ | ✅ | — |
| `deploy` | ✅ | ✅ | — |
| `approve` | ✅ | — | — |
| `viewAudit` | ✅ | — | — |
| `manageProject` | ✅ | — | — |
| **`authorAutomation`** | **—** | **—** | **—** |

Route guards use this too: a signed-in client only reaches the console for their
own persona, and a disallowed role is redirected to theirs.

> **Known gap, stated plainly.** `OPERATOR` exists only in the console. The
> backend's user role enum is `PROVIDER | CLIENT | ADMIN | VIEWER`, so an
> operator persona is carried client-side and maps onto an ordinary member
> server-side. Where operator and client differ — notably `approve`, which the
> backend restricts to admins — the backend is the one that decides. Do not
> treat the console matrix as a security boundary; treat it as the UI's view of
> one.

## `authorAutomation` is false for everyone

Including admin. This is not an oversight and not a tier to be unlocked.

Workflows and agents are **designed by the provider** and rolled out to
customers as sealed copies. A customer builds jobs and scripts, and runs what it
has been given. The backend enforces the same rule, so flipping the flag in the
console would only produce 403s.

A corollary: components a delivered agent depends on are **hidden**. A workflow
shipped only because an agent needs it is sealed, unlisted, and not runnable by
the customer directly — otherwise the library would fill with machinery nobody
asked for and anybody could fire.

## Approvals are admin-only

Operators request; only admins sign off, and the backend enforces it. See
[Approval Gates](../workflows/approval-gates.md).

## Tenancy is not a role

It is worth separating these, because they fail differently.

Every business row carries a `tenant_id` taken from the token claim. A request
for another tenant's resource does not get `403` — it gets **`404`**. A 403
would confirm that the id exists, which is exactly the probe the platform is
trying to refuse. The same reasoning covers an alert outside your scope and a
user id in another tenant.

Provider is the deliberate exception: it is the operator role, it already reads
every tenant's usage and audit, and the alert plane is infrastructure it runs.

## Entitlements

Role says *may you*; the plan says *may this workspace*. Both have to say yes.

- **Reads are never gated.** A tenant can always see and export its own data.
- **Mutations fail closed.** An unreachable subscription-service returns
  `503 entitlement_unavailable`, not an allow.
- Feature-gated capabilities include `GOVERNANCE` and `COMPLIANCE_REPORTS`
  (Business and up), `ADVANCED_RBAC` (Business), `SSO` and `PRIVATE_TEMPLATES`
  (Enterprise), `AUDIT_LOG` and `API_ACCESS` (Team and up).

Denials are `403` with the reason as the `error` code, so the console can show
the right renew-or-upgrade prompt rather than a generic failure.

## Service-to-service

Internal calls do not carry a user role. `/internal/**` on each service requires
a shared `X-Internal-Token` — one secret per callee — and those paths are never
routed through the gateway. job-service publishes no host port at all.

Where a plan gate must still apply to an internal call, the **user's own token
rides along** in `X-Access-Token`. SCM import works this way: the operation is
internal, but the entitlement is still the requesting user's.

## Provider accounts

No API mints a `PROVIDER`. The role exists in the enum and is granted directly
in the database — deliberately, because an endpoint that creates platform
operators is an endpoint worth attacking.

## Related

- [Authentication (OIDC)](authentication-oidc.md)
- [Approval Gates](../workflows/approval-gates.md)
- [Audit Logs](audit-logs.md)
