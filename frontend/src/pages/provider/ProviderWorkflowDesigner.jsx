import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { PageHeader, Card, SmallButton, Chip } from "../../components/app/appui";
import Icon from "../../components/Icon";
import { api } from "../../lib/api";
import WorkflowCanvas from "./WorkflowCanvas";
import { importWorkflow } from "./workflowImport";
import { useStore } from "../../store/store";

/**
 * The workflow designer, built against the runtime's own models.
 *
 * <h2>Why this route was empty until now</h2>
 * The comment in App.jsx said it plainly: the vendor canvas that used to live
 * here was deleted with the engine behind it, and a route was not re-added
 * because an editor that produced definitions nothing runs is worse than no
 * editor. That reasoning was right, and it is what this fixes — not by drawing
 * a canvas, but by asking the runtime what it can execute and rendering exactly
 * that.
 *
 * <h2>Nothing about the palette is written here</h2>
 * No list of node types, no list of fields, no idea which are required. All of
 * it arrives from `/provider/authoring/schema`, which derives it from the
 * Pydantic models that will judge the saved definition. The required flags in
 * particular are PROBED from the runtime's own validator, so the red asterisk
 * on this page and the refusal on the server cannot disagree.
 *
 * That is not architectural taste. `NativeWorkflowService.GRAPH_NODE_TYPES` is a
 * hand-kept copy of the same enum and it has gone stale twice — once for `job`,
 * once for `platform`, the second time silently breaking the two workflows that
 * read AutoOps's own record. A third copy, in JavaScript, would rot the same
 * way and be harder to spot, because a missing palette entry looks like a
 * design decision rather than a bug.
 *
 * <h2>A list, not a canvas</h2>
 * Nodes are edited as an ordered list with explicit edges. A drag-and-drop
 * surface would be nicer to look at and would not change what can be built —
 * and building one now would mean inventing layout state the runtime has no
 * field for, which is how a definition acquires data only this editor
 * understands. The graph is what runs; the arrangement is not.
 */

const inputCls =
  "w-full rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 outline-none transition placeholder:text-slate-400 focus:border-violet-400 focus:ring-2 focus:ring-violet-400/20";

const labelCls = "mb-1 block text-xs font-semibold text-slate-700";

/** A node id the runtime will accept: it must be a Python identifier. */
const idFor = (type, existing) => {
  const base = type.replace(/[^a-z0-9]/gi, "") || "node";
  let n = 1;
  let candidate = base;
  while (existing.has(candidate)) {
    n += 1;
    candidate = `${base}${n}`;
  }
  return candidate;
};

/**
 * The editor's node back into the shape the runtime reads.
 *
 * Fields are written under their WIRE name, which for several differs from the
 * Python one — `maxTokens`, `targetId`, `windowHours`. A definition carrying
 * `max_tokens` parses, validates and then silently runs with the default, which
 * is the worst available failure because nothing reports it.
 */
function serialiseNode(node, schema) {
  const spec = schema.node_types.find((t) => t.type === node.type);
  const out = { id: node.id, type: node.type };
  // Geometry rides along in the definition. The runtime's Node model sets only
  // `populate_by_name`, so Pydantic's default extra="ignore" applies: these
  // validate and are then ignored, and core stores the definition verbatim, so
  // they survive save, publish and delivery without any service knowing.
  //
  // Written BEFORE the schema-driven fields on purpose — this loop rebuilds a
  // node from the published field list, so anything not in that list is dropped
  // unless it is put back explicitly. That is exactly how a canvas would lose
  // every position on the first save.
  if (Number.isFinite(node.x) && Number.isFinite(node.y)) {
    out.x = node.x;
    out.y = node.y;
  }
  for (const field of spec?.fields || []) {
    const value = node.values[field.name];
    if (value === undefined || value === "") continue;
    if (field.type === "number") {
      const parsed = Number(value);
      if (!Number.isNaN(parsed)) out[field.wire_name] = parsed;
      continue;
    }
    if (field.type === "number_or_reference") {
      // A bare number stays a number; anything else travels as the string it
      // is, because a {{#start.Field#}} reference is resolved at run time and
      // coercing it to NaN would silently become the default window.
      const parsed = Number(value);
      out[field.wire_name] = value.trim?.() && !Number.isNaN(parsed) ? parsed : value;
      continue;
    }
    if (field.type === "map" || field.type === "list") {
      try {
        out[field.wire_name] = JSON.parse(value);
      } catch {
        // Left out rather than sent as a string. The runtime would reject the
        // string, but the message would name a type mismatch rather than the
        // JSON typo that caused it — and the problems panel already says so.
      }
      continue;
    }
    if (field.type === "boolean") {
      out[field.wire_name] = Boolean(value);
      continue;
    }
    out[field.wire_name] = value;
  }
  return out;
}

