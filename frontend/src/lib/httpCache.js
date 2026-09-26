/**
 * A per-tab, in-memory response cache.
 *
 * <p>What it exists to fix: every screen loaded its data from scratch on mount,
 * and about fifteen of them render `if (loading) return <spinner/>`. Walking
 * from a list to a detail page and back therefore blanked the page and spun
 * again for data that had been on screen three seconds earlier. Nothing was
 * slow in the usual sense — the app just never remembered anything.
 *
 * Two deliberate limits:
 *
 * <p><strong>Memory only, never storage.</strong> A reload starts cold. Two
 * tabs legitimately hold two different accounts in this app (see tokenStore in
 * api.js), and a cache in localStorage would be a single bucket shared by both
 * — exactly the bug that per-tab tokens exist to prevent. A Map dies with its
 * tab, so it cannot leak across one.
 *
 * <p><strong>No tenant in the key.</strong> Because there is no partitioning,
 * the whole cache is DROPPED on any session change — sign-in, sign-out, token
 * rotation, tenant switch — and on any write. Over-invalidating costs one
 * refetch; under-invalidating shows one tenant's rows to another.
 */

/** key -> { value, at, inflight }. `value === undefined` means "not resolved yet". */
const store = new Map();

/**
 * Readers get their own copy.
 *
 * <p>A cached response is handed to several components over its lifetime, and
 * one of them sorting the array in place would silently reorder what every
 * later reader sees. Responses are JSON, so a structured copy is total; the
 * JSON round-trip is only for environments without structuredClone.
 */
function copy(value) {
  if (value === null || typeof value !== "object") return value;
  if (typeof structuredClone === "function") return structuredClone(value);
  return JSON.parse(JSON.stringify(value));
}

/**
 * Fetch through the cache.
 *
 * @param key     identity of the request — the full path including query string
 * @param fetcher called only on a miss
 * @param maxAge  milliseconds a resolved value may be reused for. **0 means
 *                dedupe only**: concurrent callers share one network call, but
 *                nothing is ever served from a previous one. That is the safe
 *                default, and it is what every path outside the allowlist gets.
 */
export function cachedGet(key, fetcher, { maxAge = 0 } = {}) {
  const hit = store.get(key);
  if (hit) {
    // A page that fires twelve reads on mount (NotificationChannels does, and
    // AiProviders twelve more) repeats several of them; the duplicates join the
    // first call instead of opening their own connection.
    // `.then(copy)` and not the shared promise: two callers joining one request
    // must still end up with two objects, or the copy-on-read guarantee holds
    // for sequential readers and quietly fails for simultaneous ones.
    if (hit.inflight) return hit.inflight.then(copy);
    if (maxAge > 0 && hit.value !== undefined && now() - hit.at <= maxAge) {
      return Promise.resolve(copy(hit.value));
    }
  }
  const inflight = fetcher().then(
    (value) => {
      if (maxAge > 0) store.set(key, { value, at: now(), inflight: null });
      else store.delete(key);
      return copy(value);
    },
    (error) => {
      // Failures are never retained: a cached 500 would keep a screen broken
      // for the whole window after the backend recovered.
      store.delete(key);
      throw error;
    },
  );
  store.set(key, { value: undefined, at: 0, inflight });
  return inflight;
}

/**
 * The cached value for `key`, or undefined — synchronously.
 *
 * <p>For callers that must decide whether to show a spinner BEFORE they await
 * anything. `undefined` means "nothing to show"; a cached `null` is a real
 * value and is returned as one.
 */
export function peek(key) {
  const hit = store.get(key);
  if (!hit || hit.value === undefined) return undefined;
  return copy(hit.value);
}

/** Store a value under `key` with no expiry. Used for mapped collections. */
export function put(key, value) {
  store.set(key, { value, at: now(), inflight: null });
}

/** Drop everything. Called on every write and every session change. */
export function invalidateAll() {
  store.clear();
}

/** Entry count. Exists for the tests — nothing in the app should need it. */
export function cacheEntryCount() {
  return store.size;
}

function now() {
  return Date.now();
}
