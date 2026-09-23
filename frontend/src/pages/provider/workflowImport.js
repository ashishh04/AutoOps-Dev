import { load } from "js-yaml";

/**
 * Reading a workflow somebody else wrote.
 *
 * <h2>The rule this module is built around</h2>
 * **Never return a graph that looks complete when it is not.** Every format
 * below describes more than this runtime can execute — n8n alone has hundreds
 * of integration nodes, and there is no honest mapping for a Slack node onto a
 * platform that has no Slack node. A converter that quietly dropped them would
 * hand back a workflow that opens cleanly, validates, saves, and does a
 * fraction of what the original did, with nothing anywhere saying so.
 *
 * So conversion is partial by design and says exactly what it could not carry.
 * `unsupported` is part of the result, not a log line.
 *
 * <h2>What is understood</h2>
 * - **AutoOps** definitions, as JSON or YAML. The same shape either way; YAML
 *   is accepted because a node graph with a multi-line prompt in it is far
 *   easier to read and hand-edit as YAML than as JSON with `\n` escapes.
 * - **Dify** app exports. The engine this platform replaced; two of its designs
 *   are still in `.dify-archive/` and have no other way back in.
 * - **n8n** workflow exports. Structurally close — nodes and connections — and
 *   semantically distant, which is why it produces the longest `unsupported`
 *   list of the three.
 */

/** Node ids must satisfy Python's `str.isidentifier()`; the runtime refuses others. */
export function identifier(name, taken = new Set()) {
  let base = String(name || "")
    .normalize("NFKD")
    .replace(/[^a-zA-Z0-9_]+/g, "_")
    .replace(/^_+|_+$/g, "")
    .toLowerCase();
  if (!base || /^[0-9]/.test(base)) base = `n_${base || "node"}`;

  let candidate = base;
  let n = 1;
  while (taken.has(candidate)) {
    n += 1;
    candidate = `${base}_${n}`;
  }
  taken.add(candidate);
  return candidate;
}

/**
 * Which of the three this text is.
 *
 * Decided on structure, never on the file extension: an n8n export and an
 * AutoOps definition are both `.json`, and a Dify export and an AutoOps YAML
 * are both `.yaml`. The extension is what the user happened to name it.
 */
export function detect(document) {
  if (!document || typeof document !== "object") return "unknown";
  // n8n first: it also has a `nodes` array, so an AutoOps check that only
  // looked for `nodes` would claim it and then produce nonsense.
  if (Array.isArray(document.nodes) && document.connections) return "n8n";
  if (document.workflow?.graph || (document.app && document.kind === "app")) return "dify";
  if (Array.isArray(document.nodes)) return "autoops";
  return "unknown";
}

/** Text to object, whichever of the two syntaxes it is written in. */
export function readDocument(text) {
  const trimmed = String(text || "").trim();
  if (!trimmed) throw new Error("There is nothing to import.");
  if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
    try {
      return JSON.parse(trimmed);
    } catch (err) {
      throw new Error(`That is not valid JSON: ${err.message}`);
    }
  }
  try {
    const parsed = load(trimmed);
    if (!parsed || typeof parsed !== "object") {
      throw new Error("it did not describe a workflow");
    }
    return parsed;
  } catch (err) {
    throw new Error(`That is not valid YAML: ${err.message}`);
  }
}

// ------------------------------------------------------------------ Dify ---

/** Dify node type -> ours. Anything absent has no equivalent here. */
const DIFY_NODES = {
  start: "start",
  end: "end",
  llm: "llm",
  answer: "end",
  "template-transform": "template",
  "if-else": "condition",
  "http-request": "http",
};

