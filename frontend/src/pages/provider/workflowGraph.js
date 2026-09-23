/**
 * Turning a workflow definition into something that can be drawn.
 *
 * <h2>Why any of this is needed</h2>
 * A definition is a set of nodes and a set of edges. It carries no geometry,
 * because the runtime has no use for any: it executes a graph, and where the
 * boxes sit on a screen is not part of executing it. Every workflow in the
 * repo was generated from a Python body and has never had a coordinate in its
 * life. Opened on a canvas as-is, all fourteen of them would stack at the
 * origin in one illegible pile.
 *
 * So positions are DERIVED when absent and PRESERVED when present. An author
 * who drags a node keeps where they put it; one who opens a repo-authored
 * workflow gets a readable arrangement without anyone having had to author it.
 *
 * <h2>Why it is safe to put coordinates in the definition at all</h2>
 * The runtime's `Node` model sets only `populate_by_name`, so Pydantic's
 * default `extra="ignore"` applies: `x` and `y` validate fine and are then
 * ignored. core-service stores `definition` as a verbatim string and
 * RolloutService copies it verbatim, so the geometry survives save, publish and
 * delivery without any service needing to know it is there.
 *
 * That is the whole reason a canvas is cheap to add. It is worth stating
 * because the opposite was assumed once, in this very file's neighbour, and the
 * assumption was never checked.
 */

/** Horizontal distance between one column of nodes and the next. */
export const COLUMN_GAP = 260;

/** Vertical distance between two nodes sharing a column. */
export const ROW_GAP = 120;

/**
 * Depth of every node: how many steps it sits from the start of the graph.
 *
 * Longest path rather than shortest, deliberately. A node reachable both
 * directly from `start` and through three other nodes belongs beside the three,
 * not beside the start — otherwise an edge runs backwards across the canvas and
 * the drawing implies an order the graph does not have.
 *
 * Cycles cannot appear in a definition the runtime accepts, but they appear
 * constantly in a DRAFT — the moment somebody connects B back to A before
 * deleting the old edge. So this is iterative with a hard bound rather than
 * recursive: a cycle settles at whatever depths it reached and draws as a loop,
 * instead of hanging the tab.
 */
export function depths(nodes, edges) {
  const ids = new Set(nodes.map((n) => n.id));
  const incoming = new Map(nodes.map((n) => [n.id, []]));
  for (const edge of edges) {
    if (ids.has(edge.source) && ids.has(edge.target)) {
      incoming.get(edge.target).push(edge.source);
    }
  }

  const depth = new Map(nodes.map((n) => [n.id, 0]));
  // One pass per node is enough to settle the longest path in a DAG; the bound
  // is what stops a cyclic draft from looping forever.
  for (let pass = 0; pass < nodes.length; pass += 1) {
    let moved = false;
    for (const node of nodes) {
      for (const parent of incoming.get(node.id)) {
        const candidate = depth.get(parent) + 1;
        if (candidate > depth.get(node.id)) {
          depth.set(node.id, candidate);
          moved = true;
        }
      }
    }
    if (!moved) break;
  }
  return depth;
}

/**
 * Positions for a graph that has none.
 *
 * Nodes are placed in columns by depth and stacked within a column in the order
 * they appear in the definition — which for a generated workflow is the order
 * somebody wrote them, and is therefore the most meaningful tiebreak available.
 */
export function layout(nodes, edges) {
  const depth = depths(nodes, edges);
  const perColumn = new Map();
  const positions = new Map();

  for (const node of nodes) {
    const column = depth.get(node.id) ?? 0;
    const row = perColumn.get(column) ?? 0;
    perColumn.set(column, row + 1);
    positions.set(node.id, { x: column * COLUMN_GAP, y: row * ROW_GAP });
  }
  return positions;
}

/**
 * The draft's nodes, each guaranteed a position.
 *
 * **Only fills gaps.** A node that already carries a coordinate keeps it
 * exactly — dragging a box and then adding another must not rearrange the work
 * already done. A node that does not is placed by {@link layout}.
 *
 * The mixed case is the common one and the reason this is per-node rather than
 * all-or-nothing: open a repo-authored workflow, drag two nodes, add a third.
 */
export function positioned(nodes, edges) {
  const computed = layout(nodes, edges);
  return nodes.map((node) => {
    const known =
      Number.isFinite(node.x) && Number.isFinite(node.y)
        ? { x: node.x, y: node.y }
        : computed.get(node.id);
    return { ...node, x: known.x, y: known.y };
  });
}
