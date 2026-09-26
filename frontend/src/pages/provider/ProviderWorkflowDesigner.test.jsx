import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

/**
 * What this file protects is that the designer is a VIEW of the runtime's
 * contract rather than a second copy of it.
 *
 * Every assertion below is about that: the palette comes from the response, the
 * required markers come from the response, the wire names come from the
 * response, and when the response is missing the page says so instead of
 * falling back to something it remembers. A designer that quietly used a
 * built-in palette would let somebody author a workflow the runtime refuses and
 * tell them nothing until a customer ran it.
 */

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
  providerAuthoringSchema: vi.fn(),
  providerValidateWorkflow: vi.fn(),
  providerCreateLibrary: vi.fn(),
  providerUpdateLibrary: vi.fn(),
  providerLibrary: vi.fn(),
  listWorkspaceModels: vi.fn(),
};
vi.mock("../../lib/api", () => ({ api: apiMock }));

/**
 * The canvas is stubbed here on purpose. This file is about the DESIGNER — that
 * both views edit one draft and that switching loses nothing. WorkflowCanvas
 * has its own tests for what it draws; duplicating them through this page would
 * assert the same thing twice and fail in two places for one cause.
 */
vi.mock("./WorkflowCanvas", () => ({
  default: ({ draft, selected, onSelect, onMove }) => (
    <div data-testid="canvas">
      <span data-testid="canvas-nodes">{draft.nodes.map((n) => n.id).join(",")}</span>
      <span data-testid="canvas-selected">{selected || "none"}</span>
      {draft.nodes.map((n) => (
        <button key={n.id} onClick={() => onSelect(n.id)}>
          pick {n.id}
        </button>
      ))}
      <button onClick={() => onMove("start", { x: 300, y: 120 })}>move start</button>
    </div>
  ),
}));

const { default: ProviderWorkflowDesigner } = await import("./ProviderWorkflowDesigner");

/**
 * The palette as the runtime publishes it.
 *
 * `windowHours` differs from `window_hours` deliberately — the alias is the
 * whole point of one of the tests below, and a fixture where the two matched
 * could not catch the bug.
 */
