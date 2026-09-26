import { useCallback, useMemo } from "react";
import { useSearchParams } from "react-router-dom";

/**
 * Which slice of the alert plane a screen is looking at.
 *
 * <h2>Why this is a query parameter and not a route segment</h2>
 * Incidents, the alert feed and monitoring sources used to live under
 * `/app/projects/:pid/...`, which forced a customer to connect Datadog once per
 * project and then remember which project an alert had landed in before they
 * could go and read it. Alerts do not arrive per project — a monitoring tool
 * has never heard of an AutoOps project — so the workspace is the honest
 * default and the project is a filter on top of it.
 *
 * Making the project a filter rather than a path has one concrete consequence
 * worth stating: clearing it is a normal UI action, not a navigation to a
 * different page. `?project=` simply goes away.
 *
 * <h2>Validated, not trusted</h2>
 * A query parameter is user-editable, so it is checked against `\d+` before it
 * is ever sent. Not for safety — the server scopes every answer to the JWT and
 * treats `projectId` as a narrowing filter that cannot widen anything — but
 * because `?project=all` would otherwise be forwarded verbatim and come back as
 * a request rejection on a screen where the customer only pressed a dropdown.
 */
export function useAlertScope() {
  const [params, setParams] = useSearchParams();
  const raw = params.get("project");
  const projectId = raw && /^\d+$/.test(raw) ? raw : undefined;

  const setProject = useCallback(
    (next) => {
      const updated = new URLSearchParams(params);
      if (next) {
        updated.set("project", String(next));
      } else {
        updated.delete("project");
      }
      // replace: narrowing a filter is not a place you want to come back to
      // with the browser's Back button, which should leave the screen.
      setParams(updated, { replace: true });
    },
    [params, setParams],
  );

  /**
   * A link to another alert-plane screen, carrying the current filter.
   *
   * Every navigation between these screens has to preserve it, and doing that
   * by hand is how the filter silently resets on the third click.
   */
  const link = useCallback(
    (path) => `/app${path}${projectId ? `?project=${projectId}` : ""}`,
    [projectId],
  );

  return useMemo(
    () => ({ projectId, setProject, link }),
    [projectId, setProject, link],
  );
}