/** The whole draft as a definition. */
export function serialiseDraft(draft, schema) {
  return {
    ref: draft.ref?.trim() || undefined,
    name: draft.name?.trim() || undefined,
    description: draft.description?.trim() || undefined,
    inputs: draft.inputs.map((i) => ({
      variable: i.variable,
      label: i.label || i.variable,
      type: i.type,
      required: Boolean(i.required),
      ...(i.options?.trim()
        ? { options: i.options.split(",").map((o) => o.trim()).filter(Boolean) }
        : {}),
    })),
    nodes: draft.nodes.map((n) => serialiseNode(n, schema)),
    edges: draft.edges
      .filter((e) => e.source && e.target)
      .map((e) => ({
        source: e.source,
        target: e.target,
        ...(e.branch?.trim() ? { branch: e.branch.trim() } : {}),
      })),
  };
}

/** An existing definition back into the editor's shape. */
function parseDraft(definition, schema) {
  const spec = typeof definition === "string" ? JSON.parse(definition) : definition;
  return {
    ref: spec.ref || "",
    name: spec.name || "",
    description: spec.description || "",
    inputs: (spec.inputs || []).map((i) => ({
      variable: i.variable || "",
      label: i.label || "",
      type: i.type || "text",
      required: Boolean(i.required),
      options: Array.isArray(i.options) ? i.options.join(", ") : "",
    })),
    nodes: (spec.nodes || []).map((n) => {
      const type = schema.node_types.find((t) => t.type === n.type);
      const values = {};
      for (const field of type?.fields || []) {
        const raw = n[field.wire_name];
        if (raw === undefined || raw === null) continue;
        values[field.name] =
          field.type === "map" || field.type === "list"
            ? JSON.stringify(raw, null, 0)
            : String(raw);
      }
      // `?? undefined` rather than `|| undefined`: a node deliberately placed
      // against the left edge has x === 0, and `||` would read that as absent
      // and move it on the next render.
      return { id: n.id, type: n.type, values, x: n.x ?? undefined, y: n.y ?? undefined };
    }),
    edges: (spec.edges || []).map((e) => ({
      source: e.source || "",
      target: e.target || "",
      branch: e.branch || "",
    })),
  };
}

/**
 * One node's fields, rendered from the runtime's published schema.
 *
 * Shared by the list view and the canvas inspector, and that sharing is the
 * point: two implementations of the same form would drift, and the one that
 * drifted would be the one silently writing the wrong wire name.
 */
function NodeFields({ node, type, onChange }) {
  return (
    <div className="grid gap-2 sm:grid-cols-2">
      {(type?.fields || []).map((field) => (
        <div key={field.name}>
          <label className={labelCls} htmlFor={`${node.id}-${field.name}`}>
            {field.name}
            {/* The asterisk comes from the runtime's own validator, probed
                rather than asserted, so it cannot disagree with what the save
                will refuse. */}
            {field.required && <span className="ml-0.5 text-rose-500">*</span>}
          </label>
          {field.options ? (
            <select
              id={`${node.id}-${field.name}`}
              className={inputCls}
              value={node.values[field.name] ?? ""}
              onChange={(e) => onChange(node.id, field.name, e.target.value)}
            >
              <option value="">—</option>
              {field.options.map((o) => (
                <option key={o} value={o}>
                  {o}
                </option>
              ))}
            </select>
          ) : (
            <input
              id={`${node.id}-${field.name}`}
              className={inputCls}
              value={node.values[field.name] ?? ""}
              placeholder={field.type === "map" || field.type === "list" ? "JSON" : ""}
              onChange={(e) => onChange(node.id, field.name, e.target.value)}
            />
          )}
          {field.why && (
            <p className="mt-1 text-[10px] leading-relaxed text-slate-500">{field.why}</p>
          )}
        </div>
      ))}
    </div>
  );
}

