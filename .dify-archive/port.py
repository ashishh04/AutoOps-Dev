"""Port a Dify workflow DSL export into a native workflow definition.

Only handles what the exports actually contain: start -> llm -> end. Anything
else is reported rather than guessed at, because a workflow that silently loses
a node is worse than one that refuses to convert.
"""
import io
import json
import re
import sys

import yaml

# Dify writes references as {{#nodeId.field#}} already — the same shape the
# native runtime uses, which is why it was chosen. Only the START node's id
# differs: Dify names it by node id, and so do we.
REF = re.compile(r"\{\{#([A-Za-z0-9_]+)\.([A-Za-z0-9_]+)#\}\}")

TYPE_MAP = {
    "text-input": "text",
    "paragraph": "paragraph",
    "select": "select",
    "number": "number",
}


def port(path):
    doc = yaml.safe_load(io.open(path, encoding="utf-8"))
    graph = doc["workflow"]["graph"]
    out_nodes, inputs, unsupported = [], [], []

    for node in graph["nodes"]:
        data = node["data"]
        kind, nid = data["type"], node["id"]

        if kind == "start":
            out_nodes.append({"id": nid, "type": "start", "title": data.get("title", "Start")})
            for var in data.get("variables", []):
                field = {
                    "variable": var["variable"],
                    "label": var.get("label", var["variable"]),
                    "type": TYPE_MAP.get(var.get("type", "text-input"), "text"),
                    "required": bool(var.get("required")),
                }
                if var.get("default"):
                    field["default"] = var["default"]
                if var.get("options"):
                    field["options"] = var["options"]
                if var.get("max_length"):
                    field["maxLength"] = var["max_length"]
                if var.get("hint"):
                    field["hint"] = var["hint"]
                inputs.append(field)

        elif kind == "llm":
            params = data.get("model", {}).get("completion_params", {}) or {}
            llm = {
                "id": nid,
                "type": "llm",
                "title": data.get("title", "Model"),
                "prompt": [
                    {"role": m.get("role", "user"), "text": m.get("text", "")}
                    for m in data.get("prompt_template", [])
                ],
            }
            if params.get("temperature") is not None:
                llm["temperature"] = params["temperature"]
            # The vendor's model NAME is deliberately dropped. It pointed at a
            # deployment in Dify's own provider config; here the model must be
            # one THIS tenant's connections actually offer, and inventing a
            # mapping would produce a workflow that resolves to the wrong
            # vendor. Left unset, the deployment default applies.
            out_nodes.append(llm)

        elif kind == "end":
            outputs = []
            for out in data.get("outputs", []):
                sel = out.get("value_selector") or []
                if len(sel) >= 2:
                    outputs.append({"variable": out["variable"],
                                    "from": "{{#%s.%s#}}" % (sel[0], sel[1])})
            out_nodes.append({"id": nid, "type": "end",
                              "title": data.get("title", "End"), "outputs": outputs})
        else:
            unsupported.append(f"{nid}:{kind}")

    edges = [{"source": e["source"], "target": e["target"]} for e in graph.get("edges", [])]
    spec = {
        "name": doc.get("app", {}).get("name") or doc.get("name"),
        "description": doc.get("app", {}).get("description") or "",
        "inputs": inputs,
        "nodes": out_nodes,
        "edges": edges,
    }
    return spec, unsupported


for src in sys.argv[1:]:
    spec, unsupported = port(src)
    if unsupported:
        print(f"REFUSED {src}: unsupported node(s) {unsupported}")
        continue
    dest = src.rsplit(".", 1)[0] + ".native.json"
    io.open(dest, "w", encoding="utf-8", newline="\n").write(json.dumps(spec, indent=2) + "\n")
    print(f"ported {src} -> {dest}  ({len(spec['nodes'])} nodes, {len(spec['inputs'])} inputs)")
