import { describe, it, expect } from "vitest";
import { depths, layout, positioned, COLUMN_GAP, ROW_GAP } from "./workflowGraph";

/**
 * The canvas is a third-party component; these are the parts that are ours.
 *
 * Nothing here renders anything. What can actually go wrong is the arithmetic —
 * a graph drawn in an order it does not have, a dragged node snapping back, or
 * a draft mid-edit hanging the tab — and all of that is decidable from data.
 */

const chain = {
  nodes: [{ id: "start" }, { id: "gather" }, { id: "report" }, { id: "end" }],
  edges: [
    { source: "start", target: "gather" },
    { source: "gather", target: "report" },
    { source: "report", target: "end" },
  ],
};

describe("depths", () => {
  it("places each step one column further than the one before it", () => {
    const d = depths(chain.nodes, chain.edges);
    expect([...d.values()]).toEqual([0, 1, 2, 3]);
  });

  it("uses the LONGEST path, so no edge ever runs backwards", () => {
    // `end` is reachable straight from `start` and also through two others. At
    // shortest-path it would sit in column 1 with an edge looping back from
    // column 2 — a drawing that implies an order the graph does not have.
    const nodes = [{ id: "start" }, { id: "a" }, { id: "b" }, { id: "end" }];
    const edges = [
      { source: "start", target: "a" },
      { source: "a", target: "b" },
      { source: "b", target: "end" },
      { source: "start", target: "end" },
    ];

    expect(depths(nodes, edges).get("end")).toBe(3);
  });

  it("puts two branches of a condition in the same column", () => {
    const nodes = [{ id: "check" }, { id: "yes" }, { id: "no" }];
    const edges = [
      { source: "check", target: "yes", branch: "true" },
      { source: "check", target: "no", branch: "false" },
    ];
    const d = depths(nodes, edges);

    expect(d.get("yes")).toBe(1);
    expect(d.get("no")).toBe(1);
  });

  it("settles a cyclic draft instead of looping forever", () => {
    // The runtime refuses a cycle, but a DRAFT has one the moment somebody
    // connects B back to A before deleting the old edge. Recursion here would
    // hang the tab on a perfectly ordinary intermediate state.
    const nodes = [{ id: "a" }, { id: "b" }, { id: "c" }];
    const edges = [
      { source: "a", target: "b" },
      { source: "b", target: "c" },
      { source: "c", target: "a" },
    ];

    const d = depths(nodes, edges);
    expect(d.size).toBe(3);
    for (const value of d.values()) expect(Number.isFinite(value)).toBe(true);
  });

  it("ignores an edge pointing at a node that was deleted", () => {
    // Removing a node prunes its edges, but an imported definition can name one
    // that is not there — and that must not throw while somebody is typing.
    const nodes = [{ id: "start" }, { id: "end" }];
    const edges = [
      { source: "start", target: "end" },
      { source: "ghost", target: "end" },
    ];

    expect(depths(nodes, edges).get("end")).toBe(1);
  });
});

describe("layout", () => {
  it("spreads a chain across columns rather than stacking it", () => {
    // The failure this prevents: all fourteen repo-authored workflows have no
    // coordinates, so without this every node would draw at the origin in one
    // illegible pile.
    const p = layout(chain.nodes, chain.edges);

    expect(p.get("start")).toEqual({ x: 0, y: 0 });
    expect(p.get("gather")).toEqual({ x: COLUMN_GAP, y: 0 });
    expect(p.get("end")).toEqual({ x: 3 * COLUMN_GAP, y: 0 });
  });

  it("stacks nodes that share a column", () => {
    const nodes = [{ id: "check" }, { id: "yes" }, { id: "no" }];
    const edges = [
      { source: "check", target: "yes" },
      { source: "check", target: "no" },
    ];
    const p = layout(nodes, edges);

    expect(p.get("yes")).toEqual({ x: COLUMN_GAP, y: 0 });
    expect(p.get("no")).toEqual({ x: COLUMN_GAP, y: ROW_GAP });
  });
});

describe("positioned", () => {
  it("leaves a node that was already placed exactly where it was", () => {
    // The whole point of storing geometry. Recomputing every position on each
    // render would undo a drag the moment anything else changed.
    const nodes = [
      { id: "start", x: 40, y: 900 },
      { id: "end" },
    ];

    const [start] = positioned(nodes, [{ source: "start", target: "end" }]);
    expect(start).toMatchObject({ x: 40, y: 900 });
  });

  it("places only the nodes that have no position", () => {
    // The common case, and the reason this is per-node: open a repo-authored
    // workflow, drag two boxes, add a third.
    const nodes = [
      { id: "start", x: 40, y: 900 },
      { id: "end" },
    ];

    const [, end] = positioned(nodes, [{ source: "start", target: "end" }]);
    expect(end).toEqual({ id: "end", x: COLUMN_GAP, y: 0 });
  });

  it("does not treat a half-placed node as placed", () => {
    // A hand-edited definition carrying x and no y would otherwise produce
    // NaN coordinates, and React Flow silently drops a node it cannot place.
    const [node] = positioned([{ id: "solo", x: 10 }], []);

    expect(Number.isFinite(node.x)).toBe(true);
    expect(Number.isFinite(node.y)).toBe(true);
  });

  it("keeps a legitimate zero rather than reading it as absent", () => {
    // `0` is falsy, and the obvious `node.x || computed.x` would quietly move
    // every node an author had deliberately put against the left edge.
    const [node] = positioned([{ id: "solo", x: 0, y: 0 }], []);

    expect(node).toMatchObject({ x: 0, y: 0 });
  });
});
