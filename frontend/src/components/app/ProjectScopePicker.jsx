import React, { useEffect, useState } from "react";
import { api } from "../../lib/api";

/**
 * "All projects", or one of them.
 *
 * <h2>Why the default is everything</h2>
 * The alert plane is a workspace concern. A monitoring tool raises alerts about
 * an estate, not about an AutoOps project, and a customer who has connected
 * Datadog once should not have to guess which project an alert landed in before
 * they can read it. So the unfiltered view is the one that loads, and narrowing
 * is something you choose.
 *
 * <h2>Why it fails quietly</h2>
 * If the project list cannot be read, this renders nothing at all rather than
 * an error. The screen it sits on works perfectly well unfiltered — losing the
 * ability to narrow is an inconvenience, and turning it into a red banner over
 * a working alert feed during an incident would be the wrong trade.
 */
export default function ProjectScopePicker({
  value,
  onChange,
  label = "Project",
}) {
  const [projects, setProjects] = useState(null);

  useEffect(() => {
    let dropped = false;
    api
      .listProjects()
      .then((rows) => !dropped && setProjects(Array.isArray(rows) ? rows : []))
      .catch(() => !dropped && setProjects([]));
    return () => {
      dropped = true;
    };
  }, []);

  // One project, or none: there is nothing to choose between, and a dropdown
  // with a single option is a control that implies a decision nobody has.
  if (!projects || projects.length < 2) {
    return null;
  }

  return (
    <>
      <label className="ml-2 text-xs font-medium uppercase tracking-wider text-slate-500">
        {label}
      </label>
      <select
        value={value || ""}
        onChange={(e) => onChange(e.target.value || undefined)}
        aria-label={label}
        className="rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-sm text-slate-700"
      >
        <option value="">All projects</option>
        {projects.map((p) => (
          <option key={p.id} value={p.id}>
            {p.name}
          </option>
        ))}
      </select>
    </>
  );
}