const EMPTY = { ref: "", name: "", description: "", inputs: [], nodes: [], edges: [] };

export default function ProviderWorkflowDesigner() {
  const { id } = useParams();
  const editing = Boolean(id);
  const navigate = useNavigate();
  const { pushToast } = useStore();

  const [schema, setSchema] = useState(null);
  const [undispatchable, setUndispatchable] = useState([]);
  const [schemaError, setSchemaError] = useState(null);
  const [draft, setDraft] = useState(EMPTY);
  const [title, setTitle] = useState("");
  const [category, setCategory] = useState("General");
  const [verdict, setVerdict] = useState(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);
  // Closed until asked for. This used to open from a ?import=1 the library
  // passed in, which existed only because the library had its own Import
  // button — two controls, one destination. That button is gone, so the query
  // parameter would now be a path nothing takes.
  const [importing, setImporting] = useState(false);
  const [importText, setImportText] = useState("");
  // The result of the last conversion: which format it was, and what could not
  // be carried across.
  const [imported, setImported] = useState(null);
  // "list" or "canvas". The two are views of ONE draft, so switching is free
  // and lossless — there is no second copy of the graph to reconcile.
  const [view, setView] = useState("list");
  // The Steps card, so switching to the canvas can bring it into view. It sits
  // below Identity and the input form, which on a fresh workflow puts it off
  // the bottom of the screen — so the canvas appeared not to open at all.
  const stepsRef = useRef(null);
  const [selected, setSelected] = useState(null);

  useEffect(() => {
    let cancelled = false;
    api
      .providerAuthoringSchema()
      .then(async (contract) => {
        if (cancelled) return;
        setSchema(contract.workflow);
        setUndispatchable(contract.undispatchable_node_types || []);
        if (!editing) return;
        // ONE definition. The catalog list stopped carrying bodies: ~240 items
        // averaging 8.6KB made it a two-megabyte response, and this screen
        // edits exactly one of them.
        const item = await api.libraryItem(id).catch(() => null);
        if (!item || cancelled) return;
        setTitle(item.title || "");
        setCategory(item.category || "General");
        try {
          setDraft(parseDraft(item.definition || "{}", contract.workflow));
        } catch {
          setError(
            "This workflow's stored definition could not be read, so the editor is " +
              "empty. Saving will replace it.",
          );
        }
      })
      .catch((err) =>
        !cancelled &&
        setSchemaError(
          err.message ||
            "The agent runtime did not answer, so the designer cannot show which node " +
              "types this build supports.",
        ),
      )
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, [editing, id]);

  useEffect(() => {
    if (view === "canvas") {
      stepsRef.current?.scrollIntoView?.({ behavior: "smooth", block: "start" });
    }
  }, [view]);

  const nodeIds = useMemo(() => draft.nodes.map((n) => n.id), [draft.nodes]);

  // Looked up by id each render rather than stored. Holding the node object in
  // state would leave the inspector editing a stale copy the moment anything
  // else changed the draft.
  const selectedNode = useMemo(
    () => draft.nodes.find((n) => n.id === selected) || null,
    [draft.nodes, selected],
  );

  const addNode = (type) =>
    setDraft((d) => ({
      ...d,
      nodes: [
        ...d.nodes,
        { id: idFor(type, new Set(d.nodes.map((n) => n.id))), type, values: {} },
      ],
    }));

  const removeNode = (nodeId) =>
    setDraft((d) => ({
      ...d,
      nodes: d.nodes.filter((n) => n.id !== nodeId),
      // Edges touching a deleted node go with it. Leaving them would produce a
      // definition the runtime refuses with "edge references unknown node",
      // which reads as a bug in the editor rather than a consequence of the
      // delete the author just did.
      edges: d.edges.filter((e) => e.source !== nodeId && e.target !== nodeId),
    }));

  const setNodeValue = (nodeId, field, value) =>
    setDraft((d) => ({
      ...d,
      nodes: d.nodes.map((n) =>
        n.id === nodeId ? { ...n, values: { ...n.values, [field]: value } } : n,
      ),
    }));

  /** A finished drag. Positions live in the definition; the runtime ignores them. */
  const moveNode = (nodeId, position) =>
    setDraft((d) => ({
      ...d,
      nodes: d.nodes.map((n) =>
        n.id === nodeId ? { ...n, x: Math.round(position.x), y: Math.round(position.y) } : n,
      ),
    }));

  /** A line drawn on the canvas is an edge, and nothing else. */
  const connect = (source, target) =>
    setDraft((d) =>
      // Dragging the same connection twice is easy and means nothing new. The
      // runtime would accept the duplicate and walk it once, so the only thing
      // a second copy changes is what the canvas draws.
      source && target &&
      !d.edges.some((e) => e.source === source && e.target === target)
        ? { ...d, edges: [...d.edges, { source, target, branch: "" }] }
        : d,
    );

  const disconnect = (index) =>
    setDraft((d) => ({ ...d, edges: d.edges.filter((_, i) => i !== index) }));

  const addEdge = () =>
    setDraft((d) => ({ ...d, edges: [...d.edges, { source: "", target: "", branch: "" }] }));

  const setEdge = (index, field, value) =>
    setDraft((d) => ({
      ...d,
      edges: d.edges.map((e, i) => (i === index ? { ...e, [field]: value } : e)),
    }));

  const removeEdge = (index) =>
    setDraft((d) => ({ ...d, edges: d.edges.filter((_, i) => i !== index) }));

  const addInput = () =>
    setDraft((d) => ({
      ...d,
      inputs: [...d.inputs, { variable: "", label: "", type: "text", required: false, options: "" }],
    }));

  const setInput = (index, field, value) =>
    setDraft((d) => ({
      ...d,
      inputs: d.inputs.map((i, n) => (n === index ? { ...i, [field]: value } : i)),
    }));

  const removeInput = (index) =>
    setDraft((d) => ({ ...d, inputs: d.inputs.filter((_, i) => i !== index) }));

  /**
   * Ask the runtime, rather than deciding here.
   *
   * The same `inspect` the execution path uses, so this verdict and the one
   * that matters are the same verdict. A second validator in the browser would
   * disagree with the real one on exactly the definitions worth checking.
   */
  const check = useCallback(async () => {
    if (!schema) return null;
    try {
      const result = await api.providerValidateWorkflow(serialiseDraft(draft, schema));
      setVerdict(result);
      return result;
    } catch (err) {
      setVerdict({ valid: false, unavailable: true, error: err.message });
      return null;
    }
  }, [draft, schema]);

  /**
   * A definition authored elsewhere, into the editor.
   *
   * This is the path for the workflows that live in the repo: the JSON under
   * workflows/<DOMAIN>/ is generated from Python bodies and put in the catalog
   * by publish.py, which needs a checkout and provider credentials. Pasting it
   * here does the same job from the console.
   *
   * It goes through parseDraft rather than being stored verbatim, deliberately.
   * A definition saved unread could carry fields this build does not understand
   * and would then be un-editable here — the author would open it, see blanks,
   * save, and silently drop whatever was in them.
   */
  const applyImport = (text = importText) => {
    if (!schema) return;
    let result;
    try {
      result = importWorkflow(text);
    } catch (err) {
      setError(err.message);
      return;
    }
    try {
      setDraft(parseDraft(result.definition, schema));
    } catch (err) {
      setError(`That is not a workflow definition: ${err.message}`);
      return;
    }
    if (!title.trim() && result.definition.name) setTitle(result.definition.name);
    // Kept on screen until the next import. A list of what was NOT carried is
    // the most important thing a conversion produces, and clearing it with the
    // panel would leave an author believing they had imported the whole thing.
    setImported(result);
    setError(null);
    setImporting(false);
    // Checked immediately. An imported definition is the case most likely to
    // contain something this build cannot run, and finding that out at publish
    // time would be after the author had moved on.
    setTimeout(check, 0);
  };

  /** A file, read and then handed to exactly the same path a paste takes. */
  const applyFile = (file) => {
    if (!file) return;
    const reader = new FileReader();
    reader.onerror = () => setError(`Could not read ${file.name}.`);
    reader.onload = () => {
      setImportText(String(reader.result || ""));
      applyImport(String(reader.result || ""));
    };
    reader.readAsText(file);
  };

  const save = async () => {
    if (!title.trim()) {
      setError("Give the workflow a name.");
      return;
    }
    const result = await check();
    if (!result?.valid) {
      // Refused rather than saved with a warning. A catalog entry that cannot
      // run is worse than no entry: it is rollable, and the customer finds out.
      setError(
        result?.unavailable
          ? "The runtime could not be reached to check this draft, so it has not been published."
          : result?.error || "The runtime refused this definition.",
      );
      return;
    }
    setSaving(true);
    setError(null);
    const body = {
      title: title.trim(),
      description: draft.description?.trim() || "",
      category: category.trim() || "General",
      definition: JSON.stringify(serialiseDraft(draft, schema)),
    };
    try {
      if (editing) {
        // No `type` on this one. The update endpoint takes title, description,
        // category, premium and definition — nothing else — and a catalog
        // item's type is not a thing an edit may change anyway.
        await api.providerUpdateLibrary(id, body);
      } else {
        await api.providerCreateLibrary({ ...body, type: "workflow" });
      }
      pushToast?.({ kind: "success", message: `Saved "${title.trim()}"` });
      navigate("/provider/library/workflows");
    } catch (err) {
      setError(err.message || "Could not save the workflow.");
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return (
      <div>
        <PageHeader title="Workflow designer" />
        <Card><div className="p-6 text-sm text-slate-500">Loading…</div></Card>
      </div>
    );
  }

  if (schemaError) {
    // No built-in palette. Authoring against a remembered contract is how a
    // workflow ends up using a field the runtime ignores, and the author would
    // have been shown nothing.
    return (
      <div>
        <PageHeader title="Workflow designer" />
        <Card>
          <div className="p-6 text-sm text-rose-600" role="alert">{schemaError}</div>
        </Card>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <PageHeader
        title={editing ? "Edit workflow" : "New workflow"}
        subtitle="Built from what this runtime can actually execute."
        actions={
          <>
            {/* A segmented control showing BOTH views with the active one
                lit, rather than one button labelled with the view you would
                switch TO.

                The single button was genuinely ambiguous: on the canvas it
                read "List", which is equally believable as "you are on the
                list" — so clicking Canvas appeared to do nothing. A toggle
                whose label changes to the opposite of its state is a coin
                flip for the reader, and half of them lose.

                Both views edit one draft, so switching is free and lossless:
                the list is better for filling in fields and is the one that
                works with a keyboard and a screen reader; the canvas is
                better for seeing shape and branching. */}
            <div
              role="group"
              aria-label="View"
              className="inline-flex rounded-lg border border-slate-200 p-0.5"
            >
              {[
                // Real label text, not a lowercase key with `capitalize` on
                // it: CSS capitalisation does not change the DOM, so the
                // accessible name — and anything reading this page, including
                // a test — still sees "canvas".
                { value: "list", label: "List", icon: "list" },
                { value: "canvas", label: "Canvas", icon: "blocks" },
              ].map((option) => (
                <button
                  key={option.value}
                  type="button"
                  aria-pressed={view === option.value}
                  onClick={() => setView(option.value)}
                  className={`inline-flex items-center gap-1.5 whitespace-nowrap rounded-md px-3 py-1.5 text-sm font-semibold transition ${
                    view === option.value
                      ? "bg-blue-600 text-white"
                      : "text-slate-600 hover:text-slate-900"
                  }`}
                >
                  <Icon name={option.icon} size={15} />
                  {option.label}
                </button>
              ))}
            </div>
            {!importing && (
              <SmallButton onClick={() => setImporting(true)}>Import</SmallButton>
            )}
            <SmallButton onClick={check}>Check</SmallButton>
            <SmallButton variant="primary" onClick={save} disabled={saving}>
              {saving ? "Saving…" : editing ? "Save" : "Publish to catalog"}
            </SmallButton>
          </>
        }
      />

      {error && (
        <Card><div className="p-4 text-sm text-rose-600" role="alert">{error}</div></Card>
      )}

      {undispatchable.length > 0 && (
        // The runtime executes these and core-service would not dispatch them,
        // so a workflow built from one saves cleanly and fails on its first
        // run. Said here, where somebody is choosing.
        <Card>
          <div className="p-4 text-sm text-amber-700">
            This deployment cannot run workflows containing{" "}
            <code className="font-mono">{undispatchable.join(", ")}</code> — the runtime
            supports them and this platform would not dispatch them. Avoid those node
            types until the services are on the same version.
          </div>
        </Card>
      )}

      {importing && (
        <Card title="Import a definition">
          <div className="space-y-2 p-4">
            <p className="text-[11px] leading-relaxed text-slate-500">
              An AutoOps definition (JSON or YAML), a Dify app export, or an n8n
              workflow export. The format is worked out from the contents, not the
              file name — an n8n export and one of ours are both{" "}
              <code className="font-mono">.json</code>.
            </p>
            <p className="text-[11px] leading-relaxed text-slate-500">
              Conversion is partial by design: this runtime has eight node types and
              n8n has hundreds, so anything without an equivalent is listed rather
              than quietly dropped. Whatever loads is read into the editor, never
              stored as-is.
            </p>
            <label className="flex w-fit cursor-pointer items-center gap-2 rounded-lg border border-slate-200 px-3 py-2 text-xs font-medium text-slate-700 transition hover:border-violet-400">
              <Icon name="upload" size={14} />
              Choose a file
              <input
                type="file"
                aria-label="Workflow file"
                accept=".json,.yml,.yaml,application/json,text/yaml"
                className="hidden"
                onChange={(e) => applyFile(e.target.files?.[0])}
              />
            </label>
            <textarea
              aria-label="Definition"
              className={`${inputCls} h-48 font-mono text-xs`}
              value={importText}
              onChange={(e) => setImportText(e.target.value)}
              placeholder={'{"ref":"RD-221-…","nodes":[…]}    or    nodes: [ … ]'}
            />
            <div className="flex gap-2">
              <SmallButton variant="primary" onClick={() => applyImport()}>Load</SmallButton>
              <SmallButton onClick={() => setImporting(false)}>Cancel</SmallButton>
            </div>
          </div>
        </Card>
      )}

      {imported && (
        <Card title={`Imported from ${imported.format}`}>
          <div className="p-4 text-sm">
            {imported.unsupported.length === 0 ? (
              <p className="text-emerald-700" role="status">
                Everything in that file has an equivalent here — nothing was lost.
              </p>
            ) : (
              <>
                {/* The most important output of a conversion. An author who is
                    not told what was dropped will publish a workflow that opens
                    cleanly, validates, saves, and does a fraction of what the
                    original did. */}
                <p className="font-medium text-amber-700" role="status">
                  {imported.unsupported.length} part(s) of that file could not be
                  converted and are NOT in this workflow:
                </p>
                <ul className="mt-2 list-disc space-y-1 pl-5 text-xs text-amber-700">
                  {imported.unsupported.map((u, i) => (
                    <li key={i}>{u}</li>
                  ))}
                </ul>
              </>
            )}
          </div>
        </Card>
      )}

      <Card title="Identity">
        <div className="grid gap-4 p-4 sm:grid-cols-2">
          <div>
            <label className={labelCls} htmlFor="wf-title">Name</label>
            <input id="wf-title" className={inputCls} value={title}
                   onChange={(e) => setTitle(e.target.value)} />
          </div>
          <div>
            <label className={labelCls} htmlFor="wf-ref">Stable ref</label>
            <input id="wf-ref" className={inputCls} value={draft.ref}
                   placeholder="RD-221-open-incident-routing-context"
                   onChange={(e) => setDraft((d) => ({ ...d, ref: e.target.value }))} />
            {/* Not cosmetic. Rollout matches an agent's allow-list to the
                customer's delivered copies by ref; a workflow published without
                one cannot be an agent's tool at all. */}
            <p className="mt-1 text-[11px] text-slate-500">
              How an agent names this as a tool. Without one it can be rolled out but
              never used by an agent.
            </p>
          </div>
          <div>
            <label className={labelCls} htmlFor="wf-category">Category</label>
            <input id="wf-category" className={inputCls} value={category}
                   onChange={(e) => setCategory(e.target.value)} />
          </div>
          <div className="sm:col-span-2">
            <label className={labelCls} htmlFor="wf-desc">Description</label>
            <input id="wf-desc" className={inputCls} value={draft.description}
                   onChange={(e) => setDraft((d) => ({ ...d, description: e.target.value }))} />
          </div>
        </div>
      </Card>

      <Card title="What the operator is asked">
        <div className="space-y-2 p-4">
          {draft.inputs.length === 0 && (
            <p className="text-sm text-slate-500">
              No inputs. The workflow runs the same way every time.
            </p>
          )}
          {draft.inputs.map((input, index) => (
            <div key={index} className="flex flex-wrap items-end gap-2">
              <input aria-label={`Input variable ${index + 1}`} className={`${inputCls} w-40`}
                     placeholder="TargetHost" value={input.variable}
                     onChange={(e) => setInput(index, "variable", e.target.value)} />
              <input aria-label={`Input label ${index + 1}`} className={`${inputCls} w-48`}
                     placeholder="Host to check" value={input.label}
                     onChange={(e) => setInput(index, "label", e.target.value)} />
              <select aria-label={`Input type ${index + 1}`} className={`${inputCls} w-32`}
                      value={input.type}
                      onChange={(e) => setInput(index, "type", e.target.value)}>
                {schema.input_types.map((t) => <option key={t} value={t}>{t}</option>)}
              </select>
              {input.type === "select" && (
                <input aria-label={`Input options ${index + 1}`} className={`${inputCls} w-48`}
                       placeholder="a, b, c" value={input.options}
                       onChange={(e) => setInput(index, "options", e.target.value)} />
              )}
              <label className="flex items-center gap-1.5 pb-2 text-xs text-slate-600">
                <input type="checkbox" checked={input.required}
                       aria-label={`Input required ${index + 1}`}
                       onChange={(e) => setInput(index, "required", e.target.checked)} />
                required
              </label>
              <button type="button" aria-label={`Remove input ${index + 1}`}
                      onClick={() => removeInput(index)}
                      className="pb-2 text-slate-400 hover:text-rose-600">×</button>
            </div>
          ))}
          <SmallButton onClick={addInput}>+ Input</SmallButton>
        </div>
      </Card>

      <Card title="Steps" ref={stepsRef}>
        <div className="flex flex-wrap gap-1.5 border-b border-slate-100 p-4">
          {schema.node_types.map((type) => (
            <button
              key={type.type}
              type="button"
              onClick={() => addNode(type.type)}
              disabled={undispatchable.includes(type.type)}
              title={type.summary}
              className="rounded-lg border border-slate-200 px-2.5 py-1.5 text-xs font-medium text-slate-700 transition hover:border-violet-400 disabled:cursor-not-allowed disabled:opacity-40"
            >
              + {type.type}
            </button>
          ))}
        </div>

        {view === "list" && (
        <div className="space-y-3 p-4">
          {draft.nodes.length === 0 && (
            <p className="text-sm text-slate-500">
              No steps yet. Every workflow needs exactly one <code>start</code> and at
              least one <code>end</code>.
            </p>
          )}
          {draft.nodes.map((node) => {
            const type = schema.node_types.find((t) => t.type === node.type);
            return (
              <div key={node.id} className="rounded-xl border border-slate-200 p-3">
                <div className="mb-2 flex items-center justify-between gap-2">
                  <div className="flex items-center gap-2">
                    <Chip>{node.type}</Chip>
                    <code className="font-mono text-xs text-slate-500">{node.id}</code>
                  </div>
                  <button type="button" aria-label={`Remove ${node.id}`}
                          onClick={() => removeNode(node.id)}
                          className="text-slate-400 hover:text-rose-600">×</button>
                </div>
                <p className="mb-2 text-[11px] leading-relaxed text-slate-500">
                  {type?.summary}
                </p>
                <NodeFields node={node} type={type} onChange={setNodeValue} />
                {type?.contributes?.length > 0 && (
                  // Offered so an author writes references that resolve. One
                  // that does not fails at run time with a message nobody reads
                  // until an incident.
                  <p className="mt-2 font-mono text-[10px] text-slate-400">
                    {type.contributes.map((f) => `{{#${node.id}.${f}#}}`).join("  ")}
                  </p>
                )}
              </div>
            );
          })}
        </div>
        )}

        {view === "canvas" && (
          <>
            {/* Same palette above, same draft underneath. Selecting a node opens
                the identical field editor the list uses — NodeFields — so there
                is one implementation of the form and no way for the two views
                to disagree about a wire name. */}
            <WorkflowCanvas
              draft={draft}
              schema={schema}
              selected={selected}
              onSelect={setSelected}
              onMove={moveNode}
              onConnect={connect}
              onDisconnect={disconnect}
            />
            {selectedNode ? (
              <div className="border-t border-slate-100 p-4">
                <div className="mb-2 flex items-center justify-between gap-2">
                  <div className="flex items-center gap-2">
                    <Chip>{selectedNode.type}</Chip>
                    <code className="font-mono text-xs text-slate-500">
                      {selectedNode.id}
                    </code>
                  </div>
                  <button
                    type="button"
                    aria-label={`Remove ${selectedNode.id}`}
                    onClick={() => {
                      removeNode(selectedNode.id);
                      setSelected(null);
                    }}
                    className="text-slate-400 hover:text-rose-600"
                  >
                    ×
                  </button>
                </div>
                <NodeFields
                  node={selectedNode}
                  type={schema.node_types.find((t) => t.type === selectedNode.type)}
                  onChange={setNodeValue}
                />
              </div>
            ) : (
              <p className="border-t border-slate-100 p-4 text-sm text-slate-500">
                Select a step to edit it. Drag from one step to another to connect
                them — a connection carries no branch until you name one, which is
                done in the list view.
              </p>
            )}
          </>
        )}
      </Card>

      {view === "list" && (
      <Card title="Order">
        <div className="space-y-2 p-4">
          {draft.edges.length === 0 && (
            <p className="text-sm text-slate-500">
              No edges. The steps above will not be connected to anything.
            </p>
          )}
          {draft.edges.map((edge, index) => (
            <div key={index} className="flex flex-wrap items-center gap-2">
              <select aria-label={`Edge ${index + 1} from`} className={`${inputCls} w-40`}
                      value={edge.source}
                      onChange={(e) => setEdge(index, "source", e.target.value)}>
                <option value="">from…</option>
                {nodeIds.map((n) => <option key={n} value={n}>{n}</option>)}
              </select>
              <Icon name="arrow-right" />
              <select aria-label={`Edge ${index + 1} to`} className={`${inputCls} w-40`}
                      value={edge.target}
                      onChange={(e) => setEdge(index, "target", e.target.value)}>
                <option value="">to…</option>
                {nodeIds.map((n) => <option key={n} value={n}>{n}</option>)}
              </select>
              <input aria-label={`Edge ${index + 1} branch`} className={`${inputCls} w-32`}
                     placeholder="branch" value={edge.branch}
                     onChange={(e) => setEdge(index, "branch", e.target.value)} />
              <button type="button" aria-label={`Remove edge ${index + 1}`}
                      onClick={() => removeEdge(index)}
                      className="text-slate-400 hover:text-rose-600">×</button>
            </div>
          ))}
          <SmallButton onClick={addEdge}>+ Edge</SmallButton>
        </div>
      </Card>
      )}

      {verdict && (
        <Card title="The runtime's verdict">
          <div className="p-4 text-sm">
            {verdict.unavailable ? (
              // Deliberately not "your workflow is wrong". An unreachable
              // runtime would otherwise send somebody hunting a mistake they
              // did not make.
              <p className="text-amber-700" role="status">{verdict.error}</p>
            ) : verdict.valid ? (
              <p className="text-emerald-700" role="status">
                This runtime accepts the definition.
              </p>
            ) : (
              <p className="text-rose-600" role="status">{verdict.error}</p>
            )}
            {(verdict.problems || []).length > 0 && (
              // A graph can be structurally valid and full of references that
              // will not resolve. Those fail at run time, so they are shown
              // beside the verdict rather than instead of it.
              <ul className="mt-2 list-disc space-y-1 pl-5 text-xs text-amber-700">
                {verdict.problems.map((p, i) => <li key={i}>{String(p)}</li>)}
              </ul>
            )}
          </div>
        </Card>
      )}
    </div>
  );
}
