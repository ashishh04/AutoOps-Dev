import { beforeEach, describe, expect, it, vi } from "vitest";
import { fetchSequence, response } from "../test/setup";

// Every backend payload is reshaped before a page sees it. These mappers are
// where a backend rename turns into a blank screen, so each one is pinned to
// the contract the services actually return.
let api;
let tokenStore;

beforeEach(async () => {
  vi.resetModules();
  ({ api, tokenStore } = await import("./api"));
  tokenStore.set("access-1", "refresh-1");
});

describe("profile mapping", () => {
  it("maps a client profile", async () => {
    fetchSequence(
      response(200, {
        id: 12,
        email: "ada@acme.io",
        fullName: "Ada Lovelace",
        role: "CLIENT",
        tenantId: "acme-corp-a1b2c3d4",
        workspaceName: "Acme Corp",
      }),
    );

    const me = await api.me();

    expect(me.user).toMatchObject({
      id: 12,
      name: "Ada Lovelace",
      email: "ada@acme.io",
      isProvider: false,
    });
    expect(me.activeTenantId).toBe("acme-corp-a1b2c3d4");
    // CLIENT is the backend's Operator. Mapping it to ADMIN handed every
    // member the admin console while admin-only calls came back 403.
    expect(me.memberships[0].role).toBe("OPERATOR");
  });

  it("maps a viewer profile to read-only capabilities", async () => {
    fetchSequence(
      response(200, { id: 13, email: "obs@acme.io", role: "VIEWER", tenantId: "t" }),
    );

    const me = await api.me();

    expect(me.memberships[0].role).toBe("VIEWER");
  });

  it("keeps a workspace admin on ADMIN", async () => {
    fetchSequence(
      response(200, { id: 14, email: "boss@acme.io", role: "ADMIN", tenantId: "t" }),
    );

    const me = await api.me();

    expect(me.memberships[0].role).toBe("ADMIN");
  });

  it("flags a provider account", async () => {
    fetchSequence(
      response(200, { id: 1, email: "ops@intertec.io", role: "PROVIDER", tenantId: "t" }),
    );

    const me = await api.me();

    expect(me.user.isProvider).toBe(true);
    expect(me.memberships[0].role).toBe("PROVIDER");
  });

  it("falls back to the email local-part when there is no full name", async () => {
    fetchSequence(response(200, { id: 2, email: "grace@navy.mil", role: "CLIENT", tenantId: "t" }));

    const me = await api.me();

    expect(me.user.name).toBe("grace");
  });

  it("prettifies a slug tenant id when no workspace name was stored", async () => {
    fetchSequence(
      response(200, { id: 3, email: "x@y.z", role: "CLIENT", tenantId: "acme-corp-a1b2c3d4" }),
    );

    const me = await api.me();

    // The 8-hex suffix is the tenant discriminator, not part of the name.
    expect(me.memberships[0].tenant.name).toBe("Acme Corp");
  });

  it("uses a neutral name for the default tenant", async () => {
    fetchSequence(response(200, { id: 4, email: "x@y.z", role: "CLIENT", tenantId: "default" }));

    const me = await api.me();

    expect(me.memberships[0].tenant.name).toBe("My Workspace");
  });
});

describe("session establishment", () => {
  it("stores tokens before loading the profile, and sends the NEW token", async () => {
    const fetchMock = fetchSequence(
      response(200, { accessToken: "fresh-access", refreshToken: "fresh-refresh" }),
      response(200, { id: 1, email: "x@y.z", role: "CLIENT", tenantId: "t" }),
    );

    const session = await api.login("x@y.z", "pw");

    expect(tokenStore.access).toBe("fresh-access");
    expect(fetchMock.mock.calls[1][0]).toBe("/api/auth/me");
    expect(fetchMock.mock.calls[1][1].headers.Authorization).toBe("Bearer fresh-access");
    expect(session).toMatchObject({ accessToken: "fresh-access", context: "client" });
  });

  it("sends the refresh token on logout so the server can revoke that session", async () => {
    const fetchMock = fetchSequence(response(200, {}));

    await api.logout();

    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({ refreshToken: "refresh-1" });
  });
});

