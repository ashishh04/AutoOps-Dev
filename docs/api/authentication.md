# API Authentication

Getting a token, using it, refreshing it, and the three unauthenticated
endpoints.

For how identity works underneath — key rotation, SSO, revocation semantics —
see [Authentication (OIDC)](../security/authentication-oidc.md).

## Get a token

```
curl -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"you@example.com","password":"..."}'
```

```json
{
  "accessToken": "eyJraWQiOi...",
  "refreshToken": "8f21c3de.9a7b...",
  "expiresIn": 900
}
```

Or the email-code flow, which needs no password at all:

```
POST /api/auth/otp/generate   {"email":"you@example.com"}
POST /api/auth/otp/verify     {"email":"you@example.com","code":"481920"}
```

Both `otp/generate` and `password/forgot` return a **neutral response** whether
or not the address is registered. Neither confirms an account exists.

> Locally there is no Resend key, so codes print to the auth-service console:
> `docker compose logs auth-service | grep "DEV ONLY"`.

## Use it

```
curl http://localhost:8080/api/projects \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

That is the whole contract. Specifically:

- **Do not send `X-Tenant-ID`.** The gateway overwrites it with the token's own
  `tenantId` claim before proxying. Sending one is not an error; it is simply
  ignored, which is the point.
- **Do not send a refresh token as a bearer.** The gateway requires
  `tokenType=access` and rejects refresh tokens outright.
- **Do not cache authorization decisions.** Role and entitlement are evaluated
  per request, and an entitlement can change mid-session.

## Refresh

Access tokens live **15 minutes**. Refresh tokens live 30 days and **rotate on
every use**.

```
POST /api/auth/refresh    {"refreshToken":"8f21c3de.9a7b..."}
```

The response contains a new pair. **Discard the old refresh token immediately.**

> **Reuse detection revokes the whole session family.** Presenting a refresh
> token that has already been rotated kills every token descended from it. That
> is the intended defence against a stolen token, and it is also the most common
> way a client with two parallel refresh paths breaks itself.
>
> Serialize refreshing. The console does this with a single-flight guard: one
> refresh in flight, every other pending request awaits the same promise. Do the
> same in any client you build.

Concurrent rotations on the server are serialized with `SELECT … FOR UPDATE`, so
a genuine race is ordered rather than corrupted — but a client that fires two
refreshes still loses the second one.

## Ending a session

```
POST /api/auth/logout       {"refreshToken":"..."}   revokes that session, idempotent
POST /api/auth/logout-all                            bearer; revokes everything
```

`logout-all` bumps the account's token version, so every outstanding access
token dies at the next authorization check rather than at its own expiry. A
password reset or change does the same thing — which is what evicts a hijacker
the moment the owner resets.

## Unauthenticated endpoints

Three surfaces take no bearer token, each for a specific reason:

| Endpoint | Credential | Why |
|---|---|---|
| `POST /api/hooks/{token}` | The path token | A CI system has no AutoOps login |
| `POST /api/alerts/ingest/{providerType}` | Signed `X-API-KEY` header | Datadog has no AutoOps login. A header, not a path, so it stays out of proxy logs |
| `GET /api/plans` | None | The pricing page is public |

The auth flows themselves (`/api/auth/**`, `/oauth2/**`) are anonymous by
necessity, and `/api/voice/**` is anonymous because a landing-page visitor has
no account — which is why the voice service rate-limits itself.

## Building a service client

There is no separate API-key system. A programmatic caller uses the same tokens
a person does, so:

1. Create a dedicated user in your workspace with the narrowest role that
   works — `VIEWER` if it only reads. Viewer is enforced once, at the edge of
   core-service, so a read-only client cannot mutate anything even through a bug.
2. Sign in and hold the refresh token as the long-lived credential.
3. Refresh on `401`, serialized, once.
4. Treat `403` with a code as terminal for that operation, not something to
   retry — the subscription gate and quota limits do not clear on retry.
5. Back off on `429`; see [Rate Limits](rate-limits.md).

`API_ACCESS` is a plan feature from `TEAM` upward.

## Tokens in the browser

The console stores tokens in `localStorage`, which makes them
XSS-exfiltratable. It is a known, documented trade-off; mitigate with a strict
CSP, and the planned fix is moving the refresh token to an httpOnly cookie.

## Introspection

`POST /oauth2/introspect` (RFC 7662) exists, and **only knows SAS-issued
tokens** — the gateway's own client-credentials tokens, not user tokens.

Revocation-aware validation of a user token goes through
`POST /api/auth/authorize`, which checks the live token version and account
status and performs the entitlement check. Introspection that silently ignored
the version claim would report a revoked token as active, which is worse than
not offering it.

## Related

- [Authentication (OIDC)](../security/authentication-oidc.md)
- [Role-based Access](../security/role-based-access.md)
- [REST API](rest-api.md)
