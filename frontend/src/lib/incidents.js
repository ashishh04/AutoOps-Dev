import { realFetch } from "./api";

/**
 * Incidents — what correlation made of the alert feed.
 *
 * Separate module from `alerts.js` because the two are different surfaces that
 * change for different reasons: alerts are a feed you read, incidents are a
 * thing someone owns and works. Both use `realFetch` directly rather than
 * `api.js`'s resource chain, whose fallthrough deliberately throws 501.
 *
 * `projectId` never widens what comes back; it only narrows. Omitting it is the
 * WORKSPACE view, which is how these screens are read by default: the server
 * resolves every monitoring source the TENANT connected, across all its
 * projects, and that is how an incident built from alerts carrying no AutoOps
 * labels is recognised as yours at all.
 *
 * Pass the same value to a list call and the call that opens one of its rows.
 * Listing and opening resolve ownership the same way, so a mismatch is how you
 * get an incident that appears in the list and then 404s when clicked.
 */

const qs = (params) => {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== "") search.set(k, v);
  });
  const s = search.toString();
  return s ? `?${s}` : "";
};

export function listIncidents({ projectId, status, limit } = {}) {
  return realFetch(`/incidents${qs({ projectId, status, limit })}`, {
    auth: true,
  }).then((rows) => (Array.isArray(rows) ? rows : []));
}

export function getIncident(id, projectId) {
  return realFetch(`/incidents/${encodeURIComponent(id)}${qs({ projectId })}`, {
    auth: true,
  });
}

/** Whether this platform has an investigation engine at all. */
export function getIncidentCapabilities() {
  return realFetch("/incidents/capabilities", { auth: true }).catch(() => ({
    investigation: false,
  }));
}

export function setIncidentStatus(id, projectId, status, comment) {
  return realFetch(
    `/incidents/${encodeURIComponent(id)}/status${qs({ projectId })}`,
    { method: "POST", auth: true, body: { status, comment } },
  );
}

export function commentOnIncident(id, projectId, comment) {
  return realFetch(
    `/incidents/${encodeURIComponent(id)}/comment${qs({ projectId })}`,
    { method: "POST", auth: true, body: { comment } },
  );
}

export function assignIncident(id, projectId, user) {
  return realFetch(
    `/incidents/${encodeURIComponent(id)}/assign${qs({ projectId })}`,
    { method: "POST", auth: true, body: { user } },
  );
}

/** Where an investigation has got to, or what it concluded. Read, never re-run. */
export function getInvestigation(id, projectId) {
  return realFetch(
    `/incidents/${encodeURIComponent(id)}/investigation${qs({ projectId })}`,
    { auth: true },
  );
}

/**
 * Starts an investigation. Which engine runs it depends on the estate, and the
 * console does not choose — an AWS incident goes to the analyst that can read
 * CloudTrail, a cluster incident to the engine that can reach the cluster.
 *
 * May answer `running`: the AWS analyst takes minutes, so the response says so
 * and the caller polls `getInvestigation`. Slow and not free either way, so
 * this is only ever triggered by an explicit click, never on load.
 */
export function investigateIncident(id, projectId, { model, question } = {}) {
  return realFetch(
    `/incidents/${encodeURIComponent(id)}/investigate${qs({ projectId })}`,
    { method: "POST", auth: true, body: { model, question } },
  );
}

/**
 * Correlation rules — what decides that several alerts are one incident.
 *
 * Nothing correlates without one. An engine with no rules produces no
 * incidents at all, however many alerts arrive, which is why this is a
 * customer's own screen rather than something they ask their provider for.
 *
 * No expression crosses this boundary in either direction as something to
 * execute. The request carries VALUES — a severity, a service, what to group
 * by — and the server writes the matcher with the caller's own tenant ANDed
 * in. `expression` comes back read-only, because "why are these grouped?" is
 * the first question anyone asks of a correlated view.
 */
export function listCorrelationRules(projectId) {
  return realFetch(`/correlation-rules${qs({ projectId })}`, {
    auth: true,
  }).then((rows) => (Array.isArray(rows) ? rows : []));
}

/** What a rule may be built from. Read from the server, never a copy of it. */
export function getCorrelationVocabulary() {
  return realFetch("/correlation-rules/vocabulary", { auth: true });
}

export function createCorrelationRule(projectId, body) {
  return realFetch(`/correlation-rules${qs({ projectId })}`, {
    method: "POST",
    auth: true,
    body,
  });
}

export function deleteCorrelationRule(id, projectId) {
  return realFetch(
    `/correlation-rules/${encodeURIComponent(id)}${qs({ projectId })}`,
    { method: "DELETE", auth: true },
  );
}