describe("subscription mapping", () => {
  it("maps an active subscription with its plan limits and features", async () => {
    fetchSequence(
      response(200, {
        status: "ACTIVE",
        trialEndsAt: null,
        currentPeriodEnd: "2026-08-01T00:00:00Z",
        cancelAtPeriodEnd: false,
        plan: {
          code: "TEAM",
          priceMonthly: 99,
          features: ["CORE_AUTOMATION", "AUDIT_LOG"],
          maxProjects: 25,
          maxNodes: 50,
          historyDays: 90,
        },
      }),
    );

    const billing = await api.billingStatus();

    expect(billing.plan).toBe("TEAM");
    expect(billing.price).toBe(99);
    expect(billing.subscription.status).toBe("ACTIVE");
    expect(billing.entitlements).toEqual({ CORE_AUTOMATION: true, AUDIT_LOG: true });
    expect(billing.limits.projects).toBe(25);
    expect(billing.limits.historyDays).toBe(90);
  });

  it("renders unlimited plan limits as text, not null", async () => {
    fetchSequence(
      response(200, {
        status: "ACTIVE",
        plan: { code: "ENTERPRISE", priceMonthly: 399, features: [], historyDays: 730 },
      }),
    );

    const billing = await api.billingStatus();

    expect(billing.limits.projects).toBe("Unlimited");
    expect(billing.limits.nodes).toBe("Unlimited");
  });

  it("maps 'no subscription' to an empty billing state", async () => {
    fetchSequence(response(200, { status: "NONE" }));

    const billing = await api.billingStatus();

    expect(billing.plan).toBeNull();
    expect(billing.subscription.status).toBe("NONE");
    expect(billing.entitlements).toEqual({});
  });
});

describe("project mapping", () => {
  it("lowercases status and hides archived projects", async () => {
    fetchSequence(
      response(200, [
        { id: 1, name: "Live", status: "ACTIVE" },
        { id: 2, name: "Gone", status: "ARCHIVED" },
      ]),
    );

    const projects = await api.listProjects();

    expect(projects).toHaveLength(1);
    expect(projects[0]).toMatchObject({ id: 1, name: "Live", status: "active" });
  });

  it("tolerates a null project list", async () => {
    fetchSequence(response(200, null));

    await expect(api.listProjects()).resolves.toEqual([]);
  });
});

describe("run mapping", () => {
  const RUN = {
    id: 9,
    projectId: 3,
    name: "Nightly backup",
    targetType: "JOB",
    targetId: 42,
    status: "SUCCEEDED",
    trigger: "SCHEDULE",
    triggeredBy: "scheduler",
    stepTotal: 4,
    stepCompleted: 4,
    durationMs: 8123,
  };

  it("translates backend run status to the UI vocabulary", async () => {
    fetchSequence(response(200, RUN));

    const run = await api.runJob(42);

    // SUCCEEDED -> "success": the badge component keys off the UI word.
    expect(run.status).toBe("success");
    expect(run.trigger).toBe("schedule");
    expect(run.job).toBe("Nightly backup");
    expect(run.workflow).toBeUndefined();
  });

  it("stringifies target ids so they compare against route params", async () => {
    fetchSequence(response(200, RUN));

    const run = await api.runJob(42);

    // useParams() yields strings; a number here silently breaks every ===.
    expect(run.jobId).toBe("42");
  });

  it("maps an unknown status by lowercasing rather than dropping it", async () => {
    fetchSequence(response(200, { ...RUN, status: "TIMED_OUT" }));

    const run = await api.runJob(42);

    expect(run.status).toBe("timed_out");
  });

  it("returns the approval instead of a run when sign-off is required", async () => {
    fetchSequence(
      response(200, {
        approvalRequired: true,
        approval: { id: 5, status: "PENDING", requestedBy: "ada@acme.io" },
      }),
    );

    const result = await api.runJob(42);

    expect(result.approvalRequired).toBe(true);
    expect(result.approval.id).toBe(5);
  });
});

