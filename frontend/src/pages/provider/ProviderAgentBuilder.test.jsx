import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

// What this file protects is the SHAPE of the row the builder writes.
// RolloutService reads `description`, `model`, `instructions` and `tools` out
// of the catalog definition, and resolves each tool by its stable `ref`
// against the copies the customer's project already holds. Get any of those
// wrong and the agent publishes cleanly, rolls out cleanly, and then cannot do
// its job — which is exactly the failure a form is meant to prevent.

const storeState = { pushToast: vi.fn(), can: () => true };
vi.mock("../../store/store", async () => {
  const actual = await vi.importActual("../../store/store");
  return { ...actual, useStore: () => storeState };
});

const navigate = vi.fn();
let params = {};
vi.mock("react-router-dom", async () => {
  const actual = await vi.importActual("react-router-dom");
  return { ...actual, useNavigate: () => navigate, useParams: () => params };
});

const apiMock = {
  providerLibrary: vi.fn(),
  listWorkspaceModels: vi.fn(),
  providerCreateLibrary: vi.fn(),
  providerUpdateLibrary: vi.fn(),
};
vi.mock("../../lib/api", () => ({ api: apiMock }));

const { default: ProviderAgentBuilder } = await import("./ProviderAgentBuilder");

const workflowItem = (over = {}) => ({
  id: 7,
  title: "Unused Resource Cleanup",
  description: "Row description",
  type: "workflow",
  category: "AWS",
  rollouts: 2,
  definition: JSON.stringify({
    ref: "RD-142-unused-resource-cleanup",
    description: "Lists unattached EBS volumes and deletes approved ones.",
    nodes: [],
    inputs: [],
  }),
  ...over,
});

const PERSONA =
  "You are a cloud cost-governance operator. Always report before you act, " +
  "and never delete anything without an approval reference.";

const renderPage = () =>
  render(
    <MemoryRouter>
      <ProviderAgentBuilder />
    </MemoryRouter>,
  );

const type = (label, value) =>
  fireEvent.change(screen.getByLabelText(label), { target: { value } });

const fillValidAgent = async () => {
  type("Name", "EBS Cleanup Agent");
  type("Description", "Finds unattached EBS volumes and deletes approved ones.");
  type("Model", "anthropic.claude-sonnet-5");
  type("Operating instructions", PERSONA);
  fireEvent.click(
    await screen.findByRole("checkbox", { name: /Unused Resource Cleanup/ }),
  );
};

beforeEach(() => {
  params = {};
  navigate.mockClear();
  storeState.pushToast.mockClear();
  apiMock.providerLibrary.mockResolvedValue([workflowItem()]);
  apiMock.listWorkspaceModels.mockResolvedValue([
    {
      providerId: 1,
      providerName: "Bedrock",
      kind: "bedrock",
      verified: true,
      modelsByPurpose: { chat: ["anthropic.claude-sonnet-5"] },
    },
  ]);
  apiMock.providerCreateLibrary.mockResolvedValue({ id: 99 });
  apiMock.providerUpdateLibrary.mockResolvedValue({ id: 7 });
});

