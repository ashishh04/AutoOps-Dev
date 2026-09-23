
// Base URL for the AutoOps backend. In dev this is "/api" and the Vite proxy
// (vite.config.js) forwards it to the API GATEWAY on http://localhost:8080,
// which validates tokens and routes to auth-service / subscription-service /
// core-service (projects + workflows).
const API_BASE = import.meta.env.VITE_API_URL || "/api";

const ACCESS_KEY = "autoops_access_token";
const REFRESH_KEY = "autoops_refresh_token";

/**
 * Token storage, scoped to ONE TAB.
 *
 * sessionStorage, NOT localStorage. localStorage is a single bucket shared by
 * every tab on the origin, so signing into a second account overwrote the
 * first: both tabs then read whichever token was written last and, on refresh,
 * both showed the same account with the same role. Operators legitimately hold
 * two sessions at once — a provider console in one tab and the customer
 * workspace they are supporting in another — and that pattern is impossible on
 * shared storage, whatever the backend does. The role travels inside the JWT,
 * so one shared token means one shared identity everywhere.
 *
 * Trade-off, accepted deliberately: sessionStorage survives a RELOAD but not
 * closing the tab, so a new tab starts signed out. There is no version of this
 * that keeps both per-tab accounts and a single browser-wide session — and the
 * obvious compromise (localStorage as a seed for new tabs) is worse than it
 * looks: refresh tokens rotate on use, so a new tab seeded with a token another
 * tab had already rotated would replay it and revoke that tab's whole session
 * family. Losing a session on tab close beats logging out the tab next door.
 */
export const tokenStore = {
  get access() {
    return sessionStorage.getItem(ACCESS_KEY);
  },
  get refresh() {
    return sessionStorage.getItem(REFRESH_KEY);
  },
  set(access, refresh) {
    if (access) sessionStorage.setItem(ACCESS_KEY, access);
    if (refresh) sessionStorage.setItem(REFRESH_KEY, refresh);
  },
  clear() {
    sessionStorage.removeItem(ACCESS_KEY);
    sessionStorage.removeItem(REFRESH_KEY);
    // Tokens written by a build that used localStorage. Without this a stale
    // pair outlives the fix, and one of them is a refresh token that still
    // works — so it would keep resurrecting an account the user signed out of.
    localStorage.removeItem(ACCESS_KEY);
    localStorage.removeItem(REFRESH_KEY);
  },
};

/**
 * One-time move of tokens left in localStorage by the shared-storage build.
 *
 * Adopts them into THIS tab and removes the shared copy, so an operator who was
 * signed in before the upgrade stays signed in instead of being bounced to the
 * login screen. The removal is the important half: left in place, the next tab
 * to open would adopt the same pair and the accounts would bleed together
 * again, which is the bug this replaces.
 */
(function adoptLegacyTokens() {
  try {
    const access = localStorage.getItem(ACCESS_KEY);
    const refresh = localStorage.getItem(REFRESH_KEY);
    if (!access && !refresh) return;
    if (!sessionStorage.getItem(ACCESS_KEY) && !sessionStorage.getItem(REFRESH_KEY)) {
      if (access) sessionStorage.setItem(ACCESS_KEY, access);
      if (refresh) sessionStorage.setItem(REFRESH_KEY, refresh);
    }
    localStorage.removeItem(ACCESS_KEY);
    localStorage.removeItem(REFRESH_KEY);
  } catch {
    /* storage disabled (private mode, blocked cookies) — nothing to migrate */
  }
})();

export class ApiError extends Error {
  constructor(message, status, data) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.data = data;
  }
}

const safeJson = (text) => {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
};

// ---------------------------------------------------------------------------
// Real backend calls (auth-service). Everything that is NOT under /auth still
// goes through the offline mock below so the rest of the app keeps rendering.
// ---------------------------------------------------------------------------

// Exported so other API layers reuse one transport —
// single-flight token refresh and the upgrade-required event live here.
export async function realFetch(path, { method = "GET", body, auth = false, _retry = false } = {}) {
  const headers = { "Content-Type": "application/json" };
  if (auth && tokenStore.access) {
    headers["Authorization"] = `Bearer ${tokenStore.access}`;
  }

  const res = await fetch(`${API_BASE}${path}`, {
    method,
    headers,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });

  // One transparent refresh attempt on an expired access token.
  if (res.status === 401 && auth && !_retry && tokenStore.refresh) {
    if (await tryRefresh()) {
      return realFetch(path, { method, body, auth, _retry: true });
    }
  }

  const text = await res.text();
  const data = text ? safeJson(text) : null;
  if (!res.ok) {
    const message =
      (data && (data.message || data.error)) || `Request failed (${res.status})`;
    // Subscription-gate denials get a dedicated upgrade prompt (AppLayout
    // listens) instead of only a raw error toast.
    if (data && UPGRADE_ERROR_CODES.has(data.error)) {
      window.dispatchEvent(
        new CustomEvent("autoops:upgrade-required", {
          detail: { code: data.error, message },
        }),
      );
    }
    throw new ApiError(message, res.status, data);
  }
  return data;
}

// Error codes from the central subscription gate that mean "your plan (or its
// payment state) is the blocker" — surfaced as an upgrade/renew prompt.
const UPGRADE_ERROR_CODES = new Set([
  "feature_not_in_plan",
  "quota_exceeded",
  "trial_expired",
  "subscription_expired",
  "subscription_past_due",
  "subscription_canceled",
  "no_subscription",
]);

// Single-flight: refresh tokens rotate on every use and replaying a rotated
// token revokes the whole session family, so two concurrent 401s must share
// ONE refresh call — a second parallel refresh would log the user out.
let refreshInFlight = null;

function tryRefresh() {
  if (!refreshInFlight) {
    refreshInFlight = doRefresh().finally(() => {
      refreshInFlight = null;
    });
  }
  return refreshInFlight;
}

async function doRefresh() {
  const refreshToken = tokenStore.refresh;
  if (!refreshToken) return false;
  try {
    const res = await fetch(`${API_BASE}/auth/refresh`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ refreshToken }),
    });
    if (!res.ok) {
      tokenStore.clear();
      return false;
    }
    const data = await res.json();
    tokenStore.set(data.accessToken, data.refreshToken);
    return true;
  } catch {
    return false;
  }
}

// Map subscription-service's /subscriptions/current|subscribe|cancel payload
// onto the shape the Billing page expects ({plan, subscription, entitlements}).
function mapSubscription(s) {
  if (!s || s.status === "NONE" || !s.plan) {
    return { plan: null, subscription: { status: "NONE" }, entitlements: {}, limits: {} };
  }
  return {
    plan: s.plan.code,
    price: s.plan.priceMonthly,
    subscription: {
      status: s.status,
      trialEndsAt: s.trialEndsAt,
      currentPeriodStart: s.currentPeriodStart,
      currentPeriodEnd: s.currentPeriodEnd,
      cancelAtPeriodEnd: s.cancelAtPeriodEnd,
    },
    entitlements: Object.fromEntries((s.plan.features || []).map((f) => [f, true])),
    limits: {
      projects: s.plan.maxProjects ?? "Unlimited",
      nodes: s.plan.maxNodes ?? "Unlimited",
      automations: s.plan.maxAutomations ?? "Unlimited",
      jobs: s.plan.maxJobs ?? "Unlimited",
      cloudIntegrations: s.plan.maxCloudIntegrations ?? "Unlimited",
      historyDays: s.plan.historyDays,
    },
  };
}

// "acme-corp-a1b2c3d4" -> "Acme Corp" — fallback for pre-V5 tenants whose
// typed workspace name was never stored.
function prettySlug(slug) {
  const words = String(slug || "")
    .replace(/-[0-9a-f]{8}$/, "")
    .split("-")
    .filter(Boolean);
  if (!words.length) return "My Workspace";
  return words.map((w) => w.charAt(0).toUpperCase() + w.slice(1)).join(" ");
}

// "TEAM" -> "Team" (matches the saasData tiers keys); no subscription -> "Free".
const planTitle = (code) =>
  !code || code === "NONE"
    ? "Free"
    : code.charAt(0).toUpperCase() + code.slice(1).toLowerCase();

