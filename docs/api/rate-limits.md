# Rate Limits

What is limited, how the buckets are keyed, and what to do with a `429`.

## The gateway limit

| | |
|---|---|
| Default | **600 requests per minute, per tenant** |
| Settings | `RATE_LIMIT_ENABLED`, `RATE_LIMIT_REQUESTS`, `RATE_LIMIT_WINDOW` |
| Store | Redis, shared across gateway replicas |
| Response | `429` with `{"error":"rate_limited", ...}` |

The default is generous on purpose. **A limiter that trips on normal use gets
switched off by whoever is on call, and then there is no limiter at all.**

Turning it off with `RATE_LIMIT_ENABLED=false` leaves the filter unregistered
entirely, so there is no Redis round trip per request.

## Headers

Every response through the limiter carries:

```
X-RateLimit-Limit:     600
X-RateLimit-Remaining: 412
```

And on a `429`:

```
Retry-After: 60
```

`Retry-After` is what makes a `429` actionable rather than a mystery. A client
that is not told when to come back either gives up or retries immediately, and
the second makes the problem worse.

## What a bucket is keyed on

In order: **the tenant**, falling back to the authenticated subject, falling
back to the peer address.

The tenant is the unit the product is sold in, so it is the unit the budget
belongs to. Keying on the *user* would let one customer with fifty seats take
fifty times the budget; keying only on IP would put a whole office behind one
NAT into a single bucket.

Unauthenticated traffic — webhook fires and alert ingest — falls back to the
peer address. **Deliberately not `X-Forwarded-For`**: that header is
caller-supplied, and trusting it here would let anyone mint an unlimited number
of buckets by varying one string. If you terminate TLS in front of the gateway,
that proxy is where a trusted client-IP decision belongs.

## What is never limited

| Path | Why |
|---|---|
| `/actuator/**` | Rate-limiting health gets the gateway killed by its own orchestrator under exactly the load the limiter exists to survive |
| `/api/auth/**`, `/oauth2/**` | auth-service throttles credential attempts itself — and a per-tenant bucket cannot help, because the requests that matter arrive with **no tenant on them at all** |

## It fails open, on purpose

If Redis is unreachable the request is **allowed**, and the event is logged at
`WARN` rather than `ERROR` — the platform is working, the limiter is not.

A rate limiter that returned `503` when its bookkeeping store blipped would have
converted a Redis incident into a total platform outage. It would be the most
effective denial of service in the system.

## The window is part of the key

The bucket key includes the window slot, not just a TTL.

With the window only as an expiry, a burst arriving as one window ends and
another begins is counted against a single bucket whose TTL keeps being
refreshed — the classic sliding-window mistake that lets through roughly double
the limit at a boundary.

The counter increments and expires in **one atomic Lua script**. Doing it in two
round trips leaves a window where a process dying between them leaves a key with
no TTL — and that key then holds a customer at their limit permanently, with no
way to notice except a support ticket.

## Other limits in the platform

The gateway limit is not the only throttle you can hit.

### Authentication

auth-service applies its own limits, per account **and** per IP, on password
login. OTPs add a 5-attempt lockout that is **persisted**, with a 5-minute code
lifetime.

Watch `auth_events_total{type="RATE_LIMITED"}` and
`auth_events_total{type="REFRESH_REUSE"}` — spikes in either are worth an alert.

### The landing-page voice agent

5 sessions per IP and 120 overall per 10-minute sliding window, in memory, with
no Redis dependency on the marketing page's critical path.

That limiter is what protects the ElevenLabs bill, because the endpoints are
anonymous by necessity — a landing-page visitor has no account. The production
profile refuses to start with it disabled.

Being in-process, it is **per replica**: N replicas allow N times the intended
sessions.

### Execution concurrency

Not a rate limit, but the other place requests queue:

- `EXECUTION_POOL_SIZE` (default 4) bounds concurrent runs per core-service.
- `STEP_SANDBOX_USERS` (default 8) bounds concurrent steps in job-service; past
  it a step waits `STEP_SLOT_WAIT` and then **fails honestly** rather than
  piling up.

### Plan quotas

Quotas are not rate limits and do not clear by waiting. A `403 quota_exceeded`
means delete something or upgrade — the message carries the plan max. See
[Core Concepts](../getting-started/core-concepts.md).

## Handling 429 in a client

1. Read `Retry-After` and wait that long. Do not invent your own backoff first.
2. Add jitter if you have many workers, so they do not all return together.
3. Do **not** retry `403` with a code — the subscription gate and quotas are not
   transient.
4. Do **not** retry a refresh on `401` more than once, serialized. Parallel
   refreshes trip reuse detection and revoke the whole session family. See
   [Authentication](authentication.md).

## Related

- [Authentication](authentication.md)
- [REST API](rest-api.md)
- [High Availability](../architecture/high-availability.md)
