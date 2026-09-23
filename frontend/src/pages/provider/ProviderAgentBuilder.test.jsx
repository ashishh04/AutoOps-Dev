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
  providerAuthoringSchema: vi.fn(),
  libraryItem: vi.fn(),
};
vi.mock("../../lib/api", () => ({ api: apiMock }));

const { default: ProviderAgentBuilder } = await import("./ProviderAgentBuilder");

// No `definition`: the catalog list does not carry bodies any more. `ref` and
// `model` arrive as columns, lifted server-side out of the definition, because
// reading one string per row used to cost a two-megabyte response.
const workflowItem = (over = {}) => ({
  id: 7,
  title: "Unused Resource Cleanup",
  description: "Lists unattached EBS volumes and deletes approved ones.",
  type: "workflow",
  category: "AWS",
  rollouts: 2,
  ref: "RD-142-unused-resource-cleanup",
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
  // The catalog read is awaited FIRST, before anything is typed. Model is a
  // <select> now, and a select silently ignores a value it has no option for —
  // so filling it before the workspace's models arrive leaves the field empty
  // and the publish blocked, with nothing on screen explaining why.
  fireEvent.click(
    await screen.findByRole("checkbox", { name: /Unused Resource Cleanup/ }),
  );
  type("Name", "EBS Cleanup Agent");
  type("Description", "Finds unattached EBS volumes and deletes approved ones.");
  type("Model", "anthropic.claude-sonnet-5");
  type("Operating instructions", PERSONA);
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
  // The palette, as the runtime publishes it. Mocked rather than imported so
  // this file tests the CONSUMER of the contract; agent-runtime's own tests
  // pin the contract to the models.
  apiMock.providerAuthoringSchema.mockResolvedValue({
    agent: {
      phases: [
        { value: "TRIAGE", ordinal: 0 },
        { value: "GATHER", ordinal: 1 },
        { value: "REPORT", ordinal: 7 },
      ],
      subject_source: {
        fields: [
          { name: "subject_kind", required: true,
            options: ["cloud_resource", "principal"] },
          { name: "items", required: true },
          { name: "id_template", required: true },
          { name: "total_field", required: false },
          { name: "truncated_field", required: false },
        ],
      },
    },
    workflow: { node_types: [], input_types: [] },
    undispatchable_node_types: [],
  });
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

  it("offers the model as a dropdown, and still lets an unlisted id be typed", async () => {
    // The escape hatch is the load-bearing half. The models listed are the ones
    // THIS workspace can see; the agent runs in the CUSTOMER's, and
    // resolveForModel refuses an id no enabled connection there offers rather
    // than quietly picking another vendor. So an id this console has never
    // heard of is a legitimate answer.
    renderPage();
    fireEvent.click(
      await screen.findByRole("checkbox", { name: /Unused Resource Cleanup/ }),
    );

    const select = screen.getByLabelText("Model");
    expect(select.tagName).toBe("SELECT");
    expect(
      screen.getByRole("option", { name: "anthropic.claude-sonnet-5" }),
    ).toBeInTheDocument();

    fireEvent.change(select, { target: { value: "__custom__" } });
    type("Model id", "bedrock.some-private-deployment");

    type("Name", "EBS Cleanup Agent");
    type("Description", "Finds unattached EBS volumes and deletes approved ones.");
    type("Operating instructions", PERSONA);
    fireEvent.click(screen.getByText("Publish to catalog"));

    await waitFor(() => expect(apiMock.providerCreateLibrary).toHaveBeenCalled());
    const spec = JSON.parse(apiMock.providerCreateLibrary.mock.calls[0][0].definition);
    expect(spec.model).toBe("bedrock.some-private-deployment");
  });

  it("can publish an agent that defers the model to each customer", async () => {
    // The default that matters for a CATALOG agent. A Python agent's manifest
    // carries anthropic.claude-sonnet-5 as a placeholder, and delivering that
    // made it the customer's production vendor choice — an estate with no
    // Anthropic connection got model_not_available on an agent nobody had
    // picked a vendor for.
    renderPage();
    fireEvent.click(
      await screen.findByRole("checkbox", { name: /Unused Resource Cleanup/ }),
    );
    fireEvent.change(screen.getByLabelText("Model"), {
      target: { value: "@tenant-default" },
    });
    type("Name", "Portable Agent");
    type("Description", "Runs on whatever the customer already pays for.");
    type("Operating instructions", PERSONA);
    fireEvent.click(screen.getByText("Publish to catalog"));

    await waitFor(() => expect(apiMock.providerCreateLibrary).toHaveBeenCalled());
    const spec = JSON.parse(apiMock.providerCreateLibrary.mock.calls[0][0].definition);
    expect(spec.model).toBe("@tenant-default");
  });

  it("does not offer the sentinel twice", async () => {
    // It has its own labelled entry; listing it among real ids as well would
    // show "@tenant-default" as though it were a model somebody could buy.
    renderPage();
    await screen.findByRole("checkbox", { name: /Unused Resource Cleanup/ });

    expect(
      screen.getAllByRole("option", { name: /own default model/ }),
    ).toHaveLength(1);
    expect(screen.queryByRole("option", { name: "@tenant-default" })).toBeNull();
  });

  it("keeps an edited agent's model selectable even when this workspace cannot see it", async () => {
    // Otherwise opening such an agent shows an empty select, and saving blanks
    // the model of a working agent without anyone touching the field.
    // 31, not 7: workflowItem() is already id 7, and the builder finds the
    // agent by id in the same list — a fixture where both share an id would
    // load the workflow and the assertion would be about the wrong row.
    params = { id: "31" };
    apiMock.providerLibrary.mockResolvedValue([workflowItem()]);
    // Fetched on its own now — see api.libraryItem.
    apiMock.libraryItem.mockResolvedValue({
      id: 31,
      title: "Existing Agent",
      description: "Already published",
      type: "agent",
      category: "AWS",
      model: "openai.gpt-private-9",
      definition: JSON.stringify({
        description: "Already published",
        model: "openai.gpt-private-9",
        instructions: PERSONA,
        tools: [],
      }),
    });

    renderPage();

    await waitFor(() =>
      expect(screen.getByLabelText("Model")).toHaveValue("openai.gpt-private-9"),
    );
  });

  it("pages the tool list instead of rendering the whole catalog", async () => {
    // Fourteen workflows made this card several screens tall and pushed the
    // panels below it out of reach.
    apiMock.providerLibrary.mockResolvedValue(
      Array.from({ length: 9 }, (_, i) =>
        workflowItem({ id: i + 1, title: `Workflow ${i + 1}`, ref: `RD-00${i + 1}` }),
      ),
    );

    renderPage();

    await screen.findByRole("checkbox", { name: /Workflow 1/ });
    expect(screen.queryByRole("checkbox", { name: /Workflow 5/ })).not.toBeInTheDocument();
    expect(screen.getByText("1–4 of 9")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Next" }));

    expect(await screen.findByRole("checkbox", { name: /Workflow 5/ })).toBeInTheDocument();
    expect(screen.queryByRole("checkbox", { name: /Workflow 1/ })).not.toBeInTheDocument();
  });

  it("keeps a tool ticked on one page selected after paging away and back", async () => {
    // The selection lives in state keyed by ref, not in the rendered rows — but
    // that is exactly the kind of thing pagination quietly breaks, and the
    // symptom is an agent published with a tool the author believed they had
    // picked.
    apiMock.providerLibrary.mockResolvedValue(
      Array.from({ length: 9 }, (_, i) =>
        workflowItem({ id: i + 1, title: `Workflow ${i + 1}`, ref: `RD-00${i + 1}` }),
      ),
    );

    renderPage();
    fireEvent.click(await screen.findByRole("checkbox", { name: /Workflow 1/ }));
    fireEvent.click(screen.getByRole("button", { name: "Next" }));
    fireEvent.click(screen.getByRole("button", { name: "Previous" }));

    expect(screen.getByRole("checkbox", { name: /Workflow 1/ })).toBeChecked();
    expect(screen.getByText("1 selected")).toBeInTheDocument();
  });

  it("counts guardrails the same way it publishes them", async () => {
    // A blank line is not a promise. Two separate splits is how the number on
    // screen and the list in the catalog come to disagree.
    renderPage();
    await fillValidAgent();
    // Blank and whitespace-only lines on purpose — they are what the two
    // splits would have disagreed about.
    type(
      "Guardrails",
      ["Report before acting.", "", "   ", "Never widen the scope.", ""].join("\n"),
    );

    expect(screen.getByText("2 promises")).toBeInTheDocument();

    fireEvent.click(screen.getByText("Publish to catalog"));
    await waitFor(() => expect(apiMock.providerCreateLibrary).toHaveBeenCalled());
    const spec = JSON.parse(apiMock.providerCreateLibrary.mock.calls[0][0].definition);
    expect(spec.guardrails).toEqual(["Report before acting.", "Never widen the scope."]);
  });

  it("clears every phase in one click, back to the default loop", async () => {
    renderPage();
    await fillValidAgent();
    fireEvent.click(screen.getByRole("button", { name: /GATHER/ }));
    fireEvent.click(screen.getByRole("button", { name: /TRIAGE/ }));
    expect(screen.getByText("2 phases")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Clear" }));

    expect(screen.getByText("single loop")).toBeInTheDocument();
    fireEvent.click(screen.getByText("Publish to catalog"));
    await waitFor(() => expect(apiMock.providerCreateLibrary).toHaveBeenCalled());
    const spec = JSON.parse(apiMock.providerCreateLibrary.mock.calls[0][0].definition);
    expect(spec.phases).toBeUndefined();
  });

  it("declares phases in the order they were picked, not the palette's order", async () => {
    // Order is meaning: the graph is built from this list, so [GATHER, TRIAGE]
    // is a different agent from [TRIAGE, GATHER]. A picker that behaved like a
    // set of checkboxes — the natural way to build one — would silently sort
    // somebody's agent back into canonical order and change what it does.
    renderPage();
    await fillValidAgent();
    fireEvent.click(await screen.findByRole("button", { name: /GATHER/ }));
    fireEvent.click(screen.getByRole("button", { name: /TRIAGE/ }));
    fireEvent.click(screen.getByText("Publish to catalog"));

    await waitFor(() => expect(apiMock.providerCreateLibrary).toHaveBeenCalled());
    const spec = JSON.parse(apiMock.providerCreateLibrary.mock.calls[0][0].definition);
    expect(spec.phases).toEqual(["GATHER", "TRIAGE"]);
  });

  it("omits phases entirely when none are picked, so the agent keeps the legacy loop", async () => {
    // Not [] and not a default list. A persona written for the un-phased loop
    // has no citation rule, and switching evidence enforcement on underneath it
    // fills its report with markers its author never accounted for. An author
    // opts in; publishing must never do it for them.
    renderPage();
    await fillValidAgent();
    fireEvent.click(screen.getByText("Publish to catalog"));

    await waitFor(() => expect(apiMock.providerCreateLibrary).toHaveBeenCalled());
    const spec = JSON.parse(apiMock.providerCreateLibrary.mock.calls[0][0].definition);
    expect(spec.phases).toBeUndefined();
  });

  it("publishes a subject declaration so the agent's findings can be reaped", async () => {
    // Without this an agent claims no coverage, and a finding nothing can check
    // is a finding nothing can ever close.
    renderPage();
    await fillValidAgent();
    fireEvent.click(await screen.findByText("+ Declare"));
    fireEvent.change(
      screen.getByLabelText("Subject kind 1 for Unused Resource Cleanup"),
      { target: { value: "cloud_resource" } },
    );
    fireEvent.change(
      screen.getByLabelText("List path 1 for Unused Resource Cleanup"),
      { target: { value: "unattached_volumes" } },
    );
    fireEvent.change(
      screen.getByLabelText("Id template 1 for Unused Resource Cleanup"),
      { target: { value: "{region}/{volume_id}" } },
    );
    fireEvent.click(screen.getByText("Publish to catalog"));

    await waitFor(() => expect(apiMock.providerCreateLibrary).toHaveBeenCalled());
    const spec = JSON.parse(apiMock.providerCreateLibrary.mock.calls[0][0].definition);
    expect(spec.tools[0].subjects).toEqual([
      {
        subject_kind: "cloud_resource",
        items: "unattached_volumes",
        id_template: "{region}/{volume_id}",
      },
    ]);
    // total_field is absent rather than empty. A total derived from the list
    // agrees with it in every case including the broken ones, so it reads as
    // verification while providing none — omitting it is the guidance.
    expect(spec.tools[0].subjects[0]).not.toHaveProperty("total_field");
  });

  it("drops a half-filled declaration rather than publishing one that enumerates nothing", async () => {
    // A declaration with no id_template does not fail — it silently enumerates
    // nothing, so the agent claims no coverage and looks like it is working.
    // Dropping it keeps the stored row honest about what was actually declared.
    renderPage();
    await fillValidAgent();
    fireEvent.click(await screen.findByText("+ Declare"));
    fireEvent.change(
      screen.getByLabelText("Subject kind 1 for Unused Resource Cleanup"),
      { target: { value: "cloud_resource" } },
    );
    fireEvent.click(screen.getByText("Publish to catalog"));

    await waitFor(() => expect(apiMock.providerCreateLibrary).toHaveBeenCalled());
    const spec = JSON.parse(apiMock.providerCreateLibrary.mock.calls[0][0].definition);
    expect(spec.tools[0]).not.toHaveProperty("subjects");
  });

  it("says the palette could not be loaded instead of offering a remembered one", async () => {
    // The dangerous failure: a built-in fallback list would let somebody author
    // against a stale contract and find out at run time, having been shown
    // nothing. Refusing to offer phases is the honest answer.
    apiMock.providerAuthoringSchema.mockRejectedValue(new Error("runtime unavailable"));
    renderPage();

    await waitFor(() =>
      expect(screen.getByRole("alert")).toHaveTextContent("runtime unavailable"),
    );
    expect(screen.queryByRole("button", { name: /GATHER/ })).not.toBeInTheDocument();
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
      workflowItem({ id: 8, title: "Refless", ref: null }),
    ]);
    renderPage();

    expect(await screen.findByText(/1 workflow is not selectable/)).toBeTruthy();
    expect(screen.queryByRole("checkbox", { name: /Refless/ })).toBeNull();
  });

  it("loads an existing agent for editing and saves through the update path", async () => {
    params = { id: "42" };
    apiMock.providerLibrary.mockResolvedValue([workflowItem()]);
    apiMock.libraryItem.mockResolvedValue(
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
    );
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
    apiMock.providerLibrary.mockResolvedValue([workflowItem()]);
    apiMock.libraryItem.mockResolvedValue({
      id: 43,
      title: "Linux Server Health Check",
      description: "Row description",
      type: "agent",
      category: "Linux",
      ref: "linux.server_health_check",
      definition: JSON.stringify({
        kind: "PYTHON",
        ref: "linux.server_health_check",
        version: "1.0.0",
      }),
    });
    renderPage();

    expect(
      await screen.findByText(/authored in code \(agent-runtime\)/),
    ).toBeTruthy();
  });
});