// Map the backend's flat /me profile onto the shape the store expects.
function mapProfile(p) {
  const roleUpper = String(p.role || "").toUpperCase();
  const isProvider = roleUpper === "PROVIDER";
  const name = p.fullName || (p.email || "").split("@")[0] || "User";
  const user = { id: p.id, name, email: p.email, isProvider };

  // Real display name from /me (stored at sign-up); prettified slug otherwise.
  const workspaceName =
    p.workspaceName ||
    (p.tenantId && p.tenantId !== "default" ? prettySlug(p.tenantId) : "My Workspace");
  // Plan is a placeholder until getWorkspace() merges the live subscription.
  const workspace = { name: workspaceName, plan: "Free" };
  // The signed-in member's REAL role drives the client-side RBAC. Defaulting
  // everyone to ADMIN handed the admin console (and its capabilities) to
  // operators and viewers, and left a demoted admin looking like an admin
  // while every admin-only call came back 403.
  const activeRole = isProvider
    ? "PROVIDER"
    : roleUpper === "VIEWER"
      ? "VIEWER"
      : roleUpper === "CLIENT"
        ? "OPERATOR"
        : "ADMIN";

  return {
    user,
    workspace,
    activeRole,
    context: isProvider ? "provider" : "client",
    memberships: [{ tenantId: p.tenantId, role: activeRole, tenant: workspace }],
    activeTenantId: p.tenantId,
  };
}

// Exchange a token-issuing call for a full session: store tokens, then load the
// profile and assemble the payload StoreProvider.applySession() consumes.
async function authWithTokens(path, body) {
  const tokens = await realFetch(path, { method: "POST", body });
  tokenStore.set(tokens.accessToken, tokens.refreshToken);
  const profile = await realFetch("/auth/me", { auth: true });
  return {
    accessToken: tokens.accessToken,
    refreshToken: tokens.refreshToken,
    ...mapProfile(profile),
  };
}

// ---------------------------------------------------------------------------
// Real core-service calls (projects + workflows through the gateway).
// Mutations are subscription-gated server-side: a 403 carries the reason code
// (trial_expired, quota_exceeded, ...) and a human message ready for a toast.
// ---------------------------------------------------------------------------

// Map core-service's ProjectResponse onto the shape the store/pages expect.
function mapProject(p) {
  return {
    id: p.id,
    name: p.name,
    description: p.description,
    status: String(p.status || "").toLowerCase(), // UI compares "active"
    nodes: 0, // ops metadata — no execution engine yet
    jobs: 0,
    createdBy: p.createdBy,
    createdAt: p.createdAt,
    updatedAt: p.updatedAt,
  };
}

// Map WorkflowResponse (canvas JSON lives in `definition`) onto the designer/
// list shape ({active, nodes, edges, state, steps, ...}).
function mapWorkflow(w) {
  let def = {};
  try {
    def = JSON.parse(w.definition) || {};
  } catch {
    /* legacy/blank definition */
  }
  return {
    id: w.id,
    projectId: w.projectId,
    name: w.name,
    active: w.enabled,
    state: w.enabled ? "active" : "paused",
    // Complex workflows (server-side rule) need admin approval to run.
    requiresApproval: !!w.requiresApproval,
    // Sealed (provider-authored) workflows arrive with definition = null, so
    // these are empty by design, not by failure. `steps` still shows the real
    // size because the server sends nodeCount separately — a customer sees HOW
    // BIG the automation is without seeing WHAT it does.
    nodes: def.nodes || [],
    edges: def.edges || [],
    steps: w.nodeCount ?? (def.nodes || []).length,
    category: def.category || "Automation",
    validation: "valid",
    // PROVIDER = designed by the platform and rolled out to this workspace.
    origin: w.origin || "TENANT",
    providerManaged: w.origin === "PROVIDER",
    // The server's own answer to "may this caller change it?" — never inferred
    // client-side, so the button and the API agree.
    editable: w.editable !== false,
    // Live run stats. Null until the first run, and deliberately left null:
    // defaulting to 100 showed a perfect record for something that had never
    // executed, which is the one number a customer judges trust from.
    successRate: w.successRate ?? null,
    lastRunAt: w.lastRunAt ?? null,
    runsTotal: w.runsTotal ?? 0,
    // Live, from the server: true while a run is QUEUED or RUNNING,
    // whoever started it — an agent, a schedule, the API or the Run
    // button. Local click state could only ever know about the last.
    running: w.running === true,
    createdAt: w.createdAt,
    updatedAt: w.updatedAt,
  };
}

// The backend stores the canvas as one JSON document; node count is re-parsed
// server-side from `nodes` and gated by the plan's MAX_NODES.
const workflowDefinition = (body) =>
  JSON.stringify({
    nodes: body.nodes || [],
    edges: body.edges || [],
    category: body.category || "Automation",
  });

async function listWorkflowsReal(projectId) {
  if (projectId) {
    const rows = await realFetch(`/projects/${projectId}/workflows`, { auth: true });
    return (rows || []).map(mapWorkflow);
  }
  // No project scope (read-only viewer): aggregate across active projects.
  const projects = (await realFetch("/projects", { auth: true })) || [];
  const lists = await Promise.all(
    projects
      .filter((p) => p.status === "ACTIVE")
      .map((p) =>
        realFetch(`/projects/${p.id}/workflows`, { auth: true }).catch(() => []),
      ),
  );
  return lists.flat().map(mapWorkflow);
}

async function createWorkflowReal(body) {
  if (!body.projectId) {
    throw new ApiError("A project is required to create a workflow", 400, null);
  }
  const created = await realFetch(`/projects/${body.projectId}/workflows`, {
    method: "POST",
    auth: true,
    body: { name: body.name, definition: workflowDefinition(body) },
  });
  if (body.active === false) {
    return mapWorkflow(
      await realFetch(`/workflows/${created.id}/disable`, { method: "POST", auth: true }),
    );
  }
  return mapWorkflow(created);
}

async function updateWorkflowReal(id, body) {
  const payload = { name: body.name };
  if (body.nodes !== undefined || body.edges !== undefined) {
    payload.definition = workflowDefinition(body);
  }
  let updated = await realFetch(`/workflows/${id}`, {
    method: "PUT",
    auth: true,
    body: payload,
  });
  if (body.active !== undefined && body.active !== updated.enabled) {
    updated = await realFetch(`/workflows/${id}/${body.active ? "enable" : "disable"}`, {
      method: "POST",
      auth: true,
    });
  }
  return mapWorkflow(updated);
}

// Map JobResponse (steps JSON in `definition`) onto the jobs-page shape.
// Run stats come from the execution engine (null until the first run).
function mapJob(j) {
  let def = {};
  try {
    def = JSON.parse(j.definition) || {};
  } catch {
    /* legacy/blank definition */
  }
  return {
    id: j.id,
    projectId: j.projectId,
    name: j.name,
    group: j.group || "Ungrouped",
    description: j.description || "",
    schedule: j.schedule || "",
    steps: def.steps || [],
    stepCount: j.stepCount ?? (def.steps || []).length,
    // Everything the job editor authors beyond the step list. Each block
    // defaults rather than coming back undefined, so a job saved before these
    // existed opens in the editor with sane values instead of blank controls.
    options: def.options || [],
    workflow: { strategy: "node-first", keepgoing: false, ...(def.workflow || {}) },
    nodes: {
      dispatch: false,
      filter: "",
      threadcount: 1,
      keepgoing: false,
      rankAttribute: "",
      rankOrder: "ascending",
      ...(def.nodeDispatch || {}),
    },
    notifications: def.notifications || [],
    execution: {
      timeoutSeconds: null,
      retries: 0,
      retryDelaySeconds: 0,
      logLimit: null,
      logLimitAction: "halt",
      multipleExecutions: false,
      logLevel: "INFO",
      ...(def.execution || {}),
    },
    logFilters: def.logFilters || [],
    enabled: j.enabled,
    requiresApproval: !!j.requiresApproval,
    status: j.enabled ? (j.schedule ? "scheduled" : "active") : "paused",
    lastRunAt: j.lastRunAt ?? null,
    runsTotal: j.runsTotal ?? 0,
    avgDurationMs: j.avgDurationMs ?? 0,
    successRate: j.successRate ?? null,
    nextRunAt: j.nextRunAt ?? null,
    createdAt: j.createdAt,
    updatedAt: j.updatedAt,
  };
}

// The job definition is ONE JSON document: the step list plus every authoring
// block the editor exposes. Blocks are omitted when empty rather than written
// as nulls, so a simple job's definition stays as small and readable as it was
// before any of this existed.
const jobDefinition = (body) => {
  const def = { steps: body.steps || [] };
  if (body.options?.length) def.options = body.options;
  if (body.workflow) def.workflow = body.workflow;
  // `dispatch: false` is the default and means "run on the engine", exactly as
  // every job did before node dispatch existed — so it is not worth persisting.
  // `nodeDispatch`, NOT `nodes`: a WORKFLOW definition already uses `nodes` for
  // its step array, and core-service's parser keys off that name. Reusing it
  // here would make a job's dispatch config look like a workflow's step list.
  if (body.nodes?.dispatch) def.nodeDispatch = body.nodes;
  if (body.notifications?.length) def.notifications = body.notifications;
  if (body.execution) def.execution = body.execution;
  if (body.logFilters?.length) def.logFilters = body.logFilters;
  return JSON.stringify(def);
};

