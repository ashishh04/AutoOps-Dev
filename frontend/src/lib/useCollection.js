import { useCallback, useEffect, useRef, useState } from "react";
import { api } from "./api";
import { peek, put } from "./httpCache";

// Loads and mutates a project-scoped collection (jobs, nodes, workflows,
// executions) from the backend. Returns rows plus create/remove/reload helpers.
//
// `filter` is an optional server-side narrowing passed straight to api.list —
// e.g. {targetType: "JOB", targetId} to load one job's runs. It is keyed by
// VALUE, not identity, so callers can pass a plain object literal without
// re-fetching on every render.
//
// STALE-WHILE-REVALIDATE. The rows from the last visit are painted immediately
// and refreshed behind them, so returning to a list is instant instead of a
// spinner over data that was on screen seconds ago. Two properties make that
// safe: every mount still re-fetches, so nothing here is ever shown without
// being checked; and the cache is emptied by any write and any session change.
//
// This cache holds MAPPED rows keyed per resource + project + filter, which is
// why it is separate from the transport cache in api.js — the fetch below is
// always a real request, so a polled reload cannot be answered from anything.
export function useCollection(resource, projectId, filter) {
  const filterKey = filter ? JSON.stringify(filter) : "";
  const cacheKey = `collection:${resource}:${projectId ?? ""}:${filterKey}`;
  // Seeded during the first render rather than in an effect: an effect runs
  // after the browser has already painted, which is a frame of spinner.
  const [rows, setRows] = useState(() => peek(cacheKey) ?? []);
  const [loading, setLoading] = useState(() => peek(cacheKey) === undefined);
  const [error, setError] = useState(null);
  // The rows on screen, readable outside a state updater — an optimistic change
  // has to be written into the cache as well as into the table, or the next
  // visit re-fetches a list the user is already looking at.
  const rowsRef = useRef(rows);
  rowsRef.current = rows;
  // The key this hook is currently showing. A fetch compares against it before
  // writing state, because a load started for one project can land after the
  // user has moved to another — and the answer belongs to the list they left.
  const keyRef = useRef(cacheKey);

  const reload = useCallback(async () => {
    // A spinner only when there is genuinely nothing to look at. A reload with
    // rows already on screen — the Workflows poll, or a revalidation behind a
    // cached list — must not blank the table it is refreshing.
    if (peek(cacheKey) === undefined) setLoading(true);
    setError(null);
    try {
      // Unfiltered callers keep the original two-argument call exactly.
      const data = filterKey
        ? await api.list(resource, projectId, JSON.parse(filterKey))
        : await api.list(resource, projectId);
      const next = Array.isArray(data) ? data : [];
      // Cached under the key it was asked for either way — a list the user has
      // left is still worth having when they come back to it.
      put(cacheKey, next);
      if (keyRef.current === cacheKey) setRows(next);
      // Returned as well as stored: a caller polling for a change cannot read
      // the state it just set, and re-fetching to see it would double the load.
      return next;
    } catch (e) {
      if (keyRef.current === cacheKey) setError(e.message || "Failed to load");
      return null;
    } finally {
      // Not unconditionally: clearing `loading` here would announce that the
      // list now on screen has arrived, when what arrived was the previous one.
      if (keyRef.current === cacheKey) setLoading(false);
    }
  }, [resource, projectId, filterKey, cacheKey]);

  useEffect(() => {
    // Compared rather than re-seeded on every run: the first render already
    // seeded itself from the cache.
    if (keyRef.current !== cacheKey) {
      keyRef.current = cacheKey;
      // A different collection entirely — another project, another filter. Its
      // own cached rows if it has any, and an empty table if it does not:
      // leaving the previous project's rows on screen under the new heading
      // would misattribute one customer's jobs to another.
      const cached = peek(cacheKey);
      setRows(cached ?? []);
      setLoading(cached === undefined);
    }
    reload();
  }, [reload, cacheKey]);

  const create = useCallback(
    async (body) => {
      const created = await api.create(resource, { projectId, ...body });
      // The write already emptied the whole cache (see realFetch); this puts
      // back the one entry we can account for exactly.
      const next = [created, ...rowsRef.current];
      put(cacheKey, next);
      setRows(next);
      return created;
    },
    [resource, projectId, cacheKey],
  );

  const remove = useCallback(
    async (id) => {
      await api.remove(resource, id);
      const next = rowsRef.current.filter((x) => x.id !== id);
      put(cacheKey, next);
      setRows(next);
    },
    [resource, cacheKey],
  );

  return { rows, loading, error, reload, create, remove, setRows };
}
