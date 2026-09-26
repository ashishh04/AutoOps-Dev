import React from "react";
import { render, screen, fireEvent } from "@testing-library/react";
import { describe, it, expect, vi, beforeEach } from "vitest";

/**
 * React Flow is mocked, and only React Flow.
 *
 * Asserting that a third-party library draws a box is not this file's business,
 * and under jsdom it cannot draw one anyway — it measures its container, finds
 * zero, and renders nothing. What IS ours is the mapping either side of it:
 * which nodes get handed over, where they are placed, what the box says, and
 * what a drag or a connection does to the draft. The mock renders each node
 * through the real `nodeTypes` renderer, so the node body under test is the one
 * that ships.
 */
vi.mock("@xyflow/react", () => ({
  ReactFlow: ({ nodes, edges, nodeTypes, onConnect, onNodesChange, onNodeClick }) => {
    const Node = nodeTypes.autoops;
    return (
      <div data-testid="rf">
        {nodes.map((n) => (
          <div key={n.id} data-testid={`node-${n.id}`} data-pos={`${n.position.x},${n.position.y}`}>
            <Node data={n.data} selected={n.selected} />
            <button onClick={() => onNodeClick({}, n)}>select {n.id}</button>
            <button
              onClick={() =>
                onNodesChange([
                  { type: "position", id: n.id, dragging: false, position: { x: 11, y: 22 } },
                ])
              }
            >
              drop {n.id}
            </button>
            <button
              onClick={() =>
                onNodesChange([
                  { type: "position", id: n.id, dragging: true, position: { x: 99, y: 99 } },
                ])
              }
            >
              drag {n.id}
            </button>
            <button onClick={() => onNodesChange([{ type: "remove", id: n.id }])}>
              backspace {n.id}
            </button>
          </div>
        ))}
        {edges.map((e) => (
          <div key={e.id} data-testid={`edge-${e.source}-${e.target}`}>
            {e.label || "(no branch)"}
          </div>
        ))}
        <button onClick={() => onConnect({ source: "start", target: "end" })}>connect</button>
        <button
          onClick={() =>
            onConnect({ source: "gate", target: "end", sourceHandle: "false" })
          }
        >
          connect false arm
        </button>
      </div>
    );
  },
  Background: () => null,
  Controls: () => null,
  MiniMap: () => null,
  // A custom node draws its OWN connection points. The real Handle renders a
  // positioned div; here it renders a marker carrying the two things the node
  // decides — which side it is and, for a condition, which branch it starts.
  Handle: ({ type, id }) => (
    <span data-testid={`handle-${type}${id ? `-${id}` : ""}`} />
  ),
  Position: { Left: "left", Right: "right" },
  addEdge: vi.fn(),
  // Minimal but REAL: the canvas now applies changes to a render mirror so a
  // drag is smooth, and a mock returning undefined would hide whether it does.
  applyNodeChanges: (changes, nodes) => {
    let next = nodes;
    for (const change of changes) {
      if (change.type === "position" && change.position) {
        next = next.map((n) =>
          n.id === change.id ? { ...n, position: change.position } : n,
        );
      }
      if (change.type === "remove") {
        next = next.filter((n) => n.id !== change.id);
      }
    }
    return next;
  },
}));

const { default: WorkflowCanvas } = await import("./WorkflowCanvas");
const { COLUMN_GAP } = await import("./workflowGraph");

const SCHEMA = {
  node_types: [
    { type: "start", summary: "s", contributes: [], fields: [] },
    {
      type: "http",
      summary: "h",
      contributes: [],
      fields: [
        { name: "url", wire_name: "url", type: "text", required: true },
        { name: "title", wire_name: "title", type: "text", required: false },
      ],
    },
    { type: "end", summary: "e", contributes: [], fields: [] },
  ],
};

const handlers = {
  onSelect: vi.fn(),
  onMove: vi.fn(),
  onConnect: vi.fn(),
  onDelete: vi.fn(),
  onDisconnect: vi.fn(),
};

const draftWith = (nodes, edges = []) => ({ nodes, edges, inputs: [] });

