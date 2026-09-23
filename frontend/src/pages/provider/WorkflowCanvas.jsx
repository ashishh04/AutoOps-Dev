import React, { useCallback, useMemo } from "react";
import {
  ReactFlow,
  Background,
  Controls,
  MiniMap,
  addEdge,
  applyNodeChanges,
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import { positioned } from "./workflowGraph";

/**
 * The same draft, drawn.
 *
 * <h2>A view, not a second editor</h2>
 * This renders the designer's `draft` and reports changes back. It holds no
 * state of its own — no copy of the nodes, no separate edge list — because two
 * editors over one document is how the list view and the canvas come to
 * disagree about what the workflow is. Toggling between them is therefore free
 * and lossless: they are the same object seen twice.
 *
 * <h2>What it is for</h2>
 * It does not widen what can be built: same node types, same fields, same
 * validation, all still supplied by the runtime's published schema. What it
 * adds is legibility, and most of that value lands on one node type —
 * `condition`, whose two branches are genuinely hard to follow as rows in a
 * list and obvious as two lines leaving a box.
 *
 * <h2>Why dragging is safe</h2>
 * Positions are written into the definition, where the runtime ignores them.
 * See `workflowGraph.js` for why that is sound rather than merely convenient.
 */

/** Colour per node type, so shape is readable before any label is. */
const TONE = {
  start: "border-emerald-300 bg-emerald-50",
  end: "border-slate-300 bg-slate-100",
  condition: "border-amber-300 bg-amber-50",
  job: "border-violet-300 bg-violet-50",
  platform: "border-sky-300 bg-sky-50",
  llm: "border-blue-300 bg-blue-50",
  http: "border-indigo-300 bg-indigo-50",
  template: "border-slate-300 bg-white",
};

export default function WorkflowCanvas({
  draft,
  schema,
  selected,
  onSelect,
  onMove,
  onConnect,
  onDisconnect,
}) {
  /**
   * The draft's nodes as React Flow wants them.
   *
   * A node missing a required field is marked here rather than only in the list
   * view. On a canvas the fields are behind a click, so an incomplete node is
   * otherwise indistinguishable from a finished one — and the first sign would
   * be the runtime refusing the whole graph on save, naming one id.
   */
  const nodes = useMemo(() => {
    const placed = positioned(draft.nodes, draft.edges);
    return placed.map((node) => {
      const spec = schema.node_types.find((t) => t.type === node.type);
      const missing = (spec?.fields || []).filter(
        (f) => f.required && !String(node.values?.[f.name] ?? "").trim(),
      );
      return {
        id: node.id,
        position: { x: node.x, y: node.y },
        data: { node, missing },
        type: "autoops",
        selected: node.id === selected,
      };
    });
  }, [draft.nodes, draft.edges, schema, selected]);

  const edges = useMemo(
    () =>
      draft.edges
        .filter((e) => e.source && e.target)
        .map((e, i) => ({
          id: `${e.source}->${e.target}-${i}`,
          source: e.source,
          target: e.target,
          // The branch IS the label. An unlabelled pair of lines out of a
          // condition node is the one thing a canvas must not leave ambiguous,
          // because both look identical and only one of them runs.
          label: e.branch || undefined,
          data: { index: i },
        })),
    [draft.edges],
  );

  const nodeTypes = useMemo(
    () => ({
      autoops: ({ data, selected: isSelected }) => (
        <div
          className={`min-w-[150px] rounded-xl border-2 px-3 py-2 shadow-sm transition ${
            TONE[data.node.type] || "border-slate-300 bg-white"
          } ${isSelected ? "ring-2 ring-violet-400" : ""}`}
        >
          <div className="text-[10px] font-semibold uppercase tracking-wide text-slate-500">
            {data.node.type}
          </div>
          <div className="font-mono text-xs text-slate-900">{data.node.id}</div>
          {data.node.values?.title && (
            <div className="mt-0.5 truncate text-[11px] text-slate-600">
              {data.node.values.title}
            </div>
          )}
          {data.missing.length > 0 && (
            <div className="mt-1 text-[10px] font-medium text-rose-600">
              needs {data.missing.map((f) => f.name).join(", ")}
            </div>
          )}
        </div>
      ),
    }),
    [],
  );

  /**
   * Only a finished drag is reported.
   *
   * React Flow emits a position change per pointer move. Pushing each one into
   * the draft would rewrite the definition dozens of times a second and make
   * every drag its own undo step.
   */
  const handleNodesChange = useCallback(
    (changes) => {
      for (const change of changes) {
        if (change.type === "position" && change.dragging === false && change.position) {
          onMove(change.id, change.position);
        }
        if (change.type === "select") {
          onSelect(change.selected ? change.id : null);
        }
      }
      // applyNodeChanges is not called: `nodes` is derived from the draft on
      // every render, so applying changes to a local copy would create the
      // second source of truth this component exists to avoid.
      void applyNodeChanges;
      void addEdge;
    },
    [onMove, onSelect],
  );

  return (
    <div className="h-[560px] w-full" data-testid="workflow-canvas">
      <ReactFlow
        nodes={nodes}
        edges={edges}
        nodeTypes={nodeTypes}
        onNodesChange={handleNodesChange}
        onConnect={(connection) => onConnect(connection.source, connection.target)}
        onEdgesDelete={(removed) =>
          removed.forEach((e) => onDisconnect(e.data?.index))
        }
        onNodeClick={(_, node) => onSelect(node.id)}
        onPaneClick={() => onSelect(null)}
        fitView
        proOptions={{ hideAttribution: false }}
      >
        <Background />
        <Controls />
        <MiniMap pannable zoomable />
      </ReactFlow>
    </div>
  );
}
