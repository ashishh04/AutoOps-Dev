import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  cacheEntryCount,
  cachedGet,
  invalidateAll,
  peek,
  put,
} from "./httpCache";

// The cache decides what the app is allowed to show without asking the server,
// so its two dangerous edges are tested directly: what it keeps (a stale value
// past its window, a failure, a value belonging to a signed-out session) and
// what it hands out (a copy, never the object it is storing).
beforeEach(() => {
  invalidateAll();
});

afterEach(() => {
  vi.useRealTimers();
});

describe("cachedGet", () => {
  it("joins concurrent callers onto one fetch", async () => {
    const fetcher = vi.fn(async () => ["a"]);

    const [first, second] = await Promise.all([
      cachedGet("/plugins/catalog", fetcher, { maxAge: 30_000 }),
      cachedGet("/plugins/catalog", fetcher, { maxAge: 30_000 }),
    ]);

    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(first).toEqual(["a"]);
    expect(second).toEqual(["a"]);
  });

  /** maxAge 0 is what every path outside the allowlist gets. */
  it("dedupes but never reuses a resolved value when maxAge is 0", async () => {
    const fetcher = vi.fn(async () => ["a"]);

    await Promise.all([
      cachedGet("/auth/me", fetcher, { maxAge: 0 }),
      cachedGet("/auth/me", fetcher, { maxAge: 0 }),
    ]);
    await cachedGet("/auth/me", fetcher, { maxAge: 0 });

    // One call for the concurrent pair, one for the later caller.
    expect(fetcher).toHaveBeenCalledTimes(2);
    expect(cacheEntryCount()).toBe(0);
  });

  it("serves a second caller from the first response inside the window", async () => {
    const fetcher = vi.fn(async () => ["a"]);

    await cachedGet("/library", fetcher, { maxAge: 30_000 });
    const again = await cachedGet("/library", fetcher, { maxAge: 30_000 });

    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(again).toEqual(["a"]);
  });

  it("re-fetches once the window has passed", async () => {
    vi.useFakeTimers();
    const fetcher = vi.fn(async () => ["a"]);

    await cachedGet("/library", fetcher, { maxAge: 30_000 });
    vi.setSystemTime(Date.now() + 30_001);
    await cachedGet("/library", fetcher, { maxAge: 30_000 });

    expect(fetcher).toHaveBeenCalledTimes(2);
  });

  /** A cached 500 would keep a screen broken after the backend recovered. */
  it("does not cache a failure", async () => {
    const fetcher = vi
      .fn()
      .mockRejectedValueOnce(new Error("boom"))
      .mockResolvedValueOnce(["a"]);

    await expect(
      cachedGet("/library", fetcher, { maxAge: 30_000 }),
    ).rejects.toThrow("boom");
    expect(cacheEntryCount()).toBe(0);

    await expect(
      cachedGet("/library", fetcher, { maxAge: 30_000 }),
    ).resolves.toEqual(["a"]);
  });

  it("hands each of two joined callers its own copy", async () => {
    const fetcher = vi.fn(async () => [{ id: 1 }]);

    const [first, second] = await Promise.all([
      cachedGet("/library", fetcher, { maxAge: 30_000 }),
      cachedGet("/library", fetcher, { maxAge: 30_000 }),
    ]);
    first[0].id = 99;

    expect(second).toEqual([{ id: 1 }]);
  });

  it("hands every reader its own copy", async () => {
    const fetcher = vi.fn(async () => [{ id: 1 }]);

    const first = await cachedGet("/library", fetcher, { maxAge: 30_000 });
    first.push({ id: 2 });
    first[0].id = 99;
    const second = await cachedGet("/library", fetcher, { maxAge: 30_000 });

    expect(second).toEqual([{ id: 1 }]);
  });
});

describe("peek and put", () => {
  it("returns undefined until there is a value, then the value", () => {
    expect(peek("collection:jobs:7:")).toBeUndefined();

    put("collection:jobs:7:", [{ id: 1 }]);

    expect(peek("collection:jobs:7:")).toEqual([{ id: 1 }]);
  });

  /**
   * undefined means "nothing to show" and drives the spinner decision, so a
   * cached null — a real response from this backend — must not look like a miss.
   */
  it("distinguishes a cached null from a miss", () => {
    put("collection:jobs:7:", null);

    expect(peek("collection:jobs:7:")).toBeNull();
  });

  it("does not expose an in-flight entry as a value", () => {
    cachedGet("/library", () => new Promise(() => {}), { maxAge: 30_000 });

    expect(peek("/library")).toBeUndefined();
  });

  it("copies on read, so one reader cannot edit another's rows", () => {
    put("collection:jobs:7:", [{ id: 1 }]);

    peek("collection:jobs:7:")[0].id = 99;

    expect(peek("collection:jobs:7:")).toEqual([{ id: 1 }]);
  });
});

describe("invalidateAll", () => {
  it("empties everything", async () => {
    await cachedGet("/library", async () => ["a"], { maxAge: 30_000 });
    put("collection:jobs:7:", [{ id: 1 }]);

    invalidateAll();

    expect(cacheEntryCount()).toBe(0);
    expect(peek("collection:jobs:7:")).toBeUndefined();
  });
});
