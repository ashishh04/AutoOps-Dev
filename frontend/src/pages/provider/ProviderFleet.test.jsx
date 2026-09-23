import React from "react";
import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";

vi.mock("../../lib/api", () => ({
  api: {
    providerFleetAlerts: vi.fn(),
    providerFleetIncidents: vi.fn(),
    providerFleetAgents: vi.fn(),
    providerTenantsMerged: vi.fn(),
  },
}));

import { api } from "../../lib/api";
import ProviderFleet from "./ProviderFleet";

/**
 * What makes this a PROVIDER view rather than the tenant view widened.
 *
 * The assertions worth reading are the ones about absence. A per-tenant page
 * cannot show a customer who has sent nothing, because from inside that tenant
 * "no alerts" is indistinguishable from a quiet week. Here it is the headline.
 */
describe("ProviderFleet", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.providerFleetIncidents.mockResolvedValue({
      incidents: [],
      open_count: 0,
      unassigned_count: 0,
    });
    api.providerFleetAgents.mockResolvedValue({
      window_days: 7,
      agents: [],
      attribution: [],
      findings: [],
    });
  });

  it("names the tenants that exist and have never sent an alert", async () => {
    // acme is sending. quiet-co is in the directory and absent from the rollup,
    // which is the whole finding — the rollup can only report what arrived.
    api.providerFleetAlerts.mockResolvedValue({
      tenants: [{ tenant_id: "acme", alerts: 12, firing: 3, critical: 1,
                  last_received_at: new Date().toISOString() }],
      tenant_count: 1,
      truncated: false,
      unattributed_alerts: 0,
    });
    api.providerTenantsMerged.mockResolvedValue([
      { tenantId: "acme", name: "Acme" },
      { tenantId: "quiet-co", name: "Quiet Co" },
    ]);

    render(<ProviderFleet />);

    // By ROLE, not by text. The page carries this string twice — once as the
    // StatCard's label and once as the panel's heading — and a bare getByText
    // was silently satisfied by the stat while the heading was not rendering
    // at all, because Card dropped `title` onto the div as a tooltip.
    await waitFor(() =>
      expect(
        screen.getByRole("heading", { name: "Tenants with no signal" }),
      ).toBeInTheDocument(),
    );
    expect(screen.getByText("Quiet Co")).toBeInTheDocument();
    // And the one that IS sending must not be listed as silent.
    expect(screen.queryByText("Acme")).not.toBeInTheDocument();
  });

  it("says when the rollup was truncated rather than showing a smaller number", async () => {
    // A truncated rollup makes a busy tenant look quiet. The count alone would
    // be believed; the warning is what stops that.
    api.providerFleetAlerts.mockResolvedValue({
      tenants: [{ tenant_id: "acme", alerts: 2000, firing: 900, critical: 40,
                  last_received_at: new Date().toISOString() }],
      tenant_count: 1,
      truncated: true,
      unattributed_alerts: 0,
    });
    api.providerTenantsMerged.mockResolvedValue([{ tenantId: "acme", name: "Acme" }]);

    render(<ProviderFleet />);

    await waitFor(() =>
      expect(screen.getByText(/floor rather than a total/i)).toBeInTheDocument(),
    );
  });

  it("surfaces alerts that carry no tenant label", async () => {
    // These belong to NO tenant's view, so a per-tenant page can never show
    // them. They mean a connected source has stopped stamping its alerts.
    api.providerFleetAlerts.mockResolvedValue({
      tenants: [],
      tenant_count: 0,
      truncated: false,
      unattributed_alerts: 7,
    });
    api.providerTenantsMerged.mockResolvedValue([]);

    render(<ProviderFleet />);

    await waitFor(() =>
      expect(screen.getByText(/7 alert\(s\) arrived without a tenant label/i))
        .toBeInTheDocument(),
    );
  });

  it("joins agent rows to attribution on the runtime ref, not the display name", async () => {
    // The display name and the graph ref DIFFER in production, and confusing
    // them refused every verdict in this platform once. The fixture sets them
    // differently on purpose — a fixture where they matched could not catch it.
    api.providerFleetAlerts.mockResolvedValue({
      tenants: [], tenant_count: 0, truncated: false, unattributed_alerts: 0,
    });
    api.providerTenantsMerged.mockResolvedValue([]);
    api.providerFleetAgents.mockResolvedValue({
      window_days: 7,
      agents: [{
        tenant_id: "acme",
        agent_name: "AWS Incident RCA Analyst",
        agent_ref: "aws.incident_rca_analyst",
        enabled: true,
        runs: 4,
        failed_runs: 0,
        complete_coverage: 0,
        silent_coverage: 4,
        last_run_at: new Date().toISOString(),
      }],
      attribution: [{
        tenant_id: "acme",
        agent_ref: "aws.incident_rca_analyst",
        attributed: 9,
        unattributed: 0, unscoped: 0, out_of_scope: 0, foreign_run: 0, late: 0,
      }],
      findings: [{
        tenant_id: "acme", agent_ref: "aws.incident_rca_analyst",
        findings: 3, open_findings: 2,
      }],
    });

    render(<ProviderFleet />);

    await waitFor(() =>
      expect(screen.getByText("AWS Incident RCA Analyst")).toBeInTheDocument(),
    );
    // 9 verdicts and 2 open findings only appear if the join used agent_ref.
    expect(screen.getByText("9")).toBeInTheDocument();
    expect(screen.getByText("2")).toBeInTheDocument();
  });

  it("reports a failure instead of rendering a healthy-looking empty fleet", async () => {
    // The dangerous failure: panels that load empty read as "nothing is wrong".
    api.providerFleetAlerts.mockRejectedValue(new Error("gateway down"));
    api.providerTenantsMerged.mockResolvedValue([]);

    render(<ProviderFleet />);

    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("gateway down"));
  });
});
