import { beforeEach, describe, expect, it, vi } from "vitest";
import { fetchSequence, response } from "../test/setup";

// The transport is the security-critical half of the front end: it decides what
// carries a bearer token, what happens to a rotated refresh token, and what the
// user is told when the subscription gate says no. Tested through the public
// api surface — realFetch is deliberately not exported.
let api;
let tokenStore;
let ApiError;

beforeEach(async () => {
  // Fresh module per test: the single-flight refresh promise is module state.
  vi.resetModules();
  ({ api, tokenStore, ApiError } = await import("./api"));
});

describe("tokenStore", () => {
  it("round-trips both tokens", () => {
    tokenStore.set("access-1", "refresh-1");

    expect(tokenStore.access).toBe("access-1");
    expect(tokenStore.refresh).toBe("refresh-1");
  });

  it("keeps the existing refresh token when only an access token is set", () => {
    tokenStore.set("access-1", "refresh-1");
    tokenStore.set("access-2", null);

    expect(tokenStore.access).toBe("access-2");
    expect(tokenStore.refresh).toBe("refresh-1");
  });

  it("clears both on clear()", () => {
    tokenStore.set("access-1", "refresh-1");
    tokenStore.clear();

    expect(tokenStore.access).toBeNull();
    expect(tokenStore.refresh).toBeNull();
  });
});

describe("authenticated requests", () => {
  it("sends the bearer token on authenticated calls", async () => {
    tokenStore.set("access-1", "refresh-1");
    const fetchMock = fetchSequence(response(200, []));

    await api.listProjects();

    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("/api/projects");
    expect(init.headers.Authorization).toBe("Bearer access-1");
  });

  it("does not send a bearer token on anonymous calls", async () => {
    tokenStore.set("access-1", "refresh-1");
    const fetchMock = fetchSequence(response(200, { accessToken: "a", refreshToken: "r" }),
        response(200, { id: 1, email: "x@y.z", role: "CLIENT", tenantId: "t" }));

    await api.login("x@y.z", "pw");

    // The login call itself must be anonymous — a stale token must not
    // influence who the server thinks is signing in.
    expect(fetchMock.mock.calls[0][1].headers.Authorization).toBeUndefined();
  });

  it("serialises the body as JSON", async () => {
    tokenStore.set("access-1", "refresh-1");
    const fetchMock = fetchSequence(response(200, { id: 7, name: "Ops", status: "ACTIVE" }));

    await api.createProject({ name: "Ops" });

    const [, init] = fetchMock.mock.calls[0];
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body)).toMatchObject({ name: "Ops" });
    expect(init.headers["Content-Type"]).toBe("application/json");
  });
});

