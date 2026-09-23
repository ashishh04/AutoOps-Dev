import React from "react";
import { render, screen, waitFor } from "@testing-library/react";
import { describe, it, expect, vi, beforeEach } from "vitest";

/**
 * What this panel promises is that the list is COMPLETE and HONEST.
 *
 * A customer reads it, creates an IAM role from it, and expects the automation
 * to work. So the cases worth pinning are the ones where a wrong answer still
 * looks like an answer: a failed request rendering as "needs nothing", and a
 * tool whose permissions could not be determined being quietly left out.
 */
const apiMock = { libraryRequirements: vi.fn() };
vi.mock("../../lib/api", () => ({ api: apiMock }));

const { default: RequirementsPanel } = await import("./RequirementsPanel");

const finops = {
  itemId: 276,
  title: "AWS FinOps Analyst",
  type: "agent",
  connections: [
    {
      platform: "AWS",
      customerText: "An AWS account we can read from.",
      permissions: ["ce:GetCostAndUsage", "cloudtrail:LookupEvents", "ec2:DescribeVolumes"],
      neededBy: ["Idle Resource Inventory", "Cost Explorer Service Delta"],
      policyDocument: '{\n  "Version" : "2012-10-17"\n}',
    },
  ],
  unresolved: [],
};

beforeEach(() => vi.clearAllMocks());

describe("RequirementsPanel", () => {
  it("lists the union of permissions an agent's tools need", async () => {
    // The case a human cannot answer by reading: an agent declares nothing
    // itself, so its requirement is whatever its tools want.
    apiMock.libraryRequirements.mockResolvedValue(finops);

    render(<RequirementsPanel itemId={276} />);

    await waitFor(() => expect(screen.getByText("ce:GetCostAndUsage")).toBeInTheDocument());
    expect(screen.getByText("cloudtrail:LookupEvents")).toBeInTheDocument();
    expect(screen.getByText("ec2:DescribeVolumes")).toBeInTheDocument();
    expect(screen.getByText(/Needed by:/)).toHaveTextContent("Idle Resource Inventory");
  });

  it("shows a pasteable policy, because a list of API names is not an instruction", async () => {
    apiMock.libraryRequirements.mockResolvedValue(finops);

    render(<RequirementsPanel itemId={276} />);

    await waitFor(() =>
      expect(screen.getByText(/Paste this as an IAM policy/)).toBeInTheDocument(),
    );
    expect(screen.getByText(/IAM → Policies → Create policy/)).toBeInTheDocument();
  });

  it("says an automation needs nothing only when it genuinely needs nothing", async () => {
    // True for the platform-plane automations, which read AutoOps's own record
    // and take no vendor credential at all.
    apiMock.libraryRequirements.mockResolvedValue({
      itemId: 271, title: "AutoOps Activity Correlator", type: "agent",
      connections: [], unresolved: [],
    });

    render(<RequirementsPanel itemId={271} />);

    await waitFor(() =>
      expect(screen.getByText(/No cloud account needed/)).toBeInTheDocument(),
    );
  });

  it("does NOT render 'needs nothing' when the request failed", async () => {
    // The dangerous one. An empty list on error tells a customer to connect
    // nothing, and the automation then fails for a reason the panel denied.
    apiMock.libraryRequirements.mockRejectedValue(new Error("gateway down"));

    render(<RequirementsPanel itemId={276} />);

    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("gateway down"));
    expect(screen.queryByText(/No cloud account needed/)).not.toBeInTheDocument();
  });

  it("names tools whose permissions could not be determined", async () => {
    // A short list that looks complete is worse than an honest gap: the
    // customer grants it, still hits a permissions error, and stops trusting
    // every list after it.
    apiMock.libraryRequirements.mockResolvedValue({
      ...finops,
      unresolved: ["RD-999-not-in-catalog"],
    });

    render(<RequirementsPanel itemId={276} />);

    await waitFor(() =>
      expect(screen.getByText(/RD-999-not-in-catalog/)).toBeInTheDocument(),
    );
    expect(screen.getByText(/NOT included above/)).toBeInTheDocument();
  });

  it("does not claim anything while the answer is still loading", async () => {
    apiMock.libraryRequirements.mockReturnValue(new Promise(() => {}));

    render(<RequirementsPanel itemId={276} />);

    expect(screen.getByText(/Checking what this needs/)).toBeInTheDocument();
    expect(screen.queryByText(/No cloud account needed/)).not.toBeInTheDocument();
  });
});