describe("ProviderAgentBuilder", () => {
  it("publishes the definition rollout expects, with the tool named by ref", async () => {
    renderPage();
    await fillValidAgent();
    fireEvent.click(screen.getByText("Publish to catalog"));

    await waitFor(() => expect(apiMock.providerCreateLibrary).toHaveBeenCalled());
    const body = apiMock.providerCreateLibrary.mock.calls[0][0];
    expect(body.type).toBe("agent");
    const spec = JSON.parse(body.definition);
    expect(spec.model).toBe("anthropic.claude-sonnet-5");
    expect(spec.instructions).toContain("cost-governance");
    // By ref, never by id: workflow #7 here is a different automation in every
    // customer's workspace, and AgentService refuses an id the destination
    // project does not hold.
    expect(spec.tools).toEqual([
      { type: "WORKFLOW", ref: "RD-142-unused-resource-cleanup", mutating: true },
    ]);
  });

  it("defaults a newly picked tool to mutating, and only unsets it on request", async () => {
    renderPage();
    await fillValidAgent();
    fireEvent.change(screen.getByLabelText("Effect of Unused Resource Cleanup"), {
      target: { value: "read" },
    });
    fireEvent.click(screen.getByText("Publish to catalog"));

    await waitFor(() => expect(apiMock.providerCreateLibrary).toHaveBeenCalled());
    const spec = JSON.parse(
      apiMock.providerCreateLibrary.mock.calls[0][0].definition,
    );
    expect(spec.tools[0].mutating).toBe(false);
  });

  it("refuses to publish an agent with no tool", async () => {
    renderPage();
    type("Name", "Talker");
    type("Description", "Does nothing useful at all.");
    type("Model", "anthropic.claude-sonnet-5");
    type("Operating instructions", PERSONA);

    expect(await screen.findByText(/Pick at least one workflow/)).toBeTruthy();
    fireEvent.click(screen.getByText("Publish to catalog"));
    await waitFor(() =>
      expect(apiMock.providerCreateLibrary).not.toHaveBeenCalled(),
    );
  });

  it("refuses an approval gate on a read-only agent", async () => {
    renderPage();
    await fillValidAgent();
    fireEvent.click(
      screen.getByRole("checkbox", { name: /Requires human approval/ }),
    );

    expect(
      await screen.findByText(/approval gate belongs on a state-changing agent/),
    ).toBeTruthy();
    fireEvent.click(screen.getByText("Publish to catalog"));
    await waitFor(() =>
      expect(apiMock.providerCreateLibrary).not.toHaveBeenCalled(),
    );
  });

  it("shows a workflow published without a ref as unselectable, and says why", async () => {
    apiMock.providerLibrary.mockResolvedValue([
      workflowItem({
        id: 8,
        title: "Refless",
        definition: JSON.stringify({ nodes: [] }),
      }),
    ]);
    renderPage();

    expect(await screen.findByText(/1 workflow is not selectable/)).toBeTruthy();
    expect(screen.queryByRole("checkbox", { name: /Refless/ })).toBeNull();
  });

  it("loads an existing agent for editing and saves through the update path", async () => {
    params = { id: "42" };
    apiMock.providerLibrary.mockResolvedValue([
      workflowItem(),
      {
        id: 42,
        title: "EBS Cleanup Agent",
        description: "Row description",
        type: "agent",
        category: "AWS",
        premium: false,
        definition: JSON.stringify({
          description: "Finds unattached EBS volumes.",
          model: "anthropic.claude-sonnet-5",
          instructions: PERSONA,
          tools: [
            {
              type: "WORKFLOW",
              ref: "RD-142-unused-resource-cleanup",
              mutating: true,
            },
          ],
          guardrails: ["Report-only by default."],
          automationType: "Destructive / High-Impact",
          approvalRequired: true,
        }),
      },
    ]);
    renderPage();

    await waitFor(() =>
      expect(screen.getByLabelText("Name").value).toBe("EBS Cleanup Agent"),
    );
    expect(screen.getByLabelText("Operating instructions").value).toBe(PERSONA);
    expect(
      screen.getByRole("checkbox", { name: /Requires human approval/ }).checked,
    ).toBe(true);

    fireEvent.click(screen.getByText("Save changes"));
    await waitFor(() => expect(apiMock.providerUpdateLibrary).toHaveBeenCalled());
    expect(apiMock.providerCreateLibrary).not.toHaveBeenCalled();
  });

  it("refuses to edit a code-authored agent rather than replacing its reference", async () => {
    params = { id: "43" };
    apiMock.providerLibrary.mockResolvedValue([
      workflowItem(),
      {
        id: 43,
        title: "Linux Server Health Check",
        description: "Row description",
        type: "agent",
        category: "Linux",
        definition: JSON.stringify({
          kind: "PYTHON",
          ref: "linux.server_health_check",
          version: "1.0.0",
        }),
      },
    ]);
    renderPage();

    expect(
      await screen.findByText(/authored in code \(agent-runtime\)/),
    ).toBeTruthy();
  });
});