function fromDify(document) {
  const graph = document.workflow?.graph || {};
  const unsupported = [];
  const taken = new Set();
  const idFor = new Map();

  const nodes = [];
  let inputs = [];

  for (const raw of graph.nodes || []) {
    const kind = raw.data?.type;
    const mapped = DIFY_NODES[kind];
    const label = raw.data?.title || raw.id;
    if (!mapped) {
      unsupported.push(`${label} (${kind}) — this runtime has no equivalent node`);
      continue;
    }
    const id = identifier(raw.id || label, taken);
    idFor.set(raw.id, id);

    const node = { id, type: mapped };
    if (raw.data?.title) node.title = raw.data.title;
    if (Number.isFinite(raw.position?.x)) node.x = Math.round(raw.position.x);
    if (Number.isFinite(raw.position?.y)) node.y = Math.round(raw.position.y);

    if (mapped === "llm") {
      const messages = raw.data?.prompt_template || [];
      node.prompt = messages.map((m) => ({
        role: m.role === "system" || m.role === "assistant" ? m.role : "user",
        text: String(m.text ?? ""),
      }));
      if (raw.data?.model?.name) node.model = raw.data.model.name;
    }

    if (mapped === "start") {
      inputs = (raw.data?.variables || []).map((v) => ({
        variable: v.variable,
        label: v.label || v.variable,
        type: ["text", "paragraph", "select", "number", "boolean"].includes(v.type)
          ? v.type
          : "text",
        required: Boolean(v.required),
        ...(v.options?.length ? { options: v.options } : {}),
        ...(v.default ? { default: v.default } : {}),
        ...(v.max_length ? { maxLength: v.max_length } : {}),
        ...(v.hint ? { hint: v.hint } : {}),
      }));
    }
    nodes.push(node);
  }

  const edges = [];
  for (const edge of graph.edges || []) {
    const source = idFor.get(edge.source);
    const target = idFor.get(edge.target);
    // An edge whose endpoint was dropped is dropped WITH a note. Kept, it would
    // make the runtime refuse the whole graph with "edge references unknown
    // node" — an error about a symptom rather than the node that was lost.
    if (source && target) edges.push({ source, target });
    else unsupported.push(`a connection ${edge.source} → ${edge.target}`);
  }

  return {
    format: "dify",
    definition: {
      name: document.app?.name,
      description: document.app?.description,
      inputs,
      nodes,
      edges,
    },
    unsupported,
  };
}

// ------------------------------------------------------------------- n8n ---

/**
 * n8n node type -> ours, for the handful that genuinely correspond.
 *
 * Deliberately short. n8n's value is several hundred integration nodes, and
 * pretending any of them maps onto a platform with eight node types would
 * produce a workflow that runs and does something other than what it says.
 */
const N8N_NODES = {
  "n8n-nodes-base.httpRequest": "http",
  "n8n-nodes-base.webhook": "start",
  "n8n-nodes-base.start": "start",
  "n8n-nodes-base.manualTrigger": "start",
  "n8n-nodes-base.scheduleTrigger": "start",
  "n8n-nodes-base.cron": "start",
  "n8n-nodes-base.if": "condition",
  "n8n-nodes-base.noOp": "end",
  "n8n-nodes-base.set": "template",
};

function fromN8n(document) {
  const unsupported = [];
  const taken = new Set();
  const idFor = new Map();
  const nodes = [];

  for (const raw of document.nodes || []) {
    const mapped = N8N_NODES[raw.type];
    if (!mapped) {
      unsupported.push(`${raw.name} (${raw.type}) — no equivalent node here`);
      continue;
    }
    const id = identifier(raw.name, taken);
    // Keyed by NAME, because that is what n8n's `connections` refer to. Ids in
    // an n8n export are uuids that appear nowhere else.
    idFor.set(raw.name, id);

    const node = { id, type: mapped, title: raw.name };
    if (Array.isArray(raw.position) && raw.position.length === 2) {
      node.x = Math.round(raw.position[0]);
      node.y = Math.round(raw.position[1]);
    }
    if (mapped === "http") {
      if (raw.parameters?.url) node.url = raw.parameters.url;
      if (raw.parameters?.method) node.method = String(raw.parameters.method).toUpperCase();
    }
    nodes.push(node);
  }

  const edges = [];
  for (const [fromName, outputs] of Object.entries(document.connections || {})) {
    const source = idFor.get(fromName);
    // n8n's shape is connections[name].main[outputIndex][] — the outer index is
    // the branch. For an `if` node, 0 is true and 1 is false, which is the one
    // piece of n8n branch semantics worth carrying.
    (outputs?.main || []).forEach((branchTargets, branchIndex) => {
      (branchTargets || []).forEach((link) => {
        const target = idFor.get(link?.node);
        if (!source || !target) {
          unsupported.push(`a connection ${fromName} → ${link?.node}`);
          return;
        }
        const edge = { source, target };
        const sourceNode = nodes.find((n) => n.id === source);
        if (sourceNode?.type === "condition") {
          edge.branch = branchIndex === 0 ? "true" : "false";
        }
        edges.push(edge);
      });
    });
  }

  return {
    format: "n8n",
    definition: { name: document.name, inputs: [], nodes, edges },
    unsupported,
  };
}

// ----------------------------------------------------------------- entry ---

/**
 * Import whatever this is, and say what was lost.
 *
 * @returns {{definition: object, format: string, unsupported: string[]}}
 * @throws when the text cannot be read at all, or describes nothing runnable
 */
export function importWorkflow(text) {
  const document = readDocument(text);
  const format = detect(document);

  if (format === "dify") return fromDify(document);
  if (format === "n8n") return fromN8n(document);
  if (format === "autoops") {
    return { format: "autoops", definition: document, unsupported: [] };
  }
  throw new Error(
    "This does not look like a workflow. Expected an AutoOps definition, a Dify " +
      "app export, or an n8n workflow export.",
  );
}