const renderCanvas = (draft, selected = null) =>
  render(
    <WorkflowCanvas draft={draft} schema={SCHEMA} selected={selected} {...handlers} />,
  );

beforeEach(() => vi.clearAllMocks());

describe("WorkflowCanvas", () => {
  it("lays out a definition that has never had coordinates", () => {
    // Every workflow in the repo is generated from a Python body and carries no
    // geometry. Without a layout pass all of them draw at the origin in one
    // pile, which is what makes a canvas useless on the existing catalog rather
    // than merely unhelpful.
    const draft = draftWith(
      [
        { id: "start", type: "start", values: {} },
        { id: "end", type: "end", values: {} },
      ],
      [{ source: "start", target: "end" }],
    );

    renderCanvas(draft);

    expect(screen.getByTestId("node-start")).toHaveAttribute("data-pos", "0,0");
    expect(screen.getByTestId("node-end")).toHaveAttribute("data-pos", `${COLUMN_GAP},0`);
  });

  it("leaves a node that carries a position exactly where it was put", () => {
    const draft = draftWith([{ id: "start", type: "start", values: {}, x: 640, y: 80 }]);

    renderCanvas(draft);

    expect(screen.getByTestId("node-start")).toHaveAttribute("data-pos", "640,80");
  });

  it("marks a node that is missing a required field", () => {
    // On a canvas the fields are behind a click, so an incomplete node is
    // otherwise indistinguishable from a finished one — and the first sign
    // would be the runtime refusing the whole graph on save, naming one id.
    const draft = draftWith([{ id: "call", type: "http", values: { title: "Fetch" } }]);

    renderCanvas(draft);

    expect(screen.getByText("needs url")).toBeInTheDocument();
  });

  it("does not mark a node whose required field is filled", () => {
    const draft = draftWith([
      { id: "call", type: "http", values: { url: "https://example.invalid" } },
    ]);

    renderCanvas(draft);

    expect(screen.queryByText(/^needs /)).not.toBeInTheDocument();
  });

  it("treats whitespace as unfilled", () => {
    // A field holding " " passes a truthiness check and fails the runtime's.
    const draft = draftWith([{ id: "call", type: "http", values: { url: "   " } }]);

    renderCanvas(draft);

    expect(screen.getByText("needs url")).toBeInTheDocument();
  });

  it("labels an edge with its branch, and says when there is none", () => {
    // Two unlabelled lines out of a condition node look identical and only one
    // of them runs. That ambiguity is the one thing a canvas must not add.
    const draft = draftWith(
      [
        { id: "check", type: "start", values: {} },
        { id: "yes", type: "end", values: {} },
        { id: "no", type: "end", values: {} },
      ],
      [
        { source: "check", target: "yes", branch: "true" },
        { source: "check", target: "no" },
      ],
    );

    renderCanvas(draft);

    expect(screen.getByTestId("edge-check-yes")).toHaveTextContent("true");
    expect(screen.getByTestId("edge-check-no")).toHaveTextContent("(no branch)");
  });

  it("reports only a finished drag, never the moves on the way", () => {
    // React Flow emits a position change per pointer move. Pushing each into
    // the draft would rewrite the definition dozens of times a second.
    const draft = draftWith([{ id: "start", type: "start", values: {} }]);
    renderCanvas(draft);

    fireEvent.click(screen.getByText("drag start"));
    expect(handlers.onMove).not.toHaveBeenCalled();

    fireEvent.click(screen.getByText("drop start"));
    expect(handlers.onMove).toHaveBeenCalledWith("start", { x: 11, y: 22 });
  });

  it("reports a connection drawn between two nodes", () => {
    const draft = draftWith([
      { id: "start", type: "start", values: {} },
      { id: "end", type: "end", values: {} },
    ]);
    renderCanvas(draft);

    fireEvent.click(screen.getByText("connect"));

    // The third argument is the branch, taken from the handle that was
    // dragged. A plain node has no handle id, so it connects with no branch.
    expect(handlers.onConnect).toHaveBeenCalledWith("start", "end", "");
  });

  it("gives every node the connection points a drag needs", () => {
    // A custom React Flow node draws its own handles. Without them there is
    // nothing to start a drag from and nothing to drop onto, so onConnect can
    // never fire — the canvas renders perfectly and cannot be drawn on, which
    // is exactly how it shipped.
    renderCanvas({
      nodes: [
        { id: "start", type: "start", values: {} },
        { id: "w", type: "llm", values: { prompt: "x" } },
        { id: "end", type: "end", values: {} },
      ],
      edges: [],
    });

    const within = (id) => screen.getByTestId(`node-${id}`);
    // start is only ever a source; end is only ever a target. Offering the
    // other handle invites an edge the runtime refuses at save time.
    expect(within("start").querySelector('[data-testid="handle-source"]')).toBeTruthy();
    expect(within("start").querySelector('[data-testid="handle-target"]')).toBeNull();
    expect(within("end").querySelector('[data-testid="handle-target"]')).toBeTruthy();
    expect(within("end").querySelector('[data-testid="handle-source"]')).toBeNull();
    // Everything in between is both.
    expect(within("w").querySelector('[data-testid="handle-target"]')).toBeTruthy();
    expect(within("w").querySelector('[data-testid="handle-source"]')).toBeTruthy();
  });

  it("gives a condition two labelled source handles, one per branch", () => {
    // This is the node the canvas exists for. Two unlabelled lines out of one
    // point is the ambiguity it is meant to remove, so the branch is chosen by
    // WHICH handle you drag from.
    renderCanvas({
      nodes: [{ id: "gate", type: "condition", values: { when: "x", equals: "y" } }],
      edges: [],
    });

    const gate = screen.getByTestId("node-gate");
    expect(gate.querySelector('[data-testid="handle-source-true"]')).toBeTruthy();
    expect(gate.querySelector('[data-testid="handle-source-false"]')).toBeTruthy();
    expect(screen.getByText("true")).toBeInTheDocument();
    expect(screen.getByText("false")).toBeInTheDocument();
  });

  it("reports which branch a line left a condition by", () => {
    renderCanvas({
      nodes: [
        { id: "gate", type: "condition", values: {} },
        { id: "end", type: "end", values: {} },
      ],
      edges: [],
    });

    fireEvent.click(screen.getByText("connect false arm"));

    expect(handlers.onConnect).toHaveBeenCalledWith("gate", "end", "false");
  });

  it("moves the box under the cursor while the drag is still happening", () => {
    // The bug this replaces: positions were derived from a draft that only
    // updated on pointer-up, so the node sat frozen for the whole drag and
    // then teleported. That reads as lag, and waiting never fixed it — those
    // frames were never coming.
    renderCanvas({
      nodes: [{ id: "start", type: "start", values: {} }],
      edges: [],
    });

    fireEvent.click(screen.getByText("drag start"));

    expect(screen.getByTestId("node-start")).toHaveAttribute("data-pos", "99,99");
    // ...and the DEFINITION is still untouched. Writing every intermediate
    // position would rewrite it dozens of times a second.
    expect(handlers.onMove).not.toHaveBeenCalled();
  });

  it("offers a delete button on the node itself", () => {
    // Deleting the thing you are looking at should not mean scrolling to a
    // different control below the canvas to do it.
    renderCanvas({
      nodes: [{ id: "gate", type: "condition", values: {} }],
      edges: [],
    });

    fireEvent.click(screen.getByLabelText("Delete gate"));

    expect(handlers.onDelete).toHaveBeenCalledWith("gate");
  });

  it("deletes on the keyboard too, not only the button", () => {
    renderCanvas({
      nodes: [{ id: "w", type: "llm", values: { prompt: "x" } }],
      edges: [],
    });

    fireEvent.click(screen.getByText("backspace w"));

    expect(handlers.onDelete).toHaveBeenCalledWith("w");
  });

  it("does not hand React Flow an edge that is only half drawn", () => {
    // The list view creates an edge row with empty source and target, which is
    // a perfectly normal half-finished state there and a crash here.
    const draft = draftWith(
      [{ id: "start", type: "start", values: {} }],
      [{ source: "", target: "", branch: "" }],
    );

    renderCanvas(draft);

    expect(screen.queryByTestId(/^edge-/)).not.toBeInTheDocument();
  });
});
