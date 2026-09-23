import fs from "node:fs";
import path from "node:path";
import { describe, it, expect } from "vitest";
import { importWorkflow, detect, identifier, readDocument } from "./workflowImport";

/**
 * The Dify case is tested against the REAL export in `.dify-archive/`, not a
 * fixture written from memory of the format.
 *
 * A converter is only ever wrong about the shape of somebody else's file, and a
 * fixture I write myself encodes the same misunderstanding the converter has —
 * so it would agree with the bug and pass. These two files are the actual
 * designs the deleted engine held, and importing them is the actual reason this
 * code exists.
 */
const ARCHIVE = path.resolve(__dirname, "../../../../.dify-archive");
const difyExport = () =>
  fs.readFileSync(path.join(ARCHIVE, "Incident_Postmortem_Writer.yml"), "utf8");

describe("identifier", () => {
  it("turns a name with spaces into something the runtime accepts", () => {
    // Node ids must satisfy Python's str.isidentifier(); n8n names are free
    // text, and "HTTP Request" would be refused on save.
    expect(identifier("HTTP Request")).toBe("http_request");
  });

  it("does not start an id with a digit", () => {
    expect(identifier("1st step")).toMatch(/^[a-z_]/);
  });

  it("keeps two nodes with the same name apart", () => {
    // n8n allows duplicate-looking names; colliding ids would silently merge
    // two steps into one and drop half the graph's edges onto the survivor.
    const taken = new Set();
    expect(identifier("Send", taken)).toBe("send");
    expect(identifier("Send", taken)).toBe("send_2");
  });

  it("never returns an empty id", () => {
    expect(identifier("···")).toBeTruthy();
    expect(identifier("")).toBeTruthy();
  });
});

describe("detect", () => {
  it("does not mistake an n8n export for an AutoOps definition", () => {
    // Both carry a `nodes` array. A check that looked only for that would claim
    // the n8n file and then produce nonsense from it.
    expect(detect({ nodes: [], connections: {} })).toBe("n8n");
    expect(detect({ nodes: [{ id: "start", type: "start" }] })).toBe("autoops");
  });

  it("recognises a Dify app export", () => {
    expect(detect({ app: { name: "x" }, kind: "app" })).toBe("dify");
  });

  it("refuses to guess at something that is not a workflow", () => {
    expect(detect({ hello: "world" })).toBe("unknown");
    expect(detect(null)).toBe("unknown");
  });
});

describe("readDocument", () => {
  it("reads the same definition from JSON or YAML", () => {
    // YAML matters for a node graph specifically: a prompt is multi-line, and
    // as JSON it becomes one line of \n escapes that cannot be read in a diff.
    const json = readDocument('{"nodes":[{"id":"start","type":"start"}]}');
    const yaml = readDocument("nodes:\n  - id: start\n    type: start\n");
    expect(yaml).toEqual(json);
  });

  it("names the syntax that failed", () => {
    expect(() => readDocument("{ nope")).toThrow(/not valid JSON/);
    expect(() => readDocument("")).toThrow(/nothing to import/);
  });
});

describe("importing a real Dify export", () => {
  it("carries the graph across", () => {
    const { format, definition } = importWorkflow(difyExport());

    expect(format).toBe("dify");
    expect(definition.name).toBe("Incident Postmortem Writer");
    expect(definition.nodes.map((n) => n.type)).toEqual(["start", "llm", "end"]);
    expect(definition.edges).toEqual([
      { source: "start", target: "writer" },
      { source: "writer", target: "end" },
    ]);
  });

  it("turns the start node's variables into the operator's input form", () => {
    // These are what an operator is asked before a run. Lost, the workflow
    // still validates and runs — against empty inputs.
    const { definition } = importWorkflow(difyExport());
    const notes = definition.inputs.find((i) => i.variable === "incident_notes");

    expect(notes).toMatchObject({ type: "paragraph", required: true });
    expect(notes.hint).toMatch(/Slack thread/);
    const severity = definition.inputs.find((i) => i.variable === "severity");
    expect(severity.options).toContain("P1");
  });

  it("keeps the prompt as messages rather than flattening it", () => {
    const { definition } = importWorkflow(difyExport());
    const llm = definition.nodes.find((n) => n.type === "llm");

    expect(Array.isArray(llm.prompt)).toBe(true);
    expect(llm.prompt.length).toBeGreaterThan(0);
    for (const message of llm.prompt) {
      expect(["system", "user", "assistant"]).toContain(message.role);
      expect(typeof message.text).toBe("string");
    }
  });

  it("converts cleanly — nothing in these two designs is unsupported", () => {
    expect(importWorkflow(difyExport()).unsupported).toEqual([]);
  });
});