describe("error mapping", () => {
  it("prefers the server's message", async () => {
    tokenStore.set("access-1", "refresh-1");
    fetchSequence(response(400, { message: "Name already taken", error: "duplicate" }));

    await expect(api.listProjects()).rejects.toMatchObject({
      message: "Name already taken",
      status: 400,
    });
  });

  it("falls back to the error code, then to a generic message", async () => {
    tokenStore.set("access-1", "refresh-1");
    fetchSequence(response(400, { error: "duplicate" }));
    await expect(api.listProjects()).rejects.toThrow("duplicate");

    fetchSequence(response(500, undefined));
    await expect(api.listProjects()).rejects.toThrow("Request failed (500)");
  });

  it("throws ApiError carrying the status and payload", async () => {
    tokenStore.set("access-1", "refresh-1");
    fetchSequence(response(403, { error: "quota_exceeded", message: "Plan limit reached" }));

    const error = await api.listProjects().catch((e) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect(error.status).toBe(403);
    expect(error.data.error).toBe("quota_exceeded");
  });

  it("survives a non-JSON error body", async () => {
    tokenStore.set("access-1", "refresh-1");
    global.fetch = vi.fn(async () => ({
      ok: false,
      status: 502,
      text: async () => "<html>gateway</html>",
    }));

    await expect(api.listProjects()).rejects.toThrow("Request failed (502)");
  });
});

describe("subscription gate", () => {
  const GATE_CODES = [
    "feature_not_in_plan",
    "quota_exceeded",
    "trial_expired",
    "subscription_expired",
    "subscription_past_due",
    "subscription_canceled",
    "no_subscription",
  ];

  it.each(GATE_CODES)("raises the upgrade prompt for %s", async (code) => {
    tokenStore.set("access-1", "refresh-1");
    const listener = vi.fn();
    window.addEventListener("autoops:upgrade-required", listener);
    fetchSequence(response(403, { error: code, message: "Upgrade to continue" }));

    await api.listProjects().catch(() => {});

    expect(listener).toHaveBeenCalledTimes(1);
    expect(listener.mock.calls[0][0].detail).toEqual({
      code,
      message: "Upgrade to continue",
    });
    window.removeEventListener("autoops:upgrade-required", listener);
  });

  it("stays quiet for an ordinary error", async () => {
    tokenStore.set("access-1", "refresh-1");
    const listener = vi.fn();
    window.addEventListener("autoops:upgrade-required", listener);
    fetchSequence(response(400, { error: "validation_failed" }));

    await api.listProjects().catch(() => {});

    expect(listener).not.toHaveBeenCalled();
    window.removeEventListener("autoops:upgrade-required", listener);
  });
});

describe("transparent refresh", () => {
  it("refreshes once on a 401 and replays the original call", async () => {
    tokenStore.set("expired", "refresh-1");
    const fetchMock = fetchSequence(
      response(401, { error: "token_expired" }),
      response(200, { accessToken: "access-2", refreshToken: "refresh-2" }),
      response(200, []),
    );

    await api.listProjects();

    expect(fetchMock.mock.calls[1][0]).toBe("/api/auth/refresh");
    // The replay carries the NEW token, not the expired one.
    expect(fetchMock.mock.calls[2][1].headers.Authorization).toBe("Bearer access-2");
    expect(tokenStore.access).toBe("access-2");
    expect(tokenStore.refresh).toBe("refresh-2");
  });

  it("gives up after one refresh — no retry loop", async () => {
    tokenStore.set("expired", "refresh-1");
    const fetchMock = fetchSequence(
      response(401, { error: "token_expired" }),
      response(200, { accessToken: "access-2", refreshToken: "refresh-2" }),
      response(401, { error: "token_expired" }),
    );

    await expect(api.listProjects()).rejects.toMatchObject({ status: 401 });

    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it("clears the session when the refresh token is rejected", async () => {
    tokenStore.set("expired", "revoked");
    fetchSequence(
      response(401, { error: "token_expired" }),
      response(401, { error: "invalid_refresh_token" }),
    );

    await expect(api.listProjects()).rejects.toMatchObject({ status: 401 });

    expect(tokenStore.access).toBeNull();
    expect(tokenStore.refresh).toBeNull();
  });

  it("does not try to refresh without a refresh token", async () => {
    localStorage.setItem("autoops_access_token", "expired");
    const fetchMock = fetchSequence(response(401, { error: "token_expired" }));

    await expect(api.listProjects()).rejects.toMatchObject({ status: 401 });

    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  /**
   * The one that matters most. Refresh tokens rotate on every use and
   * auth-service revokes the whole session family when a rotated token is
   * replayed — so two concurrent 401s MUST share one refresh call. A second
   * parallel refresh logs the user out of every device.
   */
  it("shares ONE refresh across concurrent 401s", async () => {
    tokenStore.set("expired", "refresh-1");
    let refreshCalls = 0;
    global.fetch = vi.fn(async (url, init) => {
      if (String(url).endsWith("/auth/refresh")) {
        refreshCalls++;
        // Hold the refresh open so both callers queue behind this one call.
        await new Promise((resolve) => setTimeout(resolve, 20));
        return response(200, { accessToken: "access-2", refreshToken: "refresh-2" });
      }
      const authorization = init?.headers?.Authorization;
      return authorization === "Bearer access-2"
        ? response(200, [])
        : response(401, { error: "token_expired" });
    });

    // TWO DIFFERENT paths on purpose. The response cache joins concurrent reads
    // of the SAME path into one request, which would satisfy this assertion
    // without the single-flight refresh ever being exercised.
    await Promise.all([api.listProjects(), api.listPayments()]);

    expect(refreshCalls).toBe(1);
  });
});

/**
 * The response cache. What it may serve is an allowlist in api.js, and the risk
 * it carries is showing one session's data to the next — so the emptying rules
 * matter more here than the hit rate.
 */
describe("response cache", () => {
  it("serves an allowlisted path from the previous response", async () => {
    tokenStore.set("access-1", "refresh-1");
    const fetchMock = fetchSequence(
      response(200, [{ id: 1, name: "Ops", status: "ACTIVE" }]),
    );

    const first = await api.listProjects();
    const second = await api.listProjects();

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(second).toEqual(first);
  });

  /** Identity and entitlement are never served from a previous answer. */
  it("re-fetches a path that is not on the allowlist", async () => {
    tokenStore.set("access-1", "refresh-1");
    const fetchMock = fetchSequence(response(200, []), response(200, []));

    await api.listPayments();
    await api.listPayments();

    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  /** Deduplication applies to every GET, allowlisted or not. */
  it("joins concurrent reads of one path into a single request", async () => {
    tokenStore.set("access-1", "refresh-1");
    const fetchMock = fetchSequence(response(200, []));

    await Promise.all([api.listPayments(), api.listPayments()]);

    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("empties the cache after a write", async () => {
    tokenStore.set("access-1", "refresh-1");
    const fetchMock = fetchSequence(
      response(200, [{ id: 1, name: "Ops", status: "ACTIVE" }]),
      response(200, { id: 2, name: "New", status: "ACTIVE" }),
      response(200, [
        { id: 1, name: "Ops", status: "ACTIVE" },
        { id: 2, name: "New", status: "ACTIVE" },
      ]),
    );

    await api.listProjects();
    await api.createProject({ name: "New" });
    const after = await api.listProjects();

    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(after.map((p) => p.id)).toEqual([1, 2]);
  });

  /** A write that failed may still have changed server state. */
  it("empties the cache even when the write fails", async () => {
    tokenStore.set("access-1", "refresh-1");
    const fetchMock = fetchSequence(
      response(200, [{ id: 1, name: "Ops", status: "ACTIVE" }]),
      response(500, { error: "internal_error" }),
      response(200, [{ id: 1, name: "Ops", status: "ACTIVE" }]),
    );

    await api.listProjects();
    await expect(api.createProject({ name: "New" })).rejects.toThrow(ApiError);
    await api.listProjects();

    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  /**
   * The cache has no tenant in its keys, so a session change has to drop all of
   * it. Without this, signing into a second account in the same tab would read
   * the first account's projects out of memory.
   */
  it("drops everything when the session ends", async () => {
    tokenStore.set("access-1", "refresh-1");
    const fetchMock = fetchSequence(
      response(200, [{ id: 1, name: "Ops", status: "ACTIVE" }]),
      response(200, [{ id: 9, name: "Other", status: "ACTIVE" }]),
    );

    await api.listProjects();
    tokenStore.clear();
    tokenStore.set("access-2", "refresh-2");
    const after = await api.listProjects();

    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(after.map((p) => p.id)).toEqual([9]);
  });

  it("drops everything when a refresh rotates the token", async () => {
    tokenStore.set("access-1", "refresh-1");
    const fetchMock = fetchSequence(
      response(200, [{ id: 1, name: "Ops", status: "ACTIVE" }]),
      response(200, [{ id: 1, name: "Ops", status: "ACTIVE" }]),
    );

    await api.listProjects();
    // What tryRefresh does on a 401: the new access token may carry a different
    // role, so nothing read under the old one may be reused.
    tokenStore.set("access-2", "refresh-2");
    await api.listProjects();

    expect(fetchMock).toHaveBeenCalledTimes(2);
  });
});