async function listJobsReal(projectId) {
  if (projectId) {
    const rows = await realFetch(`/projects/${projectId}/jobs`, { auth: true });
    return (rows || []).map(mapJob);
  }
  // No project scope: aggregate across active projects.
  const projects = (await realFetch("/projects", { auth: true })) || [];
  const lists = await Promise.all(
    projects
      .filter((p) => p.status === "ACTIVE")
      .map((p) => realFetch(`/projects/${p.id}/jobs`, { auth: true }).catch(() => [])),
  );
  return lists.flat().map(mapJob);
}

async function createJobReal(body) {
  if (!body.projectId) {
    throw new ApiError("A project is required to create a job", 400, null);
  }
  const created = await realFetch(`/projects/${body.projectId}/jobs`, {
    method: "POST",
    auth: true,
    body: {
      name: body.name,
      group: body.group,
      description: body.description,
      definition: jobDefinition(body),
      schedule: body.schedule,
      requiresApproval: body.requiresApproval,
    },
  });
  return mapJob(created);
}

async function updateJobReal(id, body) {
  const payload = {
    name: body.name,
    group: body.group,
    description: body.description,
    schedule: body.schedule,
    requiresApproval: body.requiresApproval,
  };
  if (body.steps !== undefined) {
    payload.definition = jobDefinition(body);
  }
  return mapJob(
    await realFetch(`/jobs/${id}`, { method: "PUT", auth: true, body: payload }),
  );
}

// ---- agents (real: core-service) ----
// An agent is a persona (instructions) plus its TOOLS allow-list — the
// automations it may operate. The backend resolves each entry to a name and
// says whether the target still exists, so the page never guesses.
function mapAgent(a) {
  return {
    id: a.id,
    projectId: a.projectId,
    name: a.name,
    description: a.description || "",
    model: a.model || "",
    instructions: a.instructions || "",
    tools: (a.tools || []).map((t) => ({
      type: String(t.type || "").toLowerCase(), // job | workflow
      id: t.id,
      name: t.name,
      available: t.available !== false,
      // Carried so an edit can send back what it was given. Undefined means
      // the grant never declared it, which is NOT the same as false: the
      // backend fails closed on an undeclared tool, and inventing `false`
      // here would quietly hand a state-changing automation to the phase
      // that gathers evidence.
      mutating: typeof t.mutating === "boolean" ? t.mutating : undefined,
    })),
    toolCount: a.toolCount ?? (a.tools || []).length,
    enabled: !!a.enabled,
    status: a.enabled ? "active" : "paused",
    // PROVIDER = built by the platform and rolled out here. `instructions`
    // comes back empty for these on purpose — the persona is withheld, while
    // the tool allow-list above is always disclosed.
    origin: a.origin || "TENANT",
    providerManaged: a.origin === "PROVIDER",
    editable: a.editable !== false,
    createdBy: a.createdBy || "",
    createdAt: a.createdAt,
    updatedAt: a.updatedAt,
  };
}

// The allow-list travels as JSON: [{"type":"JOB","id":7}].
const agentTools = (tools) =>
  JSON.stringify(
    (tools || []).map((t) => {
      const entry = { type: String(t.type || "").toUpperCase(), id: Number(t.id) };
      // Only when known. Saving an agent must not invent a value the grant
      // never had, and must not drop one it did — resending {type, id} alone
      // is what wiped the flag on every rolled-out agent someone edited.
      if (typeof t.mutating === "boolean") {
        entry.mutating = t.mutating;
      }
      return entry;
    }),
  );

async function listAgentsReal(projectId) {
  if (projectId) {
    const rows = await realFetch(`/projects/${projectId}/agents`, { auth: true });
    return (rows || []).map(mapAgent);
  }
  // No project scope: aggregate across active projects.
  const projects = (await realFetch("/projects", { auth: true })) || [];
  const lists = await Promise.all(
    projects
      .filter((p) => p.status === "ACTIVE")
      .map((p) => realFetch(`/projects/${p.id}/agents`, { auth: true }).catch(() => [])),
  );
  return lists.flat().map(mapAgent);
}

async function createAgentReal(body) {
  if (!body.projectId) {
    throw new ApiError("A project is required to create an agent", 400, null);
  }
  const created = await realFetch(`/projects/${body.projectId}/agents`, {
    method: "POST",
    auth: true,
    body: {
      name: body.name,
      description: body.description,
      model: body.model,
      instructions: body.instructions,
      tools: agentTools(body.tools),
    },
  });
  return mapAgent(created);
}

// Partial by design: omit `tools` to save the persona without resending the
// allow-list (the backend leaves a null field untouched).
async function updateAgentReal(id, body) {
  const payload = {
    name: body.name,
    description: body.description,
    model: body.model,
    instructions: body.instructions,
  };
  if (body.tools !== undefined) {
    payload.tools = agentTools(body.tools);
  }
  return mapAgent(
    await realFetch(`/agents/${id}`, { method: "PUT", auth: true, body: payload }),
  );
}

/**
 * Point an agent at a different model.
 *
 * Its own endpoint rather than a field on the update above, because it is the
 * one change a customer may make to a provider-managed agent: the persona and
 * the tool allow-list stay sealed, but which vendor processes their data is
 * theirs to choose. PUT /agents/{id} is refused on a managed agent; this is not.
 */
async function setAgentModel(id, model) {
  return mapAgent(
    await realFetch(`/agents/${id}/model`, {
      method: "POST",
      auth: true,
      body: { model },
    }),
  );
}

// ---- agent runs (real: agent-service) ----
// One execution of an agent: the question, every model call and tool call it
// made, and the answer. Runs are ASYNC — POST returns 202 with the row in
// PENDING and the page polls until it reaches a terminal status.
//
// AWAITING_APPROVAL is not terminal and has no button here on purpose. An
// agent's approval is an ordinary approval in the ordinary inbox, decided on
// the Approvals page; the run resumes on its own once someone decides.
function mapAgentRun(r) {
  return {
    id: r.id,
    agentId: r.agentId,
    projectId: r.projectId,
    status: r.status, // PENDING | RUNNING | AWAITING_APPROVAL | SUCCEEDED | FAILED | CANCELLED
    running: r.status === "PENDING" || r.status === "RUNNING",
    waiting: r.status === "AWAITING_APPROVAL",
    finished: ["SUCCEEDED", "FAILED", "CANCELLED"].includes(r.status),
    input: r.input || "",
    output: r.output || "",
    error: r.error || "",
    model: r.model || "",
    vendor: r.vendor || "",
    stepCount: r.stepCount ?? 0,
    maxSteps: r.maxSteps ?? 0,
    approvalReference: r.approvalReference || null,
    promptTokens: r.promptTokens ?? 0,
    completionTokens: r.completionTokens ?? 0,
    createdBy: r.createdBy || "",
    createdAt: r.createdAt,
    startedAt: r.startedAt,
    finishedAt: r.finishedAt,
    // Absent on list rows by design — a list of 100 runs does not carry
    // every transcript step. null means "not loaded", [] means "none yet".
    steps: r.steps ? r.steps.map(mapAgentRunStep) : null,
  };
}

function mapAgentRunStep(s) {
  return {
    id: s.id,
    seq: s.seq,
    kind: s.kind, // MODEL_CALL | TOOL_CALL | TOOL_RESULT | APPROVAL_REQUESTED | APPROVAL_GRANTED
    toolType: s.toolType ? String(s.toolType).toLowerCase() : null,
    toolTargetId: s.toolTargetId,
    toolName: s.toolName || "",
    request: s.request || "",
    response: s.response || "",
    isError: !!s.isError,
    durationMs: s.durationMs,
    createdAt: s.createdAt,
  };
}

export const agentRuns = {
  start: (agentId, input) =>
    realFetch(`/agents/${agentId}/runs`, {
      method: "POST",
      auth: true,
      body: { input },
    }).then(mapAgentRun),

  listForAgent: (agentId) =>
    realFetch(`/agents/${agentId}/runs`, { auth: true }).then((rows) =>
      (rows || []).map(mapAgentRun),
    ),

  get: (runId) => realFetch(`/agent-runs/${runId}`, { auth: true }).then(mapAgentRun),

  cancel: (runId) =>
    realFetch(`/agent-runs/${runId}/cancel`, { method: "POST", auth: true }).then(mapAgentRun),
};

// ---- approvals (real: core-service) ----
// A requires_approval job — or a COMPLEX workflow (server-side rule: >= 5
// nodes or infra-grade node types) — run by a non-admin queues a PENDING
// approval instead of a run; the run trigger response carries
// approvalRequired: true. Only admins can approve (starts the run) or reject.
function mapApproval(a) {
  return {
    id: a.id,
    projectId: String(a.projectId),
    targetType: String(a.targetType || "JOB").toLowerCase(), // job | workflow
    targetId: String(a.targetId),
    target: a.targetName,
    requestedBy: a.requestedBy,
    status: String(a.status || "").toLowerCase(), // pending | approved | rejected
    decidedBy: a.decidedBy || null,
    decidedAt: a.decidedAt || null,
    runId: a.runId != null ? String(a.runId) : null,
    createdAt: a.createdAt,
  };
}

