import { act, renderHook, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

// Every project-scoped list page (jobs, workflows, executions, nodes) runs on
// this hook, so its loading/error/optimistic-update behaviour is the behaviour
// of a dozen screens at once.
vi.mock("./api", () => ({
  api: { list: vi.fn(), create: vi.fn(), remove: vi.fn() },
}));

let useCollection;
let api;

beforeEach(async () => {
  // Fresh module per test: the stale-while-revalidate cache is module state, so
  // without this a list loaded in one test is still on screen in the next.
  vi.resetModules();
  ({ useCollection } = await import("./useCollection"));
  ({ api } = await import("./api"));
});

/** A promise whose resolution the test controls, to observe an in-flight load. */
function deferred() {
  let resolve;
  const promise = new Promise((r) => {
    resolve = r;
  });
  return { promise, resolve };
}

describe("useCollection", () => {
  it("loads rows for the resource and project", async () => {
    api.list.mockResolvedValue([{ id: 1, name: "Nightly" }]);

    const { result } = renderHook(() => useCollection("jobs", "7"));

    expect(result.current.loading).toBe(true);
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(api.list).toHaveBeenCalledWith("jobs", "7");
    expect(result.current.rows).toEqual([{ id: 1, name: "Nightly" }]);
    expect(result.current.error).toBeNull();
  });

  it("leaves the table empty on failure when there is nothing cached", async () => {
    api.list.mockRejectedValue(new Error("Plan limit reached"));

    const { result } = renderHook(() => useCollection("jobs", "7"));

    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.error).toBe("Plan limit reached");
    expect(result.current.rows).toEqual([]);
  });

  /** A non-array payload used to blank the page with "rows.map is not a function". */
  it("coerces a non-array payload to an empty list", async () => {
    api.list.mockResolvedValue(null);

    const { result } = renderHook(() => useCollection("jobs", "7"));

    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.rows).toEqual([]);
  });

  it("prepends a created row without refetching", async () => {
    api.list.mockResolvedValue([{ id: 1, name: "Old" }]);
    api.create.mockResolvedValue({ id: 2, name: "New" });

    const { result } = renderHook(() => useCollection("jobs", "7"));
    await waitFor(() => expect(result.current.loading).toBe(false));

    await act(async () => {
      await result.current.create({ name: "New" });
    });

    expect(api.create).toHaveBeenCalledWith("jobs", { projectId: "7", name: "New" });
    expect(result.current.rows.map((r) => r.id)).toEqual([2, 1]);
    expect(api.list).toHaveBeenCalledTimes(1);
  });

  it("drops a removed row from the table", async () => {
    api.list.mockResolvedValue([{ id: 1 }, { id: 2 }]);
    api.remove.mockResolvedValue(undefined);

    const { result } = renderHook(() => useCollection("jobs", "7"));
    await waitFor(() => expect(result.current.loading).toBe(false));

    await act(async () => {
      await result.current.remove(1);
    });

    expect(result.current.rows).toEqual([{ id: 2 }]);
  });

  it("keeps the row when the delete is rejected", async () => {
    api.list.mockResolvedValue([{ id: 1 }]);
    api.remove.mockRejectedValue(new Error("forbidden"));

    const { result } = renderHook(() => useCollection("jobs", "7"));
    await waitFor(() => expect(result.current.loading).toBe(false));

    await act(async () => {
      await expect(result.current.remove(1)).rejects.toThrow("forbidden");
    });

    expect(result.current.rows).toEqual([{ id: 1 }]);
  });

  it("reloads when the project changes", async () => {
    api.list.mockResolvedValue([]);

    const { rerender } = renderHook(({ pid }) => useCollection("jobs", pid), {
      initialProps: { pid: "7" },
    });
    await waitFor(() => expect(api.list).toHaveBeenCalledWith("jobs", "7"));

    rerender({ pid: "8" });

    await waitFor(() => expect(api.list).toHaveBeenCalledWith("jobs", "8"));
  });

  it("passes a server-side filter through and re-fetches when it changes", async () => {
    api.list.mockResolvedValue([]);

    // A plain object literal: the hook keys the filter by VALUE, so an
    // unchanged filter must not re-fetch on every render.
    const { result, rerender } = renderHook(
      ({ jobId }) =>
        useCollection("executions", "7", { targetType: "JOB", targetId: jobId }),
      { initialProps: { jobId: "31" } },
    );

    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(api.list).toHaveBeenCalledWith("executions", "7", {
      targetType: "JOB",
      targetId: "31",
    });

    rerender({ jobId: "31" });
    expect(api.list).toHaveBeenCalledTimes(1);

    rerender({ jobId: "32" });
    await waitFor(() =>
      expect(api.list).toHaveBeenCalledWith("executions", "7", {
        targetType: "JOB",
        targetId: "32",
      }),
    );
  });

  /**
   * The point of the whole cache: walking from a list into a detail page and
   * back used to blank the table and spin for rows that had been on screen a
   * moment earlier.
   */
  it("paints the previous rows on the first render when a list is revisited", async () => {
    api.list.mockResolvedValue([{ id: 1, name: "Nightly" }]);
    const first = renderHook(() => useCollection("jobs", "7"));
    await waitFor(() => expect(first.result.current.loading).toBe(false));
    first.unmount();

    const second = renderHook(() => useCollection("jobs", "7"));

    // Asserted with no await: this is the very first render of the remount.
    expect(second.result.current.loading).toBe(false);
    expect(second.result.current.rows).toEqual([{ id: 1, name: "Nightly" }]);
    // Let the revalidation behind those rows settle before the test ends.
    await waitFor(() => expect(api.list).toHaveBeenCalledTimes(2));
  });

  it("still re-fetches behind the cached rows", async () => {
    api.list.mockResolvedValue([{ id: 1, name: "Nightly" }]);
    const first = renderHook(() => useCollection("jobs", "7"));
    await waitFor(() => expect(first.result.current.loading).toBe(false));
    first.unmount();
    api.list.mockResolvedValue([{ id: 1, name: "Renamed" }]);

    const second = renderHook(() => useCollection("jobs", "7"));

    await waitFor(() =>
      expect(second.result.current.rows).toEqual([{ id: 1, name: "Renamed" }]),
    );
    expect(api.list).toHaveBeenCalledTimes(2);
  });

  /** The Workflows poll calls reload() every 3s; it must not flash a spinner. */
  it("does not raise loading on a reload that has rows to show", async () => {
    api.list.mockResolvedValue([{ id: 1 }]);
    const { result } = renderHook(() => useCollection("jobs", "7"));
    await waitFor(() => expect(result.current.loading).toBe(false));

    const next = deferred();
    api.list.mockReturnValue(next.promise);
    let inFlight;
    act(() => {
      inFlight = result.current.reload();
    });

    expect(result.current.loading).toBe(false);
    expect(result.current.rows).toEqual([{ id: 1 }]);
    await act(async () => {
      next.resolve([{ id: 1 }, { id: 2 }]);
      await inFlight;
    });
    expect(result.current.rows).toEqual([{ id: 1 }, { id: 2 }]);
  });

  /** Cached rows are per project: project 8 must never show project 7's list. */
  it("does not show one project's cached rows under another project", async () => {
    api.list.mockResolvedValue([{ id: 1, name: "Seven" }]);
    const { result, rerender } = renderHook(({ pid }) => useCollection("jobs", pid), {
      initialProps: { pid: "7" },
    });
    await waitFor(() => expect(result.current.loading).toBe(false));

    const pending = deferred();
    api.list.mockReturnValue(pending.promise);
    rerender({ pid: "8" });

    expect(result.current.rows).toEqual([]);
    expect(result.current.loading).toBe(true);
    await act(async () => {
      pending.resolve([{ id: 2, name: "Eight" }]);
      await pending.promise;
    });
    expect(result.current.rows).toEqual([{ id: 2, name: "Eight" }]);
  });

  /**
   * A slow list for one project answering after the user has moved to another.
   * The rows are still worth caching under the key they were asked for, but
   * putting them on screen would label one project's jobs with another's name.
   */
  it("ignores a load that lands after the project changed", async () => {
    const seven = deferred();
    api.list.mockReturnValueOnce(seven.promise);
    const { result, rerender } = renderHook(({ pid }) => useCollection("jobs", pid), {
      initialProps: { pid: "7" },
    });
    api.list.mockResolvedValue([{ id: 2, name: "Eight" }]);

    rerender({ pid: "8" });
    await waitFor(() =>
      expect(result.current.rows).toEqual([{ id: 2, name: "Eight" }]),
    );
    await act(async () => {
      seven.resolve([{ id: 1, name: "Seven" }]);
      await seven.promise;
    });

    expect(result.current.rows).toEqual([{ id: 2, name: "Eight" }]);
    expect(result.current.loading).toBe(false);
  });

  it("keeps a deleted row out of the cached copy the next visit reads", async () => {
    api.list.mockResolvedValue([{ id: 1 }, { id: 2 }]);
    api.remove.mockResolvedValue(undefined);
    const first = renderHook(() => useCollection("jobs", "7"));
    await waitFor(() => expect(first.result.current.loading).toBe(false));
    await act(async () => {
      await first.result.current.remove(1);
    });
    first.unmount();

    const second = renderHook(() => useCollection("jobs", "7"));

    expect(second.result.current.rows).toEqual([{ id: 2 }]);
    await waitFor(() => expect(api.list).toHaveBeenCalledTimes(2));
  });
});
