import { realFetch } from "./api";

/**
 * The alert plane's client.
 *
 * Separate from `api.js` on purpose. That module's `api.list`/`api.get` are a
 * ternary chain mapping resource names onto real backends, and anything that
 * falls off the end hits `apiFetch`, which throws 501 — the guard that keeps
 * unimplemented screens honest instead of quietly mocked. Alerts have a real
 * backend, so they do not belong in that chain, and adding them to it would
 * mean editing a file this feature otherwise has no reason to touch.
 *
 * Every call is `auth: true`: alert-service reads the tenant out of the JWT and
 * scopes the answer to it. There is no tenant or project parameter that widens
 * what comes back — `projectId` can only narrow, and the server treats it that
 * way regardless of what is sent.
 */

const qs = (params) => {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== "") search.set(k, v);
  });
  const s = search.toString();
  return s ? `?${s}` : "";
};

export function listAlerts({ projectId, status, severity, limit } = {}) {
  return realFetch(`/alerts${qs({ projectId, status, severity, limit })}`, {
    auth: true,
  }).then((rows) => (Array.isArray(rows) ? rows : []));
}

/**
 * `projectId` widens nothing. It is how the server learns which monitoring
 * sources this project owns, which is the only way an alert carrying no labels
 * is recognised at all — a Datadog alert has never heard of an AutoOps project.
 * Omit it and such an alert lists fine, then 404s when opened.
 */
export function getAlert(fingerprint, projectId) {
  return realFetch(
    `/alerts/${encodeURIComponent(fingerprint)}${qs({ projectId })}`,
    { auth: true },
  );
}

/** The catalog of monitoring sources that can be connected. */
export function listProviderTypes() {
  return realFetch("/alert-providers", { auth: true }).then((rows) =>
    Array.isArray(rows) ? rows : [],
  );
}

/** How to make one source send alerts here — URL, token and a rewritten guide. */
export function getProviderSetup(type, projectId) {
  return realFetch(
    `/alert-providers/${encodeURIComponent(type)}/setup${qs({ projectId })}`,
    { auth: true },
  );
}

/** What this project has already connected. */
export function listConnectedProviders(projectId) {
  return realFetch(`/alert-providers/connected${qs({ projectId })}`, {
    auth: true,
  }).then((rows) => (Array.isArray(rows) ? rows : []));
}

/**
 * Credentials go one way. There is no counterpart that reads one back — the
 * engine only ever returns them masked, and adding a getter here would make the
 * console the weakest link in that chain.
 */
export function connectProvider(projectId, { type, name, config }) {
  return realFetch(`/alert-providers${qs({ projectId })}`, {
    method: "POST",
    auth: true,
    body: { type, name, config },
  });
}

export function disconnectProvider(projectId, id) {
  return realFetch(
    `/alert-providers/${encodeURIComponent(id)}${qs({ projectId })}`,
    { method: "DELETE", auth: true },
  );
}

/**
 * PROVIDER-only server side: an incident carries no tenant label, so there is
 * nothing to scope it by yet. A tenant calling this gets 403 `provider_only`,
 * which the console renders as an explanation rather than an error.
 */
export function listIncidents({ limit } = {}) {
  return realFetch(`/incidents${qs({ limit })}`, { auth: true }).then((rows) =>
    Array.isArray(rows) ? rows : [],
  );
}