async function listApprovalsReal(projectId) {
  const qsPart = projectId ? `?projectId=${encodeURIComponent(projectId)}` : "";
  const rows = await realFetch(`/approvals${qsPart}`, { auth: true });
  return (rows || []).map(mapApproval);
}

// ---- schedules (real: core-service) ----
// Schedules are not a separate backend entity: a schedule IS a job with a
// cron expression (jobs.schedule + schedule_timezone + next_run_at, fired by
// JobScheduler). The schedules "collection" is therefore a view over jobs:
// create/update set a job's cron, remove clears it (the job itself survives).
//
// The cron is a LOCAL-TIME rule read in the job's own zone; `next` is the
// absolute instant it resolves to, so it renders correctly in any viewer's
// locale regardless of which zone the job is scheduled in.
function mapSchedule(job) {
  return {
    id: job.id,
    projectId: job.projectId,
    jobId: String(job.id),
    job: job.name,
    cron: job.schedule,
    next: job.nextRunAt ?? null,
    tz: job.scheduleTimezone || "UTC",
    status: job.enabled ? "enabled" : "disabled",
  };
}

async function listSchedulesReal(projectId) {
  const jobs = await listJobsReal(projectId);
  return jobs.filter((j) => j.schedule).map(mapSchedule);
}

async function setJobScheduleReal(jobId, cron, timezone) {
  // Backend PUT validates the whole JobRequest (name @NotBlank), so merge from
  // the current job. schedule: "" clears it; non-blank must be a valid cron.
  // Omitting scheduleTimezone leaves the job's existing zone untouched, so
  // editing only the cron never silently resets a job back to UTC.
  const current = await realFetch(`/jobs/${jobId}`, { auth: true });
  const updated = await realFetch(`/jobs/${jobId}`, {
    method: "PUT",
    auth: true,
    body: {
      name: current.name,
      group: current.group,
      description: current.description,
      definition: current.definition,
      schedule: cron ?? "",
      ...(timezone ? { scheduleTimezone: timezone } : {}),
    },
  });
  return mapSchedule(mapJob(updated));
}

async function createScheduleReal(body) {
  if (!body.jobId) {
    throw new ApiError("Pick a job to schedule", 400, null);
  }
  return setJobScheduleReal(body.jobId, body.cron, body.tz);
}

// Map RunResponse (core-service execution engine) onto the executions-page
// shape. The pages type a row by the presence of `workflow`/`job` and read
// lowercase statuses ("success"/"failed"/"running"/...).
const RUN_STATUS = {
  QUEUED: "queued",
  RUNNING: "running",
  SUCCEEDED: "success",
  FAILED: "failed",
  CANCELED: "cancelled",
};

function mapRun(r) {
  return {
    id: r.id,
    projectId: r.projectId,
    name: r.name,
    workflow: r.targetType === "WORKFLOW" ? r.name : undefined,
    job: r.targetType === "JOB" ? r.name : undefined,
    // Stringified: pages compare against useParams() route ids (strings).
    jobId: r.targetType === "JOB" ? String(r.targetId) : undefined,
    workflowId: r.targetType === "WORKFLOW" ? String(r.targetId) : undefined,
    status: RUN_STATUS[r.status] || String(r.status || "").toLowerCase(),
    trigger: String(r.trigger || "manual").toLowerCase(),
    by: r.triggeredBy,
    stepTotal: r.stepTotal,
    stepCompleted: r.stepCompleted,
    durationMs: r.durationMs ?? null,
    startedAt: r.startedAt,
    finishedAt: r.finishedAt,
    createdAt: r.createdAt,
    // What the run DID (the engine's trace) and what it PRODUCED (the
    // deliverable) are separate fields now. They shared one, and a customer
    // who asked for a meeting summary got node timings and an echo of their
    // own transcript above it.
    log: r.log,
    output: r.output,
    error: r.error,
  };
}

// filter {targetType, targetId} scopes the page to ONE job's or workflow's
// history SERVER-side. That matters because the endpoint returns the newest
// 200 runs: narrowing the project-wide page in the browser would drop a quiet
// job's runs as soon as a noisier one filled the cap.
async function listRunsReal(projectId, filter) {
  const scope =
    filter && filter.targetType && filter.targetId
      ? `?targetType=${encodeURIComponent(filter.targetType)}` +
        `&targetId=${encodeURIComponent(filter.targetId)}`
      : "";
  if (projectId) {
    const rows = await realFetch(`/projects/${projectId}/runs${scope}`, { auth: true });
    return (rows || []).map(mapRun);
  }
  // No project scope: aggregate across active projects.
  const projects = (await realFetch("/projects", { auth: true })) || [];
  const lists = await Promise.all(
    projects
      .filter((p) => p.status === "ACTIVE")
      .map((p) => realFetch(`/projects/${p.id}/runs`, { auth: true }).catch(() => [])),
  );
  return lists
    .flat()
    .map(mapRun)
    .sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt));
}

// Members: the UI's Admin/Operator/Viewer vocabulary maps 1:1 onto the
// backend roles ADMIN/CLIENT/VIEWER. Keep it lossless — collapsing Viewer
// onto CLIENT silently stored the wrong role and read it back as "Operator".
const memberRoleToBackend = (role) => {
  const r = String(role || "").toUpperCase();
  if (r === "ADMIN") return "ADMIN";
  if (r === "VIEWER") return "VIEWER";
  return "CLIENT";
};
const memberRoleToUi = (role) => {
  const r = String(role || "").toUpperCase();
  if (r === "ADMIN" || r === "PROVIDER") return "ADMIN";
  if (r === "VIEWER") return "VIEWER";
  return "OPERATOR";
};

// Map auth-service's UserProfileResponse onto the membership row the store maps.
function mapMemberRow(u) {
  return {
    id: u.id,
    user: {
      id: u.id,
      name: u.fullName || (u.email || "").split("@")[0],
      email: u.email,
    },
    role: memberRoleToUi(u.role),
    status: u.status,
  };
}

async function listMembersReal() {
  const rows = (await realFetch("/auth/users", { auth: true })) || [];
  return rows.map(mapMemberRow);
}

// Core-service audit trail (AUDIT_LOG plan feature, Team+; a 403
// feature_not_in_plan propagates so the page can show the upgrade prompt).
async function listAuditReal(projectId) {
  const rows =
    (await realFetch(`/audit${projectId ? `?projectId=${projectId}` : ""}`, {
      auth: true,
    })) || [];
  return rows.map((e) => ({
    id: e.id,
    time: e.createdAt ? new Date(e.createdAt).toLocaleString() : "",
    actor: e.actor || "system",
    action: e.eventType,
    resource: [e.targetType, e.targetName].filter(Boolean).join(": "),
    detail: e.detail || "",
  }));
}

async function createMemberReal(body) {
  // Onboard creates the member ACTIVE; auth-service emails an invite ("sign
  // in with a one-time code") when SendGrid is configured.
  const created = await realFetch("/auth/onboard", {
    method: "POST",
    auth: true,
    body: {
      email: body.email,
      fullName: body.name,
      role: memberRoleToBackend(body.role),
    },
  });
  return mapMemberRow(created);
}

async function updateMemberRoleReal(id, body) {
  const updated = await realFetch(`/auth/users/${id}/role`, {
    method: "PATCH",
    auth: true,
    body: { role: memberRoleToBackend(body.role) },
  });
  return mapMemberRow(updated);
}

// Secret vault metadata — values are write-only and never come back.
const mapSecret = (s) => ({
  id: s.id,
  path: s.path,
  type: s.type
    ? s.type.charAt(0) + s.type.slice(1).toLowerCase()
    : "Opaque",
  updated: s.updatedAt || s.createdAt
    ? new Date(s.updatedAt || s.createdAt).toLocaleString()
    : "—",
  createdBy: s.createdBy || "",
});

async function listSecretsReal() {
  const rows = (await realFetch("/secrets", { auth: true })) || [];
  return rows.map(mapSecret);
}

// Inbound webhooks — url is the REAL trigger endpoint minted by the backend.
const mapWebhook = (w) => ({
  id: w.id,
  projectId: w.projectId,
  name: w.name,
  url: w.url,
  targetType: w.targetType,
  targetId: w.targetId,
  events: `${String(w.targetType || "JOB").toLowerCase()} #${w.targetId}`,
  last: w.lastFiredAt
    ? `${new Date(w.lastFiredAt).toLocaleString()} (${w.lastStatus || ""})`
    : "never",
  status: w.enabled ? "active" : "paused",
});

