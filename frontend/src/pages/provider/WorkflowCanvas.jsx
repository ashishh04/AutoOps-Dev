import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  ReactFlow,
  Background,
  Controls,
  Handle,
  MiniMap,
  Position,
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
 *
 * <h2>Handles are not decoration</h2>
 * A custom React Flow node draws its own connection points. Without a
 * `<Handle>` there is nothing to start a drag from and nothing to drop onto, so
 * `onConnect` can never fire — the canvas renders correctly and is simply
 * impossible to draw an edge on, which is how this shipped.
 *
 * A `condition` gets TWO source handles, labelled `true` and `false`, and the
 * handle's id becomes the edge's branch. That is the whole reason this view
 * earns its place: the branch is chosen by which side of the box you drag from,
 * rather than typed into a field afterwards and hoped to be right.
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
  onDelete,
}) {
  /**
   * Held in a ref, not passed through node data.
   *
   * The designer supplies this as an inline arrow, so its identity changes on
   * every render. Putting it in the `derived` memo's dependencies — or in each
   * node's `data` — would recompute the whole graph, re-run the layout and
   * replace every node object on every render, which is precisely the kind of
   * churn this component was slow from before. A ref lets the button call the
   * current handler without anything downstream depending on its identity.
   */
  const deleteRef = useRef(onDelete);
  useEffect(() => {
    deleteRef.current = onDelete;
  });
  /**
   * The draft's nodes as React Flow wants them.
   *
   * A node missing a required field is marked here rather than only in the list
   * view. On a canvas the fields are behind a click, so an incomplete node is
   * otherwise indistinguishable from a finished one — and the first sign would
   * be the runtime refusing the whole graph on save, naming one id.
   */
  // Layout is the expensive part, and it depends only on the GRAPH. Kept in its
  // own memo so that clicking a node — which changes `selected` and nothing
  // else — does not re-run it.
  const placed = useMemo(
    () => positioned(draft.nodes, draft.edges),
    [draft.nodes, draft.edges],
  );

  const derived = useMemo(() => {
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
  }, [placed, schema, selected]);

  /**
   * A render mirror of {@link derived}, so a drag is smooth.
   *
   * This is NOT the second source of truth the header warns about. The draft
   * still decides what the workflow IS; this holds only where React Flow is
   * currently drawing a box, and the effect below throws it away and re-derives
   * the moment the draft changes.
   *
   * Without it the canvas felt broken rather than merely unusual: positions
   * were derived from a draft that only updated on pointer-up, so a node stayed
   * frozen under the cursor for the whole drag and then teleported. That reads
   * as lag, and no amount of waiting fixes it — the frames were never going to
   * come.
   */
  const [view, setView] = useState(derived);
  useEffect(() => setView(derived), [derived]);

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
      autoops: ({ data, selected: isSelected }) => {
        const kind = data.node.type;

        // `start` is only ever a source and `end` only ever a target. Drawing
        // the handle anyway would invite an edge the runtime refuses at save
        // time, naming an id — a worse way to learn it than not being able to
        // draw it.
        const isStart = kind === "start";
        const isEnd = kind === "end";
        const isCondition = kind === "condition";
        return (
          <div
            className={`group relative min-w-[150px] rounded-xl border-2 px-3 py-2 shadow-sm transition ${
              TONE[kind] || "border-slate-300 bg-white"
            } ${isSelected ? "ring-2 ring-violet-400" : ""}`}
          >
            {!isStart && (
              <Handle
                type="target"
                position={Position.Left}
                className="!h-3 !w-3 !border-2 !border-white !bg-slate-400"
              />
            )}

            {/* On the node, not only in the panel below it. Deleting the
                thing you are looking at should not mean scrolling to a
                different control to do it. Backspace works too — React Flow
                emits a `remove` change, handled alongside this. */}
            <button
              type="button"
              aria-label={`Delete ${data.node.id}`}
              title={`Delete ${data.node.id}`}
              onClick={(event) => {
                // Without this the click also selects the node, and the panel
                // below flashes open for a node that is being removed.
                event.stopPropagation();
                deleteRef.current?.(data.node.id);
              }}
              // `nodrag` stops React Flow treating the press as the start of a
              // drag, which would otherwise swallow the click.
              className="nodrag absolute -right-2 -top-2 flex h-5 w-5 items-center justify-center rounded-full border border-slate-300 bg-white text-[11px] leading-none text-slate-400 opacity-0 shadow-sm transition hover:border-rose-300 hover:text-rose-600 focus:opacity-100 group-hover:opacity-100"
            >
              ×
            </button>

            <div className="text-[10px] font-semibold uppercase tracking-wide text-slate-500">
              {kind}
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

            {/* A condition's branch is chosen by WHICH handle you drag from.
                Two unlabelled lines out of one point is the exact ambiguity
                this view exists to remove. */}
            {isCondition && !isEnd && (
              <>
                <Handle
                  id="true"
                  type="source"
                  position={Position.Right}
                  style={{ top: "35%" }}
                  className="!h-3 !w-3 !border-2 !border-white !bg-emerald-500"
                />
                <span className="pointer-events-none absolute -right-9 top-[27%] text-[9px] font-semibold text-emerald-600">
                  true
                </span>
                <Handle
                  id="false"
                  type="source"
                  position={Position.Right}
                  style={{ top: "70%" }}
                  className="!h-3 !w-3 !border-2 !border-white !bg-rose-500"
                />
                <span className="pointer-events-none absolute -right-10 top-[62%] text-[9px] font-semibold text-rose-500">
                  false
                </span>
              </>
            )}

            {!isCondition && !isEnd && (
              <Handle
                type="source"
                position={Position.Right}
                className="!h-3 !w-3 !border-2 !border-white !bg-slate-400"
              />
            )}
          </div>
        );
      },
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
      // Applied to the mirror FIRST, every frame, so the box tracks the
      // pointer. The draft is written once, on pointer-up.
      setView((current) => applyNodeChanges(changes, current));
      for (const change of changes) {
        if (change.type === "position" && change.dragging === false && change.position) {
          // Only the finished drag reaches the definition. Pushing every
          // intermediate position would rewrite it dozens of times a second
          // and make each one its own undo step.
          onMove(change.id, change.position);
        }
        if (change.type === "select") {
          onSelect(change.selected ? change.id : null);
        }
        if (change.type === "remove") {
          deleteRef.current?.(change.id);
        }
      }
      void addEdge;
    },
    [onMove, onSelect],
  );

  return (
    // Tall enough to lay out a real graph without the controls falling below
    // the fold — at 560px the zoom buttons and the minimap sat under the page
    // edge, so the canvas looked like it could not be panned at all.
    <div className="h-[70vh] min-h-[520px] w-full" data-testid="workflow-canvas">
      <ReactFlow
        nodes={view}
        edges={edges}
        nodeTypes={nodeTypes}
        onNodesChange={handleNodesChange}
        // The handle carries the branch: dragging from a condition's `true`
        // point IS choosing that branch, rather than typing it afterwards.
        onConnect={(connection) =>
          onConnect(connection.source, connection.target, connection.sourceHandle || "")
        }
        onEdgesDelete={(removed) =>
          removed.forEach((e) => onDisconnect(e.data?.index))
        }
        onNodeClick={(_, node) => onSelect(node.id)}
        onPaneClick={() => onSelect(null)}
        fitView
        // Room around the graph on first paint; without it a two-node draft
        // fills the viewport and there is nowhere obvious to drop a third.
        fitViewOptions={{ padding: 0.3, maxZoom: 1 }}
        minZoom={0.2}
        maxZoom={2}
        // Scrolling the PAGE must not be hijacked by the canvas. Pan is drag,
        // zoom is ctrl+wheel — the same bargain every embedded map makes.
        panOnScroll={false}
        zoomOnScroll={false}
        zoomOnPinch
        preventScrolling={false}
        proOptions={{ hideAttribution: false }}
      >
        <Background />
        <Controls />
        <MiniMap pannable zoomable />
      </ReactFlow>
    </div>
  );
}