describe("agent mapping", () => {
  const AGENT = {
    id: 4,
    projectId: 3,
    name: "Production watchdog",
    description: "Watches deploys",
    model: "gpt-4o",
    instructions: "Escalate what you cannot fix.",
    tools: [
      { type: "WORKFLOW", id: 11, name: "Deploy API", available: true },
      { type: "JOB", id: 31, name: "Deleted job #31", available: false },
    ],
    toolCount: 2,
    enabled: true,
  };

  it("lowercases tool types and derives the badge status", async () => {
    fetchSequence(response(200, [AGENT]));

    const [agent] = await api.list("agents", 3);

    // The tool icons and the <StatusBadge /> both key off these words.
    expect(agent.tools.map((t) => t.type)).toEqual(["workflow", "job"]);
    expect(agent.status).toBe("active");
  });

  it("keeps a dangling tool visible rather than dropping it", async () => {
    fetchSequence(response(200, [AGENT]));

    const [agent] = await api.list("agents", 3);

    // An agent pointing at a deleted automation is a configuration problem
    // its owner has to see — silently filtering it hides the breakage.
    expect(agent.tools[1]).toMatchObject({ id: 31, available: false });
    expect(agent.toolCount).toBe(2);
  });

  it("round-trips the mutating flag so an edit cannot wipe it", async () => {
    // The bug: mapAgent read {type, id, name} and agentTools wrote
    // {type, id}, so saving a rolled-out agent dropped the flag its catalog
    // declared. The backend then fails closed on the undeclared tool and the
    // agent goes blind to its own read-only automations.
    fetchSequence(
      response(200, {
        ...AGENT,
        tools: [
          { type: "WORKFLOW", id: 11, name: "Deploy API", available: true, mutating: false },
          { type: "JOB", id: 31, name: "Purge logs", available: true, mutating: true },
        ],
      }),
      response(200, { ...AGENT, tools: [] }),
    );

    const agent = await api.get("agents", 4);
    expect(agent.tools.map((t) => t.mutating)).toEqual([false, true]);

    await api.update("agents", 4, { tools: agent.tools });
    const sent = JSON.parse(fetch.mock.calls[1][1].body);
    expect(JSON.parse(sent.tools)).toEqual([
      { type: "WORKFLOW", id: 11, mutating: false },
      { type: "JOB", id: 31, mutating: true },
    ]);
  });

  it("does not invent a mutating flag the grant never declared", async () => {
    // Undeclared is a third state. Writing `false` would hand a possibly
    // state-changing automation to the evidence-gathering phase; writing
    // `true` would hide a read-only one. Saying nothing keeps the decision
    // with the backend, which fails closed.
    fetchSequence(
      response(200, AGENT),
      response(200, { ...AGENT, tools: [] }),
    );

    const agent = await api.get("agents", 4);
    expect(agent.tools[0].mutating).toBeUndefined();

    await api.update("agents", 4, { tools: agent.tools });
    const sent = JSON.parse(fetch.mock.calls[1][1].body);
    expect(JSON.parse(sent.tools)[0]).toEqual({ type: "WORKFLOW", id: 11 });
  });

  it("shows a disabled agent as paused", async () => {
    fetchSequence(response(200, { ...AGENT, enabled: false }));

    const agent = await api.setAgentEnabled(4, false);

    expect(agent.status).toBe("paused");
    expect(agent.enabled).toBe(false);
  });

  it("sends the allow-list as JSON the backend can validate", async () => {
    const spy = fetchSequence(response(201, AGENT));

    await api.create("agents", {
      projectId: 3,
      name: "Production watchdog",
      tools: [{ type: "workflow", id: "11" }],
    });

    const [url, init] = spy.mock.calls[0];
    expect(url).toContain("/projects/3/agents");
    // Types upper-cased and ids numeric — the backend rejects anything else.
    expect(JSON.parse(init.body).tools).toBe('[{"type":"WORKFLOW","id":11}]');
  });

  it("omits tools on a persona-only save so the allow-list survives", async () => {
    const spy = fetchSequence(response(200, AGENT));

    await api.update("agents", 4, { name: "Night watchdog" });

    const body = JSON.parse(spy.mock.calls[0][1].body);
    expect(body.name).toBe("Night watchdog");
    expect(body).not.toHaveProperty("tools");
  });
});
