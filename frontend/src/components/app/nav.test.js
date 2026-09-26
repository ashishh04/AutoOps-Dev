import { describe, it, expect } from "vitest";
import { projectNav, workspaceNav } from "./AppLayout";
import { ROLE_CAPS } from "../../store/store";

// A page can have a route, a component and passing tests and still be
// unreachable because nothing links to it. That has happened twice (the
// designer, then Compliance Reports), so the sidebar gets asserted directly.

const canFor = (role) => (cap) => !!ROLE_CAPS[role]?.[cap];
const B = "/app/projects/7";

const flatten = (nav) => nav.flatMap((g) => g.items);
const labels = (nav) => flatten(nav).map((i) => i.label);
const byLabel = (nav, label) => flatten(nav).find((i) => i.label === label);

describe("project sidebar", () => {
  const adminNav = () => projectNav(B, canFor("admin"));

  it("links every project page an admin needs", () => {
    const found = labels(adminNav());
    for (const label of [
      "Overview",
      "Jobs",
      "Workflows",
      "Executions",
      "Approvals",
      "Governance",
      "Compliance Reports",
    ]) {
      expect(found).toContain(label);
    }
  });

  it("places Compliance Reports directly after Governance", () => {
    const govern = adminNav().find((g) => g.group === "Govern");
    const order = govern.items.map((i) => i.label);
    expect(order.indexOf("Compliance Reports")).toBe(order.indexOf("Governance") + 1);
  });

  it("points Compliance Reports at the compliance route, not settings", () => {
    expect(byLabel(adminNav(), "Compliance Reports").to).toBe(`${B}/compliance`);
  });

  /**
   * There is ONE workflow concept and the native runtime is the engine. A second
   * "AI Workflows" entry would imply a second kind of workflow with a second
   * designer, which is exactly the split that was removed.
   */
  it("offers a single Workflows entry, not a separate AI one", () => {
    const found = labels(adminNav());
    expect(found).toContain("Workflows");
    expect(found).not.toContain("AI Workflows");
    expect(byLabel(adminNav(), "Workflows").to).toBe(`${B}/workflows`);
  });

  /**
   * The ONLY place a delivered agent can appear.
   *
   * An agent's tool workflows are delivered as sealed AGENT_COMPONENTs and are
   * deliberately filtered out of the Workflows list, so a customer who has
   * been rolled out an agent and has no entry here sees nothing at all — which
   * is exactly what happened while this entry was missing.
   */
  it("offers AI Agents, which is the only surface a rolled-out agent has", () => {
    const found = labels(adminNav());
    expect(found).toContain("AI Agents");
    expect(byLabel(adminNav(), "AI Agents").to).toBe(`${B}/agents`);
  });

  it("gives every item a destination and an icon", () => {
    for (const item of flatten(adminNav())) {
      expect(item.to, `${item.label} has no destination`).toBeTruthy();
      expect(item.icon, `${item.label} has no icon`).toBeTruthy();
    }
  });

  it("hides governance and compliance from roles that cannot manage them", () => {
    for (const role of ["operator", "viewer"]) {
      const found = labels(projectNav(B, canFor(role)));
      expect(found).not.toContain("Governance");
      expect(found).not.toContain("Compliance Reports");
    }
  });

  it("still offers the automation pages to an operator", () => {
    const found = labels(projectNav(B, canFor("operator")));
    expect(found).toContain("Workflows");
    expect(found).toContain("Jobs");
  });

  it("drops no group to an empty item list", () => {
    for (const role of ["admin", "operator", "viewer"]) {
      for (const group of projectNav(B, canFor(role))) {
        expect(group.items.length, `${group.group} is empty for ${role}`).toBeGreaterThan(0);
      }
    }
  });
});

describe("workspace sidebar", () => {
  const nav = (role = "admin") => workspaceNav(canFor(role), role);

  /**
   * The alert plane is WORKSPACE-level. It used to hang off a project, which
   * meant connecting the same Datadog account once per project and then
   * guessing which project an alert had landed in before it could be read —
   * and for an alert carrying no AutoOps labels, which is most of them, the
   * answer was none of them.
   */
  it("offers the alert plane at workspace level", () => {
    const found = labels(nav());
    expect(found).toContain("Incidents");
    expect(found).toContain("Alert Feed");
    expect(found).toContain("Monitoring Sources");
  });

  /**
   * Nothing correlates without a rule, so a customer looking at an empty
   * Incidents page needs the thing that fills it within reach. Buried in
   * settings, they conclude the feature is broken instead of unconfigured.
   */
  it("offers Correlation Rules directly under Incidents", () => {
    const operate = nav().find((g) => g.group === "Operate");
    const order = operate.items.map((i) => i.label);
    expect(order.indexOf("Correlation Rules")).toBe(order.indexOf("Incidents") + 1);
    expect(byLabel(nav(), "Correlation Rules").to).toBe("/app/incidents/rules");
  });

  /**
   * Incidents must be `end`, or it stays highlighted while you are on
   * Correlation Rules — which lives underneath it as /app/incidents/rules.
   */
  it("marks Incidents as an exact match so its child route does not light it up", () => {
    expect(byLabel(nav(), "Incidents").end).toBe(true);
  });

  it("points them at workspace routes, not at a project", () => {
    expect(byLabel(nav(), "Incidents").to).toBe("/app/incidents");
    expect(byLabel(nav(), "Alert Feed").to).toBe("/app/alerts");
    expect(byLabel(nav(), "Monitoring Sources").to).toBe("/app/alerts/sources");
  });

  /**
   * Alert Feed must be `end`, or it stays highlighted while you are on
   * Monitoring Sources — which lives underneath it as /app/alerts/sources.
   */
  it("marks Alert Feed as an exact match so its child route does not light it up", () => {
    expect(byLabel(nav(), "Alert Feed").end).toBe(true);
  });

  it("gives every item a destination and an icon", () => {
    for (const role of ["admin", "operator", "viewer"]) {
      for (const item of flatten(nav(role))) {
        expect(item.to, `${item.label} has no destination`).toBeTruthy();
        expect(item.icon, `${item.label} has no icon`).toBeTruthy();
      }
    }
  });

  it("drops no group to an empty item list", () => {
    for (const role of ["admin", "operator", "viewer"]) {
      for (const group of nav(role)) {
        expect(group.items.length, `${group.group} is empty for ${role}`).toBeGreaterThan(0);
      }
    }
  });
});

describe("project sidebar no longer duplicates the alert plane", () => {
  /**
   * Two entries for one screen is the split this change removed. A customer
   * who sees "Alert Feed" in both places has to learn which of the two shows
   * their alerts, and the answer used to be "the project one, but only some".
   */
  it("leaves incidents, alerts and sources to the workspace nav", () => {
    const found = labels(projectNav(B, canFor("admin")));
    expect(found).not.toContain("Incidents");
    expect(found).not.toContain("Alert Feed");
    expect(found).not.toContain("Monitoring Sources");
  });

  it("keeps the rest of Operate where it was", () => {
    const found = labels(projectNav(B, canFor("admin")));
    expect(found).toContain("Executions");
    expect(found).toContain("Nodes");
  });
});
