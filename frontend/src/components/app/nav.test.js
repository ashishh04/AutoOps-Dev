import { describe, it, expect } from "vitest";
import { projectNav } from "./AppLayout";
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