async function listWebhooksReal() {
  const rows = (await realFetch("/webhooks", { auth: true })) || [];
  return rows.map(mapWebhook);
}

// Node registry (core-service) — RUNNER status is the real runtime health.
const mapNode = (n) => ({
  id: n.id,
  name: n.name,
  type: n.type,
  region: n.region || "",
  status: n.status, // online | offline | registered
  createdAt: n.createdAt,
});

async function listNodesReal(projectId) {
  if (!projectId) {
    // No project scope (tenant-wide dashboard): aggregate across active
    // projects, same as listRunsReal.
    const projects = (await realFetch("/projects", { auth: true })) || [];
    const lists = await Promise.all(
      projects
        .filter((p) => p.status === "ACTIVE")
        .map((p) =>
          realFetch(`/projects/${p.id}/nodes`, { auth: true }).catch(() => []),
        ),
    );
    return lists.flat().map(mapNode);
  }
  const rows =
    (await realFetch(`/projects/${projectId}/nodes`, { auth: true })) || [];
  return rows.map(mapNode);
}

async function createNodeReal(body) {
  const created = await realFetch(`/projects/${body.projectId}/nodes`, {
    method: "POST",
    auth: true,
    body: { name: body.name, type: body.type, region: body.region },
  });
  return mapNode(created);
}

async function updateNodeReal(id, body) {
  const updated = await realFetch(`/nodes/${id}`, {
    method: "PUT",
    auth: true,
    body: { name: body.name, type: body.type, region: body.region },
  });
  return mapNode(updated);
}

// Compliance report summary → the shape ComplianceReports.jsx renders.
function mapComplianceReport(r) {
  return {
    id: r.id,
    name: `${r.frameworkLabel} report`,
    framework: r.frameworkLabel,
    frameworkCode: r.framework,
    status: r.status === "COMPLIANT" ? "success" : "failed",
    compliant: r.status === "COMPLIANT",
    score: r.score,
    controlsTotal: r.controlsTotal,
    passed: r.passed,
    warnings: r.warnings,
    failed: r.failed,
    generatedBy: r.generatedBy,
    generated: r.createdAt ? new Date(r.createdAt).toLocaleString() : "",
  };
}

// ---------------------------------------------------------------------------
// Former mock transport — every surviving surface is backed by a real
// service now. This stub exists only so an unbranched resource name fails
// LOUDLY instead of silently returning fabricated data.
// ---------------------------------------------------------------------------

export async function apiFetch(path, options = {}) {
  const { method = "GET" } = options;
  throw new ApiError(
    `No backend implements ${method} ${path} — this feature has no API yet`,
    501,
    { error: "not_implemented" },
  );
}

const qs = (projectId) =>
  projectId ? `?projectId=${encodeURIComponent(projectId)}` : "";

