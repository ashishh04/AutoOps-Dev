import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

/**
 * The control a field gets, and where its choices come from.
 *
 * A node's fields used to be text boxes regardless of what they held. `model`
 * was free text, so authoring meant remembering a vendor's exact id and finding
 * out at run time if it was wrong; `temperature` had no bounds and no spinner;
 * and a structured field said only "JSON", which tells an author the encoding
 * and nothing about the shape.
 *
 * Two sources feed the choices, and the split matters:
 *   - the RUNTIME's schema, for things it knows (`job.target` is JOB|WORKFLOW)
 *   - this DEPLOYMENT's own connections, for things it cannot know — the
 *     runtime has no idea which vendors a workspace has connected.
 */

const storeState = { pushToast: vi.fn() };
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
  providerAuthoringSchema: vi.fn(),
  providerValidateWorkflow: vi.fn(),
  providerCreateLibrary: vi.fn(),
  providerUpdateLibrary: vi.fn(),
  providerLibrary: vi.fn(),
  listWorkspaceModels: vi.fn(),
};
vi.mock("../../lib/api", () => ({ api: apiMock }));
vi.mock("./WorkflowCanvas", () => ({ default: () => <div data-testid="canvas" /> }));

const { default: ProviderWorkflowDesigner } = await import("./ProviderWorkflowDesigner");

const SCHEMA = {
  workflow: {
    input_types: ["text"],
    reference_syntax: "{{#nodeId.field#}}",
    node_types: [
      {
        type: "llm",
        summary: "Ask a model.",
        contributes: ["text"],
        fields: [
          { name: "prompt", wire_name: "prompt", type: "list", required: true },
          { name: "model", wire_name: "model", type: "text", required: false },
          { name: "temperature", wire_name: "temperature", type: "number", required: false },
        ],
      },
      {
        type: "job",
        summary: "Run an automation.",
        contributes: ["output"],
        fields: [
          {
            name: "target", wire_name: "target", type: "text", required: false,
            options: ["JOB", "WORKFLOW"],
          },
          { name: "target_id", wire_name: "targetId", type: "number", required: true },
        ],
      },
    ],
  },
  agent: { phases: [], subject_source: { fields: [] } },
  undispatchable_node_types: [],
};

const renderPage = () =>
  render(
    <MemoryRouter>
      <ProviderWorkflowDesigner />
    </MemoryRouter>,
  );

const addNode = async (type) => {
  renderPage();
  fireEvent.click(await screen.findByRole("button", { name: `+ ${type}` }));
};

beforeEach(() => {
  params = {};
  navigate.mockClear();
  apiMock.providerAuthoringSchema.mockResolvedValue(SCHEMA);
  apiMock.providerLibrary.mockResolvedValue([]);
  apiMock.listWorkspaceModels.mockResolvedValue([
    {
      kind: "BEDROCK",
      providerName: "AWS Bedrock",
      models: ["deepseek.v3.2", "amazon.nova-pro-v1:0", "titan-embed-v2"],
      modelsByPurpose: { CHAT: ["deepseek.v3.2", "amazon.nova-pro-v1:0"] },
    },
    {
      kind: "OPENAI",
      providerName: "OpenAI",
      models: ["gpt-4o-mini"],
      modelsByPurpose: { CHAT: ["gpt-4o-mini"] },
    },
  ]);
});

describe("workflow field controls", () => {
  it("offers the models this deployment can actually reach", async () => {
    await addNode("llm");

    const select = await screen.findByLabelText(/^model$/i);
    expect(select.tagName).toBe("SELECT");
    const values = [...select.options].map((o) => o.value);
    expect(values).toContain("deepseek.v3.2");
    expect(values).toContain("gpt-4o-mini");
    // Grouped by connection, so two vendors offering similar ids are
    // distinguishable.
    expect(select.querySelector('optgroup[label="AWS Bedrock"]')).toBeTruthy();
    expect(select.querySelector('optgroup[label="OpenAI"]')).toBeTruthy();
  });

  it("offers only models you can hold a conversation with", async () => {
    // An embedding model accepted here fails at the first call with a vendor
    // error that explains nothing.
    await addNode("llm");

    const select = await screen.findByLabelText(/^model$/i);
    expect([...select.options].map((o) => o.value)).not.toContain("titan-embed-v2");
  });

  it("makes the portable choice the default, and says why", async () => {
    // A catalog workflow is rolled out to many workspaces. Naming a literal
    // model pins all of them to one vendor — so blank is not "unset" here, it
    // is the correct answer, and the label says so.
    await addNode("llm");

    const select = await screen.findByLabelText(/^model$/i);
    expect(select.value).toBe("");
    expect(screen.getByText("Each workspace's own default")).toBeInTheDocument();
    expect(screen.getByText(/each workspace runs this on the model it has chosen/i))
      .toBeInTheDocument();
  });

  it("still honours options the runtime published", async () => {
    // Two sources, one control. `job.target` is the runtime's own enum.
    await addNode("job");

    const select = await screen.findByLabelText(/^target$/i);
    expect(select.tagName).toBe("SELECT");
    expect([...select.options].map((o) => o.value)).toEqual(["", "JOB", "WORKFLOW"]);
  });

  it("gives a number field a number control with sane bounds", async () => {
    await addNode("llm");

    const temperature = await screen.findByLabelText(/^temperature$/i);
    expect(temperature).toHaveAttribute("type", "number");
    expect(temperature).toHaveAttribute("min", "0");
    expect(temperature).toHaveAttribute("max", "2");
  });

  it("shows a structured field's SHAPE, not the word JSON", async () => {
    await addNode("llm");

    const prompt = await screen.findByLabelText(/prompt/i);
    expect(prompt.tagName).toBe("TEXTAREA");
    expect(prompt.placeholder).toContain('"role"');
    expect(prompt.placeholder).not.toBe("JSON");
  });

  it("falls back to free text when the model list cannot be read", async () => {
    // An unreachable model list is a reason to type a name, not a reason to be
    // unable to author a workflow at all.
    apiMock.listWorkspaceModels.mockRejectedValue(new Error("offline"));
    await addNode("llm");

    await waitFor(async () => {
      const model = await screen.findByLabelText(/^model$/i);
      expect(model.tagName).toBe("INPUT");
    });
  });
});