const SCHEMA = {
  workflow: {
    input_types: ["text", "paragraph", "select", "number", "boolean"],
    reference_syntax: "{{#nodeId.field#}}",
    node_types: [
      { type: "start", summary: "Where the run begins.", contributes: [],
        fields: [{ name: "title", wire_name: "title", type: "text", required: false }] },
      { type: "platform", summary: "Read the platform's own record.",
        contributes: ["timeline", "incidents"],
        fields: [
          { name: "source", wire_name: "source", type: "text", required: false,
            options: ["timeline", "incidents"] },
          { name: "window_hours", wire_name: "windowHours", type: "number_or_reference",
            required: false, min: 1, max: 168 },
        ] },
      { type: "http", summary: "Call an external endpoint.", contributes: ["body"],
        fields: [
          { name: "url", wire_name: "url", type: "text", required: true },
          { name: "headers", wire_name: "headers", type: "map", required: false },
        ] },
      { type: "llm", summary: "Ask a model.", contributes: ["text"],
        fields: [
          { name: "prompt", wire_name: "prompt", type: "list", required: true },
          { name: "model", wire_name: "model", type: "text", required: false },
          { name: "temperature", wire_name: "temperature", type: "number", required: false },
        ] },
      { type: "end", summary: "Where the run finishes.", contributes: [],
        fields: [{ name: "title", wire_name: "title", type: "text", required: false }] },
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

beforeEach(() => {
  params = {};
  navigate.mockClear();
  storeState.pushToast.mockClear();
  apiMock.providerAuthoringSchema.mockResolvedValue(SCHEMA);
  apiMock.providerValidateWorkflow.mockResolvedValue({
    valid: true, unavailable: false, error: null, inputs: [], problems: [],
  });
  apiMock.providerCreateLibrary.mockResolvedValue({ id: 51 });
  apiMock.providerUpdateLibrary.mockResolvedValue({ id: 7 });
  apiMock.providerLibrary.mockResolvedValue([]);
  // The model palette cannot come from the runtime's schema — it has no idea
  // which vendors a workspace has connected — so it is fetched separately.
  apiMock.listWorkspaceModels.mockResolvedValue([
    {
      kind: "BEDROCK",
      providerName: "AWS Bedrock",
      models: ["deepseek.v3.2", "amazon.nova-pro-v1:0", "titan-embed-v2"],
      modelsByPurpose: { CHAT: ["deepseek.v3.2", "amazon.nova-pro-v1:0"] },
    },
  ]);
});

describe("ProviderWorkflowDesigner", () => {
  it("offers exactly the node types the runtime published", async () => {
    // Not a list in this file. A palette hardcoded in the frontend is a second
    // copy of an enum that changes in Python, and it goes stale silently — a
    // missing entry reads as a design decision rather than a bug.
    renderPage();

    for (const type of ["start", "platform", "http", "end"]) {
      expect(await screen.findByRole("button", { name: `+ ${type}` })).toBeInTheDocument();
    }
    // And nothing it did not publish. `job` is real in the runtime and absent
    // from this fixture; if it appeared, the palette came from somewhere else.
    expect(screen.queryByRole("button", { name: "+ job" })).not.toBeInTheDocument();
  });

  it("writes each field under its WIRE name, not the Python one", async () => {
    // `window_hours` travels as `windowHours`. A definition carrying the Python
    // name parses, validates, and then silently runs with the default — the
    // worst available failure, because nothing reports it.
    renderPage();
    fireEvent.change(await screen.findByLabelText("Name"), {
      target: { value: "Incident context" },
    });
    fireEvent.click(screen.getByRole("button", { name: "+ platform" }));
    fireEvent.change(screen.getByLabelText("window_hours"), { target: { value: "6" } });
    fireEvent.click(screen.getByText("Publish to catalog"));

    await waitFor(() => expect(apiMock.providerCreateLibrary).toHaveBeenCalled());
    const spec = JSON.parse(apiMock.providerCreateLibrary.mock.calls[0][0].definition);
    expect(spec.nodes[0].windowHours).toBe(6);
    expect(spec.nodes[0]).not.toHaveProperty("window_hours");
  });

  it("keeps a reference in the window field instead of coercing it to a number", async () => {
    // The window is the knob an operator most often needs per run. Coerced with
    // Number() a reference becomes NaN and the node silently falls back to the
    // default — a workflow that looks parameterised and is not.
    renderPage();
    fireEvent.change(await screen.findByLabelText("Name"), { target: { value: "W" } });
    fireEvent.click(screen.getByRole("button", { name: "+ platform" }));
    fireEvent.change(screen.getByLabelText("window_hours"), {
      target: { value: "{{#start.Hours#}}" },
    });
    fireEvent.click(screen.getByText("Publish to catalog"));

    await waitFor(() => expect(apiMock.providerCreateLibrary).toHaveBeenCalled());
    const spec = JSON.parse(apiMock.providerCreateLibrary.mock.calls[0][0].definition);
    expect(spec.nodes[0].windowHours).toBe("{{#start.Hours#}}");
  });

  it("marks a field required exactly when the runtime said so", async () => {
    renderPage();
    fireEvent.click(await screen.findByRole("button", { name: "+ http" }));

    // The asterisk is rendered next to the required field's label and not the
    // optional one. Both halves: a page that starred everything would pass the
    // first assertion alone.
    expect(screen.getByText("url").textContent).toContain("*");
    expect(screen.getByText("headers").textContent).not.toContain("*");
  });

  it("refuses to publish a definition the runtime rejects", async () => {
    // A catalog entry that cannot run is worse than no entry: it is rollable,
    // and the customer is the one who finds out.
    apiMock.providerValidateWorkflow.mockResolvedValue({
      valid: false, unavailable: false,
      error: "a workflow needs exactly one start node, found 0",
      problems: [],
    });
    renderPage();
    fireEvent.change(await screen.findByLabelText("Name"), { target: { value: "Broken" } });
    fireEvent.click(screen.getByText("Publish to catalog"));

    await waitFor(() =>
      expect(screen.getByRole("alert")).toHaveTextContent("exactly one start node"),
    );
    expect(apiMock.providerCreateLibrary).not.toHaveBeenCalled();
  });

  it("says a draft was not checked rather than calling it invalid", async () => {
    // "The runtime is unreachable" and "your workflow is wrong" must not look
    // the same. The second sends somebody hunting a mistake they did not make.
    apiMock.providerValidateWorkflow.mockResolvedValue({
      valid: false, unavailable: true,
      error: "The agent runtime did not answer, so this draft could not be checked.",
    });
    renderPage();
    fireEvent.change(await screen.findByLabelText("Name"), { target: { value: "Draft" } });
    fireEvent.click(screen.getByText("Publish to catalog"));

    await waitFor(() =>
      expect(screen.getByRole("alert")).toHaveTextContent("could not be reached"),
    );
    expect(apiMock.providerCreateLibrary).not.toHaveBeenCalled();
  });

  it("shows unresolvable references beside a valid verdict", async () => {
    // A graph can be structurally valid and full of references that will not
    // resolve. Those fail at run time with a message nobody reads until an
    // incident, so they belong beside the verdict rather than instead of it.
    apiMock.providerValidateWorkflow.mockResolvedValue({
      valid: true, unavailable: false, error: null, inputs: [],
      problems: ["node 'summary' references {{#gather.text#}}, which no node produces"],
    });
    renderPage();
    fireEvent.click(await screen.findByText("Check"));

    await waitFor(() =>
      expect(screen.getByText(/which no node produces/)).toBeInTheDocument(),
    );
    expect(screen.getByText(/accepts the definition/)).toBeInTheDocument();
  });

  it("will not offer a node type this platform could not dispatch", async () => {
    // The failure that motivated all of this: the runtime executes a node type
    // core-service does not recognise, so a workflow built from it saves
    // cleanly and fails on its first run.
    apiMock.providerAuthoringSchema.mockResolvedValue({
      ...SCHEMA,
      undispatchable_node_types: ["platform"],
    });
    renderPage();

    await waitFor(() =>
      expect(screen.getByRole("button", { name: "+ platform" })).toBeDisabled(),
    );
    expect(screen.getByText(/cannot run workflows containing/)).toBeInTheDocument();
  });

  it("refuses to open at all when the palette could not be loaded", async () => {
    // No fallback list, deliberately. Authoring against a remembered contract
    // is how a workflow ends up using a field the runtime ignores, and the
    // author would have been shown nothing.
    apiMock.providerAuthoringSchema.mockRejectedValue(new Error("runtime unavailable"));
    renderPage();

    await waitFor(() =>
      expect(screen.getByRole("alert")).toHaveTextContent("runtime unavailable"),
    );
    expect(screen.queryByRole("button", { name: "+ start" })).not.toBeInTheDocument();
  });

  it("shows which view is ACTIVE, not which one you would switch to", async () => {
    // The control used to be one button labelled with the destination: on the
    // canvas it read "List", which is equally believable as "you are looking at
    // the list" — so clicking Canvas appeared to do nothing at all. A toggle
    // whose label is the opposite of its state is a coin flip for the reader.
    renderPage();

    const list = await screen.findByRole("button", { name: /List/ });
    const canvas = screen.getByRole("button", { name: /Canvas/ });

    // Both options are always visible, and exactly one is pressed.
    expect(list).toHaveAttribute("aria-pressed", "true");
    expect(canvas).toHaveAttribute("aria-pressed", "false");

    fireEvent.click(canvas);

    expect(canvas).toHaveAttribute("aria-pressed", "true");
    expect(list).toHaveAttribute("aria-pressed", "false");
    expect(screen.getByTestId("canvas")).toBeInTheDocument();
  });

  it("shows the same steps in either view, because there is one draft", async () => {
    // Two editors over one document is how a list view and a canvas come to
    // disagree about what the workflow is. They share state precisely so that
    // switching is free and lossless.
    renderPage();
    fireEvent.click(await screen.findByRole("button", { name: "+ start" }));
    fireEvent.click(screen.getByRole("button", { name: "+ end" }));

    expect(screen.queryByTestId("canvas")).not.toBeInTheDocument();

    fireEvent.click(screen.getByText("Canvas"));

    expect(screen.getByTestId("canvas-nodes")).toHaveTextContent("start,end");
    // The edge list belongs to the list view; the canvas draws connections.
    expect(screen.queryByText("+ Edge")).not.toBeInTheDocument();
  });

  it("keeps a dragged position when the view is switched and the workflow saved", async () => {
    // Geometry lives in the definition, where the runtime ignores it. The way
    // this breaks is not the runtime refusing it — it is the designer dropping
    // it, because serialiseNode rebuilds each node from the published field
    // list and x/y are not in that list.
    renderPage();
    fireEvent.click(await screen.findByRole("button", { name: "+ start" }));
    fireEvent.click(screen.getByText("Canvas"));
    fireEvent.click(screen.getByText("move start"));

    fireEvent.click(screen.getByText("List"));
    fireEvent.change(screen.getByLabelText("Name"), { target: { value: "Placed" } });
    fireEvent.click(screen.getByText("Publish to catalog"));

    await waitFor(() => expect(apiMock.providerCreateLibrary).toHaveBeenCalled());
    const spec = JSON.parse(apiMock.providerCreateLibrary.mock.calls[0][0].definition);
    expect(spec.nodes[0]).toMatchObject({ id: "start", x: 300, y: 120 });
  });

  it("edits a node picked on the canvas with the same form the list uses", async () => {
    // One implementation of the field editor. Two would drift, and the one that
    // drifted would be the one silently writing the wrong wire name.
    renderPage();
    fireEvent.click(await screen.findByRole("button", { name: "+ http" }));
    fireEvent.click(screen.getByText("Canvas"));
    fireEvent.click(screen.getByText("pick http"));

    // /^url/ rather than "url": a required field's label carries the asterisk,
    // so its accessible name is "url*" and an exact match never finds it.
    fireEvent.change(screen.getByLabelText(/^url/), {
      target: { value: "https://example.invalid/hook" },
    });

    fireEvent.click(screen.getByText("List"));
    fireEvent.change(screen.getByLabelText("Name"), { target: { value: "Hooked" } });
    fireEvent.click(screen.getByText("Publish to catalog"));

    await waitFor(() => expect(apiMock.providerCreateLibrary).toHaveBeenCalled());
    const spec = JSON.parse(apiMock.providerCreateLibrary.mock.calls[0][0].definition);
    expect(spec.nodes[0].url).toBe("https://example.invalid/hook");
  });

  it("drops edges that pointed at a deleted step", async () => {
    // Left behind, they produce "edge references unknown node", which reads as
    // a bug in the editor rather than a consequence of the delete just made.
    renderPage();
    fireEvent.click(await screen.findByRole("button", { name: "+ start" }));
    fireEvent.click(screen.getByRole("button", { name: "+ end" }));
    fireEvent.click(screen.getByText("+ Edge"));
    fireEvent.change(screen.getByLabelText("Edge 1 from"), { target: { value: "start" } });
    fireEvent.change(screen.getByLabelText("Edge 1 to"), { target: { value: "end" } });

    fireEvent.click(screen.getByLabelText("Remove end"));

    fireEvent.change(screen.getByLabelText("Name"), { target: { value: "Pruned" } });
    fireEvent.click(screen.getByText("Publish to catalog"));

    await waitFor(() => expect(apiMock.providerCreateLibrary).toHaveBeenCalled());
    const spec = JSON.parse(apiMock.providerCreateLibrary.mock.calls[0][0].definition);
    expect(spec.edges).toEqual([]);
    expect(spec.nodes.map((n) => n.id)).toEqual(["start"]);
  });

  it("loads a pasted definition into the editor rather than storing it unread", async () => {
    // Stored verbatim, a definition could carry fields this build does not
    // understand; the author would open it, see blanks, save, and silently drop
    // them. Reading it in makes that visible now.
    renderPage();
    fireEvent.click(await screen.findByText("Import"));
    fireEvent.change(screen.getByLabelText("Definition"), {
      target: {
        value: JSON.stringify({
          ref: "RD-221-open-incident-routing-context",
          name: "Open incident routing context",
          nodes: [
            { id: "start", type: "start" },
            { id: "ctx", type: "platform", source: "incidents", windowHours: 12 },
          ],
          edges: [{ source: "start", target: "ctx" }],
        }),
      },
    });
    fireEvent.click(screen.getByText("Load"));

    // The window arrived under its wire name and is shown in the field the
    // editor calls window_hours — the alias round-trips in both directions.
    await waitFor(() =>
      expect(screen.getByLabelText("window_hours")).toHaveValue("12"),
    );
    expect(screen.getByLabelText("Name")).toHaveValue("Open incident routing context");
    expect(screen.getByLabelText("Stable ref")).toHaveValue(
      "RD-221-open-incident-routing-context",
    );
  });

  it("imports an n8n export and NAMES what it could not convert", async () => {
    // The failure this prevents is silent and total: a converted workflow that
    // opens cleanly, validates, saves, is rollable — and does a fraction of
    // what the original did, because a third of its nodes had no equivalent
    // here and nothing said so.
    renderPage();
    fireEvent.click(await screen.findByText("Import"));
    fireEvent.change(screen.getByLabelText("Definition"), {
      target: {
        value: JSON.stringify({
          name: "Ops alerting",
          nodes: [
            { name: "Schedule", type: "n8n-nodes-base.scheduleTrigger", position: [0, 0] },
            {
              name: "HTTP Request",
              type: "n8n-nodes-base.httpRequest",
              position: [220, 0],
              parameters: { url: "https://example.invalid/s" },
            },
            { name: "Post to Slack", type: "n8n-nodes-base.slack", position: [440, 0] },
          ],
          connections: {
            Schedule: { main: [[{ node: "HTTP Request", type: "main", index: 0 }]] },
          },
        }),
      },
    });
    fireEvent.click(screen.getByText("Load"));

    expect(await screen.findByText(/Imported from n8n/)).toBeInTheDocument();
    expect(screen.getByText(/could not be converted/)).toBeInTheDocument();
    expect(screen.getByText(/Post to Slack/)).toBeInTheDocument();
    // And what DID convert is in the editor.
    expect(screen.getByLabelText(/^url/)).toHaveValue("https://example.invalid/s");
  });

  it("imports a definition written as YAML", async () => {
    // A node graph with a multi-line prompt is far easier to read and hand-edit
    // as YAML than as JSON with escaped newlines — which is the whole reason
    // generate.py exists on the repo side.
    renderPage();
    fireEvent.click(await screen.findByText("Import"));
    fireEvent.change(screen.getByLabelText("Definition"), {
      target: {
        value: [
          "ref: RD-900-yaml",
          "name: From YAML",
          "nodes:",
          "  - id: start",
          "    type: start",
          "  - id: ctx",
          "    type: platform",
          "    source: incidents",
          "edges:",
          "  - source: start",
          "    target: ctx",
        ].join(String.fromCharCode(10)),
      },
    });
    fireEvent.click(screen.getByText("Load"));

    await waitFor(() =>
      expect(screen.getByLabelText("Stable ref")).toHaveValue("RD-900-yaml"),
    );
    expect(screen.getByText(/nothing was lost/)).toBeInTheDocument();
  });

  it("refuses a file that is not a workflow rather than making an empty one", async () => {
    // An empty graph that saves is worse than an error: it is rollable.
    renderPage();
    fireEvent.click(await screen.findByText("Import"));
    fireEvent.change(screen.getByLabelText("Definition"), {
      target: { value: '{"hello":"world"}' },
    });
    fireEvent.click(screen.getByText("Load"));

    await waitFor(() =>
      expect(screen.getByRole("alert")).toHaveTextContent(/does not look like a workflow/),
    );
  });

  it("reports unreadable JSON on import instead of clearing the draft", async () => {
    renderPage();
    fireEvent.click(await screen.findByRole("button", { name: "+ start" }));
    fireEvent.click(screen.getByText("Import"));
    fireEvent.change(screen.getByLabelText("Definition"), {
      target: { value: "{not json" },
    });
    fireEvent.click(screen.getByText("Load"));

    await waitFor(() =>
      expect(screen.getByRole("alert")).toHaveTextContent(/not valid JSON/),
    );
    // The step that was already there survives. Queried by its remove control
    // rather than by the text "start", which appears twice on a start node —
    // once as the type chip and once as the id.
    expect(screen.getByLabelText("Remove start")).toBeInTheDocument();
  });
});