export const api = {
  // ---- auth (real backend: auth-service) ----
  login: (email, password) => authWithTokens("/auth/login", { email, password }),
  // Two-step sign-up: register emails a verification code (202, no tokens);
  // verifyRegistration confirms it, activates the account, and signs in.
  register: async (payload) => {
    const res = await realFetch("/auth/register", {
      method: "POST",
      body: {
        email: payload.email,
        password: payload.password,
        fullName: payload.name,
        workspaceName: payload.workspaceName,
      },
    });
    return { verificationRequired: true, email: (res && res.email) || payload.email };
  },
  verifyRegistration: (email, code) =>
    authWithTokens("/auth/register/verify", { email, otp: code }),
  resendRegistrationCode: (email) =>
    realFetch("/auth/register/resend", { method: "POST", body: { email } }),
  forgotPassword: (email) =>
    realFetch("/auth/password/forgot", { method: "POST", body: { email } }),
  resetPassword: (email, code, newPassword) =>
    authWithTokens("/auth/password/reset", { email, otp: code, newPassword }),
  changePassword: async (currentPassword, newPassword) => {
    // Bearer-authenticated; rotates every session and returns a fresh pair.
    const tokens = await realFetch("/auth/password/change", {
      method: "POST",
      auth: true,
      body: { currentPassword, newPassword },
    });
    tokenStore.set(tokens.accessToken, tokens.refreshToken);
    return tokens;
  },
  requestOtp: (email) =>
    realFetch("/auth/otp/generate", { method: "POST", body: { email } }),
  verifyOtp: (email, code) =>
    authWithTokens("/auth/otp/verify", { email, otp: code }),
  me: async () => {
    const profile = await realFetch("/auth/me", { auth: true });
    const mapped = mapProfile(profile);
    return {
      user: mapped.user,
      memberships: mapped.memberships,
      activeTenantId: mapped.activeTenantId,
    };
  },
  logout: () =>
    realFetch("/auth/logout", {
      method: "POST",
      body: { refreshToken: tokenStore.refresh },
    }),

  // selectTenant is not backed by the auth-service yet; return the current session.
  selectTenant: async () => {
    const profile = await realFetch("/auth/me", { auth: true });
    return mapProfile(profile);
  },

  // ---- projects (real backend: core-service via the gateway) ----
  listProjects: async () => {
    const rows = (await realFetch("/projects", { auth: true })) || [];
    // Archived projects free their plan slot and drop out of the workspace.
    return rows.filter((p) => p.status === "ACTIVE").map(mapProject);
  },
  getProject: async (id) => mapProject(await realFetch(`/projects/${id}`, { auth: true })),
  createProject: async (body) =>
    mapProject(
      await realFetch("/projects", {
        method: "POST",
        auth: true,
        body: { name: body.name, description: body.description },
      }),
    ),
  updateProject: async (id, body) => {
    // Backend PUT requires a name; merge from the current project on partial patches.
    let payload = { name: body.name, description: body.description };
    if (!payload.name) {
      const current = await realFetch(`/projects/${id}`, { auth: true });
      payload = {
        name: current.name,
        description: body.description ?? current.description,
      };
    }
    return mapProject(
      await realFetch(`/projects/${id}`, { method: "PUT", auth: true, body: payload }),
    );
  },
  // "Delete" archives: the data survives, the MAX_PROJECTS slot is freed.
  deleteProject: (id) =>
    realFetch(`/projects/${id}/archive`, { method: "POST", auth: true }),

  // ---- generic project-scoped resources ----
  // "workflows", "agents", "jobs", "executions", "schedules", "approvals"
  // (core-service) and "members" (auth-service) are real. "nodes" is real too;
  // called without a projectId it aggregates across the tenant's projects.
  list: (resource, projectId, filter) =>
    resource === "workflows"
      ? listWorkflowsReal(projectId)
      : resource === "jobs"
        ? listJobsReal(projectId)
        : resource === "executions"
          ? listRunsReal(projectId, filter)
          : resource === "schedules"
            ? listSchedulesReal(projectId)
            : resource === "approvals"
              ? listApprovalsReal(projectId)
              : resource === "members"
                ? listMembersReal()
                : resource === "audit"
                  ? listAuditReal(projectId)
                  : resource === "nodes"
                    ? listNodesReal(projectId)
                    : resource === "secrets"
                      ? listSecretsReal()
                      : resource === "webhooks"
                        ? listWebhooksReal()
                        : resource === "agents"
                          ? listAgentsReal(projectId)
                          : apiFetch(`/${resource}${qs(projectId)}`),
  get: (resource, id) =>
    resource === "workflows"
      ? realFetch(`/workflows/${id}`, { auth: true }).then(mapWorkflow)
      : resource === "jobs"
        ? realFetch(`/jobs/${id}`, { auth: true }).then(mapJob)
        : resource === "executions"
          ? realFetch(`/runs/${id}`, { auth: true }).then(mapRun)
          : resource === "agents"
            ? realFetch(`/agents/${id}`, { auth: true }).then(mapAgent)
            : apiFetch(`/${resource}/${id}`),
  create: (resource, body) =>
    resource === "workflows"
      ? createWorkflowReal(body)
      : resource === "jobs"
        ? createJobReal(body)
        : resource === "schedules"
          ? createScheduleReal(body)
          : resource === "members"
            ? createMemberReal(body)
            : resource === "nodes"
              ? createNodeReal(body)
              : resource === "secrets"
                ? realFetch("/secrets", { method: "POST", auth: true, body }).then(mapSecret)
                : resource === "webhooks"
                  ? realFetch("/webhooks", { method: "POST", auth: true, body }).then(mapWebhook)
                  : resource === "agents"
                    ? createAgentReal(body)
                    : apiFetch(`/${resource}`, { method: "POST", body }),
  update: (resource, id, body) =>
    resource === "workflows"
      ? updateWorkflowReal(id, body)
      : resource === "jobs"
        ? updateJobReal(id, body)
        : resource === "schedules"
          ? setJobScheduleReal(id, body.cron, body.tz)
          : resource === "members"
            ? updateMemberRoleReal(id, body)
            : resource === "nodes"
              ? updateNodeReal(id, body)
              : resource === "secrets"
                ? realFetch(`/secrets/${id}`, { method: "PUT", auth: true, body }).then(mapSecret)
                : resource === "webhooks"
                  ? realFetch(`/webhooks/${id}`, { method: "PUT", auth: true, body }).then(mapWebhook)
                  : resource === "agents"
                    ? updateAgentReal(id, body)
                    : apiFetch(`/${resource}/${id}`, { method: "PATCH", body }),
  remove: (resource, id) =>
    resource === "workflows"
      ? realFetch(`/workflows/${id}`, { method: "DELETE", auth: true })
      : resource === "jobs"
        ? realFetch(`/jobs/${id}`, { method: "DELETE", auth: true })
        : resource === "schedules"
          ? setJobScheduleReal(id, "")
          : resource === "members"
            ? realFetch(`/auth/offboard/${id}`, { method: "POST", auth: true })
            : resource === "nodes"
              ? realFetch(`/nodes/${id}`, { method: "DELETE", auth: true })
              : resource === "secrets"
                ? realFetch(`/secrets/${id}`, { method: "DELETE", auth: true })
                : resource === "webhooks"
                  ? realFetch(`/webhooks/${id}`, { method: "DELETE", auth: true })
                  : resource === "agents"
                    ? realFetch(`/agents/${id}`, { method: "DELETE", auth: true })
                    : apiFetch(`/${resource}/${id}`, { method: "DELETE" }),

  // Kill switch for an agent — a disabled agent may not act at all.
  // Pausing a rolled-out workflow is the customer's call, so this is a
  // dedicated call rather than a field on update() — update is refused on
  // provider-managed rows, enable/disable is not.
  setWorkflowEnabled: async (id, enabled) => {
    const updated = await realFetch(`/workflows/${id}/${enabled ? "enable" : "disable"}`, {
      method: "POST",
      auth: true,
    });
    return mapWorkflow(updated);
  },
  setAgentEnabled: (id, enabled) =>
    realFetch(`/agents/${id}/${enabled ? "enable" : "disable"}`, {
      method: "POST",
      auth: true,
    }).then(mapAgent),

  // ---- actions (real: core-service execution engine) ----
  // Triggers are subscription-gated; a 403 carries the reason for the toast.
  // A requires_approval job run by a non-admin returns {approvalRequired,
  // approval} instead of a run — callers key off approvalRequired.
  runJob: (id) =>
    realFetch(`/jobs/${id}/run`, { method: "POST", auth: true }).then((res) =>
      res && res.approvalRequired
        ? { approvalRequired: true, approval: mapApproval(res.approval) }
        : mapRun(res),
    ),
  approveApproval: (id) =>
    realFetch(`/approvals/${id}/approve`, { method: "POST", auth: true }).then(mapApproval),
  rejectApproval: (id) =>
    realFetch(`/approvals/${id}/reject`, { method: "POST", auth: true }).then(mapApproval),
  // ---- scm (real: core-service, per-project git sync via JGit) ----
  // Config PUT is admin-only; token omitted on update keeps the stored one,
  // clearToken:true drops it (for a repo that needs no credentials).
  getScmConfig: (pid) => realFetch(`/projects/${pid}/scm`, { auth: true }),
  saveScmConfig: (pid, body) =>
    realFetch(`/projects/${pid}/scm`, { method: "PUT", auth: true, body }),
  scmExport: (pid) =>
    realFetch(`/projects/${pid}/scm/export`, { method: "POST", auth: true }),
  scmImport: (pid, strategy) =>
    realFetch(`/projects/${pid}/scm/import`, {
      method: "POST",
      auth: true,
      body: { strategy },
    }),

  // ---- access control (real: auth-service) ----
  // Fixed platform role catalog + live member counts + the permission matrix
  // as actually enforced across the services. Any member may view.
  listRbacRoles: () => realFetch("/auth/roles", { auth: true }),

  // ---- governance (real: core-service) ----
  // Summary is a read (never gated): live policy states, violations computed
  // from real data, quota usage vs plan limits. Policy mode changes are
  // admin-only and GOVERNANCE-feature-gated (403 carries the reason).
  getGovernanceSummary: () => realFetch("/governance/summary", { auth: true }),
  updateGovernancePolicy: (code, mode) =>
    realFetch(`/governance/policies/${code}`, {
      method: "PUT",
      auth: true,
      body: { mode },
    }),

  // ---- compliance reports (real: core-service, COMPLIANCE_REPORTS-gated) ----
  // Generation evaluates the framework's controls against live project data;
  // a 403 carries the upgrade reason. Reads/downloads are never gated.
  listComplianceReports: async (pid) =>
    ((await realFetch(`/projects/${pid}/compliance/reports`, { auth: true })) || [])
      .map(mapComplianceReport),
  generateComplianceReport: async (pid, framework) => {
    const res = await realFetch(`/projects/${pid}/compliance/reports`, {
      method: "POST",
      auth: true,
      body: { framework },
    });
    return { report: mapComplianceReport(res.report), content: res.content };
  },
  getComplianceReport: async (id) => {
    const res = await realFetch(`/compliance/reports/${id}`, { auth: true });
    return { report: mapComplianceReport(res.report), content: res.content };
  },
  // Server renders a PDF evidence document; save it via a blob link since
  // fetch (not the browser) must carry the Authorization header.
  downloadComplianceReport: async (id, filename, _retry = false) => {
    const res = await fetch(`${API_BASE}/compliance/reports/${id}/download`, {
      headers: tokenStore.access
        ? { Authorization: `Bearer ${tokenStore.access}` }
        : {},
    });
    if (res.status === 401 && !_retry && tokenStore.refresh && (await tryRefresh())) {
      return api.downloadComplianceReport(id, filename, true);
    }
    if (!res.ok) throw new ApiError(`Download failed (${res.status})`, res.status, null);
    const url = URL.createObjectURL(await res.blob());
    const a = document.createElement("a");
    a.href = url;
    a.download = filename || `compliance-report-${id}.pdf`;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
  },

  // Per-tenant workflow-complexity rules (admin-only to change). Partial
  // update: omit a field to leave it unchanged; riskyTypes: [] disables
  // risky-type gating.
  getApprovalSettings: () => realFetch("/approvals/settings", { auth: true }),
  updateApprovalSettings: ({ threshold, riskyTypes } = {}) =>
    realFetch("/approvals/settings", {
      method: "PUT",
      auth: true,
      body: {
        complexNodeThreshold: threshold ?? null,
        riskyTypes: riskyTypes ?? null,
      },
    }),
  /**
   * The form a workflow asks for before it can run — [] when it asks for
   * nothing, which is the normal answer for a plain step canvas. Resolved
   * server-side: the definition itself never reaches this browser.
   */
  workflowInputs: (id) => realFetch(`/workflows/${id}/inputs`, { auth: true }),

  /**
   * What stands between this workspace and running a workflow, asked BEFORE
   * Run. A rolled-out workflow arrives complete and looks identical to a ready
   * one — the provider built it against their workspace, not this one.
   *
   * Returns `{ready, blockers:[{kind,title,detail,action,href}]}`.
   */
  workflowReadiness: (id) =>
    realFetch(`/workflows/${id}/readiness`, { auth: true }),

  runWorkflow: (id, inputs) =>
    realFetch(`/workflows/${id}/run`, {
      method: "POST",
      auth: true,
      // Omitted entirely when there is nothing to send, so a workflow with no
      // declared inputs posts the same empty body it always did.
      body: inputs && Object.keys(inputs).length ? { inputs } : undefined,
    }).then((res) =>
      res && res.approvalRequired
        ? { approvalRequired: true, approval: mapApproval(res.approval) }
        : mapRun(res),
    ),
  cancelExecution: (id) =>
    realFetch(`/runs/${id}/cancel`, { method: "POST", auth: true }).then(mapRun),

  // ---- notifications (real: core-service inbox, per-member read state) ----
  listNotifications: () => realFetch("/notifications", { auth: true }),
  unreadCount: () => realFetch("/notifications/unread-count", { auth: true }),
  markAllNotificationsRead: () =>
    realFetch("/notifications/read-all", { method: "PATCH", auth: true }),
  markNotificationRead: (id) =>
    realFetch(`/notifications/${id}/read`, { method: "PATCH", auth: true }),
  /**
   * Which notification kinds this member wants to see. The label and blurb
   * come from the server with the row, so a new kind appears in Settings
   * without a change here. Muting genuinely filters the inbox and the badge —
   * it is not a stored preference nothing reads.
   */
  notificationPreferences: () =>
    realFetch("/notifications/preferences", { auth: true }),
  setNotificationPreference: (kind, enabled) =>
    realFetch("/notifications/preferences", {
      method: "PUT",
      auth: true,
      body: { kind, enabled },
    }),

  // ---- account / workspace settings ----
  // Profile: name/email come from auth-service; only the name is editable.
  getAccount: async () => {
    const me = await realFetch("/auth/me", { auth: true });
    return { name: me?.fullName || "", email: me?.email || "" };
  },
  updateAccount: (body) =>
    realFetch("/auth/me", {
      method: "PATCH",
      auth: true,
      body: { fullName: body.name },
    }),
  // Live workspace identity: display name from auth-service, plan from
  // subscription-service (title-cased so tiers[plan] lookups keep working).
  getWorkspace: async () => {
    const [profile, billing] = await Promise.all([
      realFetch("/auth/me", { auth: true }),
      realFetch("/subscriptions/current", { auth: true }).catch(() => null),
    ]);
    const name =
      profile.workspaceName ||
      (profile.tenantId ? prettySlug(profile.tenantId) : "My Workspace");
    const plan = planTitle(billing && billing.plan ? billing.plan.code : null);
    return { workspace: { name, plan } };
  },
  updateWorkspace: async (body) => {
    // Only the display name is backed by the API; other settings stay local.
    if (!body || !body.name) return null;
    const res = await realFetch("/auth/workspace", {
      method: "PATCH",
      auth: true,
      body: { name: body.name },
    });
    return { workspace: { name: res.workspaceName } };
  },

  // ---- billing / subscription (real backend: subscription-service) ----
  listPlans: () => realFetch("/plans"),
  billingStatus: async () => {
    const s = await realFetch("/subscriptions/current", { auth: true });
    return mapSubscription(s);
  },
  subscribePlan: async (plan) => {
    const s = await realFetch("/subscriptions/subscribe", {
      method: "POST",
      auth: true,
      body: { planCode: String(plan).toUpperCase() },
    });
    return mapSubscription(s);
  },
  cancelSubscription: async () => {
    const s = await realFetch("/subscriptions/cancel", { method: "POST", auth: true });
    return mapSubscription(s);
  },
  // Payment history + PAST_DUE recovery (real backend: payment module).
  listPayments: () => realFetch("/payments", { auth: true }),
  retryPayment: () => realFetch("/payments/retry", { method: "POST", auth: true }),

  // ---- library (real: core-service template catalog) ----
  listLibrary: async () => {
    const rows = (await realFetch("/library", { auth: true })) || [];
    return rows.map((i) => ({
      id: i.id,
      title: i.title,
      description: i.description,
      type: i.type,
      category: i.category,
      premium: !!i.premium,
      managed: !!i.managed,
      owned: !!i.owned,
      locked: !!i.locked,
      installs: i.installs || 0,
      definition: i.definition,
    }));
  },
  createLibraryItem: (body) =>
    realFetch("/library", { method: "POST", auth: true, body }),
  cloneLibraryItem: (id) =>
    realFetch(`/library/${id}/clone`, { method: "POST", auth: true }),
  /** Edit a script this workspace owns — authored here or imported and adapted. */
  updateLibraryItem: (id, body) =>
    realFetch(`/library/${id}`, { method: "PUT", auth: true, body }),

  // ---- cloud connections (real backend: core-service, MAX_CLOUD_INTEGRATIONS-gated) ----
  listCloudConnections: async () => {
    const rows = (await realFetch("/cloud/connections", { auth: true })) || [];
    // Disconnected rows survive for history but drop out of the UI.
    return rows
      .filter((c) => c.status === "CONNECTED")
      .map((c) => ({
        id: c.id,
        // Normalized to the saasData catalog's lowercase ids.
        platform: String(c.platform || "").toLowerCase(),
        name: c.name,
        // Non-secret identity: what the provider reported at the last
        // verification, falling back to the credentials' own fields.
        accountId: c.accountId || null,
        accountName: c.accountName || null,
        region: c.region || null,
        status: "connected",
        hasCredentials: !!c.hasCredentials,
        lastVerifiedAt: c.lastVerifiedAt || null,
        lastVerifiedOk: c.lastVerifiedOk ?? null, // null = never checked
        lastVerifiedMessage: c.lastVerifiedMessage || null,
        // Single-project scope; null = global (available to all projects).
        projectId: c.projectId != null ? String(c.projectId) : null,
        createdAt: c.createdAt,
      }));
  },
  // Live check of stored credentials against the real provider (STS /
  // Entra ID / Google OAuth / cluster /version). Returns
  // {supported, verified, message, checkedAt}.
  verifyCloudConnection: (id) =>
    realFetch(`/cloud/connections/${id}/verify`, { method: "POST", auth: true }),
  // Preflight: check credentials with the provider BEFORE anything is stored,
  // so the connection is only created once the user confirms the result.
  verifyCloudCredentials: (platform, credentials) =>
    realFetch("/cloud/connections/verify", {
      method: "POST",
      auth: true,
      body: { platform, credentials },
    }),
  // credentials: JSON object of the platform's fields — stored encrypted
  // server-side, never returned; terraform/kubernetes steps execute with them.
  createCloudConnection: (body) =>
    realFetch("/cloud/connections", {
      method: "POST",
      auth: true,
      body: {
        platform: body.platform,
        name: body.name,
        credentials: body.credentials,
        projectId: body.projectId ?? null,
      },
    }),
  // Scope a connection to one project, or back to global with projectId=null.
  assignCloudConnection: (id, projectId) =>
    realFetch(`/cloud/connections/${id}/project`, {
      method: "PUT",
      auth: true,
      body: { projectId: projectId ?? null },
    }),
  updateCloudCredentials: (id, credentials) =>
    realFetch(`/cloud/connections/${id}/credentials`, {
      method: "PUT",
      auth: true,
      body: { platform: "-", name: "-", credentials },
    }),
  removeCloudConnection: (id) =>
    realFetch(`/cloud/connections/${id}`, { method: "DELETE", auth: true }),

  // ---- provider (real: PROVIDER-role endpoints across the services) ----
  // Tenant directory (auth) merged with subscriptions (billing) client-side.
  providerTenantsMerged: async () => {
    const [directory, subscriptions] = await Promise.all([
      realFetch("/auth/provider/tenants", { auth: true }).catch(() => []),
      realFetch("/provider/tenants", { auth: true }).catch(() => []),
    ]);
    const byTenant = Object.fromEntries(
      (subscriptions || []).map((s) => [s.tenantId, s]),
    );
    return (directory || []).map((t) => ({
      ...t,
      subscription: byTenant[t.tenantId] || null,
    }));
  },
  providerUsage: () => realFetch("/provider/usage", { auth: true }),
  providerHealth: () => realFetch("/provider/health", { auth: true }),
  providerInvoices: () => realFetch("/provider/payments", { auth: true }),
  providerAudit: () => realFetch("/provider/audit", { auth: true }),

  // ---- fleet: every tenant at once, which is a different question ----
  // Not the tenant endpoints with a wider filter. A customer asks what is wrong
  // with their system; a provider asks which customer is on fire and which one
  // has quietly stopped sending anything. The second is answered by absence, so
  // these return per-tenant rollups the console joins against the directory —
  // a tenant missing from a rollup is the finding.
  providerFleetAlerts: () => realFetch("/provider/fleet/alerts", { auth: true }),
  providerFleetIncidents: () =>
    realFetch("/provider/fleet/incidents", { auth: true }),
  providerFleetAgents: (days = 7) =>
    realFetch(`/provider/fleet/agents?days=${days}`, { auth: true }),

  // What a designer may build, read from the runtime's own models rather than
  // remembered here. A palette hardcoded in this file is a second copy of a
  // contract that changes in Python, and it goes stale silently: the runtime
  // gains a node type, the console never offers it, and a missing palette entry
  // looks like a design decision rather than a bug. That exact coupling has
  // already cost this platform twice on the Java side.
  //
  // Deliberately NOT cached across a session. It is one small request per page
  // load, and a cache would mean a console kept offering yesterday's palette
  // after the runtime was upgraded underneath it.
  // Who holds a catalog item right now. Read live from the services that hold
  // the copies, so a revoked delivery leaves the list rather than lingering.
  // Deliver a catalog item into the provider's OWN sandbox project so it can
  // be run before a customer ever sees it. A real delivery, not a simulation.
  providerTestRollout: (catalogId) =>
    realFetch(`/provider/rollout/${catalogId}/test`, { method: "POST", auth: true }),

  providerDeliveries: (catalogId) =>
    realFetch(`/provider/rollout/${catalogId}/deliveries`, { auth: true }),

  // The counterpart to rollout. tenantId travels explicitly: a delivery is
  // identified by (tenant, id), and revoking on an id alone could take a copy
  // from a customer nobody named.
  providerRevoke: (catalogId, deliveredId, tenantId) =>
    realFetch(
      `/provider/rollout/${catalogId}/deliveries/${deliveredId}?tenantId=${encodeURIComponent(tenantId)}`,
      { method: "DELETE", auth: true },
    ),

  providerAuthoringSchema: () =>
    realFetch("/provider/authoring/schema", { auth: true }),

  // The same `inspect` the execution path uses, so the designer's verdict and
  // the one that decides whether a run works are the same verdict. A validator
  // written in this file would be a second opinion, and the two would disagree
  // on exactly the definitions worth checking — the odd ones.
  providerValidateWorkflow: (definition) =>
    realFetch("/provider/authoring/validate", {
      method: "POST",
      auth: true,
      body: definition,
    }),
  createBroadcast: (body) =>
    realFetch("/provider/broadcasts", { method: "POST", auth: true, body }),
  providerUpdatePlan: (code, body) =>
    realFetch(`/provider/plans/${code}`, { method: "PATCH", auth: true, body }),
  providerCreateLibrary: (body) =>
    realFetch("/provider/library", { method: "POST", auth: true, body }),
  providerUpdateLibrary: (id, body) =>
    realFetch(`/provider/library/${id}`, { method: "PUT", auth: true, body }),

  // ---- rollout: delivering a catalog item to chosen customers ----
  // Which projects a customer has, so the provider can say WHERE it lands.
  providerTenantProjects: (tenantId) =>
    realFetch(`/provider/tenants/${encodeURIComponent(tenantId)}/projects`, { auth: true }),

  // targets: [{ tenantId, projectId }]. Per-target outcomes come back in
  // `deliveries` — a rollout can partly succeed, and the UI must say so
  // rather than claiming all-or-nothing.
  providerRollOut: (catalogId, targets) =>
    realFetch("/provider/rollout", {
      method: "POST",
      auth: true,
      body: { catalogId, targets },
    }),

  // The provider template catalog is the managed slice of the shared library.
  // The catalog WITHOUT definitions. ~240 items averaging 8.6KB of JSON made
  // this a two-megabyte response on screens that render none of them, and
  // three provider screens read it. `ref` — the one field anything reliably
  // needed from inside a definition — is now a column on the row, so the
  // agent builder no longer has a reason to ask for bodies at all.
  providerLibrary: async () => {
    const rows = (await realFetch("/library", { auth: true })) || [];
    return rows.filter((i) => i.managed);
  },

  // One item, with its definition. What the drawer and the designer want.
  libraryItem: (id) => realFetch(`/library/${id}`, { auth: true }),

  // ---- API keys (real: auth-service, API_ACCESS plan feature) ----
  // create() returns the raw key EXACTLY ONCE — show it, then it's gone.
  listApiKeys: () => realFetch("/auth/api-keys", { auth: true }),
  createApiKey: (name) =>
    realFetch("/auth/api-keys", { method: "POST", auth: true, body: { name } }),
  revokeApiKey: (id) =>
    realFetch(`/auth/api-keys/${id}`, { method: "DELETE", auth: true }),

  // core-service's /connectors endpoints are deliberately absent here. They
  // still exist server-side, but no screen calls them: connectors stored a
  // credential and could be tested, and nothing ever dispatched through one.
  // The channel endpoints below replaced them.

  // ---- notification channels + rules (real: plugin-service) ----
  // The one outbound-integration system. The catalog is schema-driven (the
  // install form is generated from `fields`) and the rules actually dispatch.
  // Secret values are never returned — a configured field comes back masked,
  // so an edit that omits it keeps the stored value.
  pluginCatalog: () => realFetch("/plugins/catalog", { auth: true }),
  /** Lifecycle events a rule can subscribe to, with severity + description. */
  pluginEvents: () => realFetch("/plugins/events", { auth: true }),
  listInstallations: () => realFetch("/plugins/installations", { auth: true }),
  installPlugin: (body) =>
    realFetch("/plugins/installations", { method: "POST", auth: true, body }),
  updateInstallation: (id, body) =>
    realFetch(`/plugins/installations/${id}`, { method: "PUT", auth: true, body }),
  removeInstallation: (id) =>
    realFetch(`/plugins/installations/${id}`, { method: "DELETE", auth: true }),
  /** Real call to the third party. Answers 200 even when it fails. */
  testInstallation: (id) =>
    realFetch(`/plugins/installations/${id}/test`, { method: "POST", auth: true }),
  enableInstallation: (id) =>
    realFetch(`/plugins/installations/${id}/enable`, { method: "POST", auth: true }),
  disableInstallation: (id) =>
    realFetch(`/plugins/installations/${id}/disable`, { method: "POST", auth: true }),
  /** Delivery log — what was sent and what came back. */
  pluginDeliveries: (limit = 100) =>
    realFetch(`/plugins/deliveries?limit=${limit}`, { auth: true }),
  listNotificationRules: () => realFetch("/notification-rules", { auth: true }),
  createNotificationRule: (body) =>
    realFetch("/notification-rules", { method: "POST", auth: true, body }),
  updateNotificationRule: (id, body) =>
    realFetch(`/notification-rules/${id}`, { method: "PUT", auth: true, body }),
  removeNotificationRule: (id) =>
    realFetch(`/notification-rules/${id}`, { method: "DELETE", auth: true }),

  // ---- AI model providers, tenant bring-your-own-key (real: core-service) ----
  // This workspace's own vendor keys, stored encrypted per tenant.
  listModelProviders: () => realFetch("/model-providers", { auth: true }),
  /** Vendors AutoOps supports and the fields each one needs. */
  modelProviderCatalog: () =>
    realFetch("/model-providers/catalog", { auth: true }),
  /** Models this workspace can actually reach, for the agent model picker. */
  listWorkspaceModels: () => realFetch("/model-providers/models", { auth: true }),
  /** Switch an agent's model — allowed even on a provider-managed agent. */
  setAgentModel,
  saveModelProvider: (body) =>
    realFetch("/model-providers", { method: "POST", auth: true, body }),
  removeModelProvider: (id) =>
    realFetch(`/model-providers/${id}`, { method: "DELETE", auth: true }),
  /**
   * Preflight, the model-provider twin of verifyCloudCredentials: ask the
   * vendor about a credential BEFORE anything is stored, so a rejected key
   * never becomes a saved connection. Stores nothing either way.
   */
  verifyModelCredentials: (body) =>
    realFetch("/model-providers/verify", { method: "POST", auth: true, body }),
  /** Makes a REAL call against the vendor and stores the outcome. */
  testModelProvider: (id) =>
    realFetch(`/model-providers/${id}/test`, { method: "POST", auth: true }),
  setModelProviderEnabled: (id, enabled) =>
    realFetch(`/model-providers/${id}/enabled`, {
      method: "POST",
      auth: true,
      body: { enabled },
    }),
  /**
   * Re-points a purpose at another model. Separate from saveModelProvider
   * because the browser never receives the credential back, so it could not
   * re-send one in order to change a default.
   */
  setModelProviderDefaults: (id, body) =>
    realFetch(`/model-providers/${id}/defaults`, {
      method: "POST",
      auth: true,
      body,
    }),
  /**
   * Models the tenant DECLARED, for the vendors whose models are things they
   * deployed and named — Azure deployments, ModelArts ids, SageMaker
   * endpoints — and which therefore no probe can discover.
   */
  listModelDeployments: (id) =>
    realFetch(`/model-providers/${id}/deployments`, { auth: true }),
  saveModelDeployment: (id, body) =>
    realFetch(`/model-providers/${id}/deployments`, {
      method: "POST",
      auth: true,
      body,
    }),
  removeModelDeployment: (id, deploymentId) =>
    realFetch(`/model-providers/${id}/deployments/${deploymentId}`, {
      method: "DELETE",
      auth: true,
    }),
  /** Re-reads model lists from the vendor — the same call Test makes. */
  refreshModelProvider: (id) =>
    realFetch(`/model-providers/${id}/refresh`, { method: "POST", auth: true }),
  refreshAllModelProviders: () =>
    realFetch("/model-providers/refresh", { method: "POST", auth: true }),

  // ---- ad-hoc commands (real: core-service → platform runner) ----
  listCommands: () => realFetch("/commands", { auth: true }),
  dispatchCommand: (command) =>
    realFetch("/commands", { method: "POST", auth: true, body: { command } }),

};

// Social/SSO login. "google" and "microsoft" hit the direct OIDC flows in
// auth-service (free on every plan); anything else falls back to the
// Keycloak enterprise flow.
export const oauthUrl = (provider) => {
  const p = String(provider || "").toLowerCase();
  return p === "google" || p === "microsoft"
    ? `${API_BASE}/auth/sso/${p}`
    : `${API_BASE}/auth/sso/initiate`;
};

// Enterprise per-tenant SSO: where an email's company IdP flow starts. Login
// redirects here when the backend answers sso_required.
export const enterpriseSsoUrl = (email) =>
  `${API_BASE}/auth/enterprise-sso/initiate?email=${encodeURIComponent(email)}`;