describe("importing an n8n export", () => {
  const n8n = {
    name: "Ops alerting",
    nodes: [
      { name: "Schedule", type: "n8n-nodes-base.scheduleTrigger", position: [0, 0] },
      {
        name: "HTTP Request",
        type: "n8n-nodes-base.httpRequest",
        position: [220, 0],
        parameters: { url: "https://example.invalid/status", method: "get" },
      },
      { name: "Post to Slack", type: "n8n-nodes-base.slack", position: [440, 0] },
    ],
    connections: {
      Schedule: { main: [[{ node: "HTTP Request", type: "main", index: 0 }]] },
      "HTTP Request": { main: [[{ node: "Post to Slack", type: "main", index: 0 }]] },
    },
  };

  it("converts the nodes it genuinely understands", () => {
    const { definition } = importWorkflow(JSON.stringify(n8n));

    expect(definition.nodes.map((n) => n.type)).toEqual(["start", "http"]);
    const http = definition.nodes.find((n) => n.type === "http");
    expect(http).toMatchObject({ url: "https://example.invalid/status", method: "GET" });
  });

  it("SAYS what it could not convert rather than dropping it quietly", () => {
    // The whole point. n8n has hundreds of integration nodes and this platform
    // has eight node types; a silent conversion hands back a workflow that
    // opens, validates, saves, and does a fraction of the original.
    const { unsupported } = importWorkflow(JSON.stringify(n8n));

    expect(unsupported.some((u) => u.includes("Post to Slack"))).toBe(true);
    expect(unsupported.some((u) => u.includes("n8n-nodes-base.slack"))).toBe(true);
  });

  it("reports a connection whose far end was not converted", () => {
    // Kept, it would make the runtime refuse the entire graph with "edge
    // references unknown node" — an error about the symptom, not the loss.
    const { definition, unsupported } = importWorkflow(JSON.stringify(n8n));

    expect(definition.edges).toEqual([{ source: "schedule", target: "http_request" }]);
    expect(unsupported.some((u) => u.includes("Post to Slack"))).toBe(true);
  });

  it("carries n8n's node positions, so an import is laid out as it was drawn", () => {
    const { definition } = importWorkflow(JSON.stringify(n8n));

    expect(definition.nodes[1]).toMatchObject({ x: 220, y: 0 });
  });

  it("names which branch of an if-node an edge came from", () => {
    // n8n puts true on output 0 and false on output 1. Losing that leaves two
    // identical lines where only one of them runs.
    const branching = {
      name: "Branching",
      nodes: [
        { name: "Check", type: "n8n-nodes-base.if", position: [0, 0] },
        { name: "Yes", type: "n8n-nodes-base.noOp", position: [200, 0] },
        { name: "No", type: "n8n-nodes-base.noOp", position: [200, 120] },
      ],
      connections: {
        Check: {
          main: [
            [{ node: "Yes", type: "main", index: 0 }],
            [{ node: "No", type: "main", index: 0 }],
          ],
        },
      },
    };

    const { definition } = importWorkflow(JSON.stringify(branching));
    expect(definition.edges).toEqual([
      { source: "check", target: "yes", branch: "true" },
      { source: "check", target: "no", branch: "false" },
    ]);
  });
});

describe("an AutoOps definition", () => {
  it("passes through untouched", () => {
    const definition = { ref: "RD-1", nodes: [{ id: "start", type: "start" }], edges: [] };
    const result = importWorkflow(JSON.stringify(definition));

    expect(result.format).toBe("autoops");
    expect(result.definition).toEqual(definition);
    expect(result.unsupported).toEqual([]);
  });

  it("is accepted as YAML too", () => {
    const result = importWorkflow(
      "ref: RD-2\nnodes:\n  - id: start\n    type: start\nedges: []\n",
    );

    expect(result.format).toBe("autoops");
    expect(result.definition.ref).toBe("RD-2");
  });
});

describe("something else entirely", () => {
  it("refuses rather than producing an empty workflow", () => {
    // An empty graph that saves is worse than an error: it is rollable.
    expect(() => importWorkflow('{"hello":"world"}')).toThrow(/does not look like a workflow/);
  });
});
