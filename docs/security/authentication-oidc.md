# Authentication (OIDC)

How a person proves who they are, what a token contains, and how it is revoked.

## One authority

auth-service is the **only holder of the RS256 private key**. No shared secret
for token signing exists anywhere in the platform. Every other service validates
tokens locally against the published JWKS:

```
GET /oauth2/jwks
```

The gateway never calls auth-service per request. That is what keeps the edge
fast and keeps auth-service off the critical path of every API call.

## Four ways in

| Method | Endpoint | Notes |
|---|---|---|
| Password | `POST /api/auth/login` | Rate-limited per account and per IP |
| Email code (OTP) | `POST /api/auth/otp/generate` → `/otp/verify` | Accounts may be OTP-only, with no password at all |
| Forgot password | `POST /api/auth/password/forgot` → `/password/reset` | Revokes every session on success |
| SSO (OIDC via Keycloak) | `GET /api/auth/sso/initiate` → `/sso/callback` | State + PKCE S256, single-use, held in Redis |

Registration (`POST /api/auth/register`) creates a **PENDING admin in a fresh
tenant** and emails a verification code. It returns `202` and no tokens — an
account is not usable until the address is confirmed.

Any `X-Tenant-ID` a client sends at sign-up is **ignored**. Honouring it would
be a way to make yourself an admin of somebody else's workspace.

## The access token

RS256, 15-minute TTL, `kid` pinned in the JWS header.

```
sub        the email
userId
role       PROVIDER | CLIENT | ADMIN | VIEWER
tenantId   the workspace — the single most important claim
tokenType  access
status
ver        token version, for revocation
iss        autoops-auth-service
iat / exp
```

**`tenantId` is the tenant boundary.** The gateway overwrites the `X-Tenant-ID`
header with this claim before proxying, so a client can never smuggle another
workspace's id past the edge. Downstream services read the claim, never the
header.

Refresh tokens are rejected at the gateway — only `tokenType=access` passes.

## Revocation

Access tokens are short-lived rather than checked per request, so revocation
works through two mechanisms:

- **`ver` (token version).** auth-service checks the claim against the database
  on `POST /api/auth/authorize`. `logout-all`, offboarding, a password reset and
  a password change all bump it — every outstanding token dies instantly.
- **Refresh-token rotation with reuse detection.** A refresh token is
  `{sessionId}.{48-byte secret}` with only the SHA-256 stored. It rotates on
  every refresh, and **presenting an already-used one revokes the whole session
  family**. That revocation is persisted even though the request throws, which
  is exactly the kind of bug the commit-semantics test suite exists to catch.
  Concurrent rotations are serialized with `SELECT … FOR UPDATE`.

A password reset therefore evicts a hijacker the moment the owner resets.

## Hardening that is easy to miss

- **OTPs**: 6 digits from `SecureRandom`, SHA-256 at rest, constant-time
  compare, 5-attempt lockout (persisted), 5-minute TTL, sent after commit.
- **No timing oracle on login**: an unknown email and an OTP-only account both
  burn a dummy BCrypt compare. Failures are a uniform `login_failed` — only a
  caller with the *correct* password learns that an account is unverified.
- **Neutral responses** on OTP generation and forgot-password, so neither
  confirms whether an address is registered.
- **Registration race**: a Redis lock closes the check-then-insert window on a
  globally unique email.
- **Tenant scoping on user management**: onboard and offboard are restricted to
  the caller's own JWT tenant. Another tenant's user id returns `404`, not
  `403` — a 403 would confirm the user exists.
- **Retention sweep**: hourly purge of OTPs (1 d), sessions (30 d) and audit
  (180 d).
- **Metrics**: `auth_events_total{type=…}`. Alert on spikes in `REFRESH_REUSE`
  and `RATE_LIMITED`.

## Key rotation

Every alias in the keystore is published in JWKS; only `JWT_KEYSTORE_ALIAS`
signs, and the `kid` is pinned in each token's header.

To rotate: add a new alias, flip `JWT_KEYSTORE_ALIAS`, and drop the old alias
after the access-token TTL (15 minutes) has elapsed. Nothing needs restarting in
lockstep, because validators fetch by `kid`.

> In the `dev` profile the key pair is **ephemeral** — generated at startup.
> Restarting auth-service invalidates every outstanding token. That is by
> design; production uses a PKCS#12 keystore.

## SSO with Keycloak

Fully implemented, and off unless configured. The beans are lazy, so the
platform boots without Keycloak present.

```
KEYCLOAK_ISSUER_URI=http://localhost:8180/realms/autoops
KEYCLOAK_CLIENT_ID=...
KEYCLOAK_CLIENT_SECRET=...
SSO_SUCCESS_REDIRECT=http://localhost:5173/auth/callback
```

The flow: `/sso/initiate` generates state and a PKCE S256 verifier into Redis
(single-use), Keycloak calls `/sso/callback`, and auth-service issues AutoOps
tokens and redirects to the SPA with them in the **URL fragment** — a fragment
rather than a query string, because fragments are not sent to servers and do not
land in access logs.

SSO is an `ENTERPRISE` plan feature. It needs an `autoops` realm configured to
exercise end to end.

## Machine access

There is no separate API-key system. A programmatic caller authenticates the
same way a person does and carries the same bearer token; `API_ACCESS` is a
plan feature from `TEAM` upward. See [Authentication](../api/authentication.md)
for the request-level detail.

`POST /oauth2/introspect` (RFC 7662) exists but only knows **SAS-issued
tokens** — the gateway's own client-credentials tokens. Revocation-aware checks
for *user* tokens go through `POST /api/auth/authorize`. This is intentional:
introspection that silently ignored the `ver` claim would report a revoked token
as active.

## Known trade-off

Tokens are held in `localStorage` in the console, which makes them
XSS-exfiltratable. Mitigate with a strict CSP; moving the refresh token to an
httpOnly cookie is the planned fix.

## Related

- [Role-based Access](role-based-access.md)
- [Audit Logs](audit-logs.md)
- [API Authentication](../api/authentication.md)
