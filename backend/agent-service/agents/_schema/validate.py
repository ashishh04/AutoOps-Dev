#!/usr/bin/env python3
"""Validate authored agents and workflows.

    python backend/agent-service/agents/_schema/validate.py

Agents live in agents/<DOMAIN>/, workflows in workflows/<DOMAIN>/. Each is
checked against its JSON Schema, then against the cross-field rules a schema
cannot express. Exits non-zero on any fault so this can gate a commit.

Inputs are declared ONCE, on the workflow. Agents do not restate them: two
copies drift, and an operator form that disagrees with the model's tool schema
is worse than either alone.
"""
import json
import pathlib
import re
import sys

try:
    from jsonschema import Draft202012Validator
except ImportError:
    sys.exit("jsonschema not installed — run: python -m pip install jsonschema")

SCHEMA_DIR = pathlib.Path(__file__).resolve().parent
AGENT_ROOT = SCHEMA_DIR.parent                      # .../agents
WORKFLOW_ROOT = AGENT_ROOT.parent / "workflows"     # .../workflows
BASE = AGENT_ROOT.parent

AGENT_SCHEMA = Draft202012Validator(
    json.loads((SCHEMA_DIR / "agent.schema.json").read_text(encoding="utf-8")))
WORKFLOW_SCHEMA = Draft202012Validator(
    json.loads((SCHEMA_DIR / "workflow.schema.json").read_text(encoding="utf-8")))

PLACEHOLDER = re.compile(r"\{\{\s*([A-Za-z][A-Za-z0-9_]*)\s*\}\}")

#: The NATIVE graph form, `{{#start.Field#}}`. A different engine reads a
#: different syntax, and the two deliberately cannot collide — core-service
#: substitutes `{{Name}}` into a step before execution, so a graph body passing
#: through that substitution would have its own references eaten.
GRAPH_REF = re.compile(r"\{\{#\s*start\.([A-Za-z][A-Za-z0-9_]*)\s*#\}\}")


def referenced(node):
    """Every input a node reads, whichever engine it belongs to.

    A step node keeps its command in `value`; a graph node scatters references
    across `windowHours`, `url`, `args`, `prompt` and more — so the whole node
    is searched rather than one field.
    """
    text = json.dumps(node)
    return set(PLACEHOLDER.findall(text)) | set(GRAPH_REF.findall(text))


def name_matches_task(doc, path, errors):
    task_id = doc.get("taskId", "")
    if not path.name.startswith(task_id + "-"):
        errors.append(f"file name does not start with its taskId '{task_id}'")


def agent_rules(doc, path):
    errors = []
    name_matches_task(doc, path, errors)
    for tool in doc.get("tools", []):
        if not tool["ref"].startswith(doc.get("taskId", "") + "-"):
            errors.append(f"tool ref '{tool['ref']}' does not belong to {doc.get('taskId')}")
    return errors


def workflow_rules(doc, path):
    errors = []
    name_matches_task(doc, path, errors)

    fields = doc.get("inputs", [])
    names = [f["variable"] for f in fields]
    for dupe in sorted({n for n in names if names.count(n) > 1}):
        errors.append(f"input variable '{dupe}' declared more than once")
    by_name = {f["variable"]: f for f in fields}

    for field in fields:
        when = field.get("requiredWhen")
        if when and when["field"] not in by_name:
            errors.append(f"'{field['variable']}'.requiredWhen references unknown field "
                          f"'{when['field']}'")
        if "pattern" in field:
            try:
                re.compile(field["pattern"])
            except re.error as exc:
                errors.append(f"'{field['variable']}'.pattern is not a valid regex: {exc}")

        default = field.get("default")
        if default is None:
            continue
        if field.get("options") and default not in field["options"]:
            errors.append(f"'{field['variable']}' default {default!r} is not one of its options")
        if field.get("pattern") and isinstance(default, str) \
                and not re.fullmatch(field["pattern"], default):
            errors.append(f"'{field['variable']}' default {default!r} fails its own pattern")
        if isinstance(default, (int, float)) and not isinstance(default, bool):
            if field.get("min") is not None and default < field["min"]:
                errors.append(f"'{field['variable']}' default {default} is below min")
            if field.get("max") is not None and default > field["max"]:
                errors.append(f"'{field['variable']}' default {default} is above max")

    warn, crit = by_name.get("DiskWarnPercent"), by_name.get("DiskCriticalPercent")
    if warn and crit and warn.get("default") is not None and crit.get("default") is not None \
            and warn["default"] >= crit["default"]:
        errors.append(f"DiskWarnPercent default ({warn['default']}) must be below "
                      f"DiskCriticalPercent default ({crit['default']})")

    # Every {{Placeholder}} must resolve to a declared input. An unresolved one
    # would reach the runner verbatim — a hostname of "{{TargetHost}}" handed to
    # ssh is the kind of fault that must fail here, not in production.
    for node in doc.get("nodes", []):
        for used in referenced(node):
            if used not in by_name:
                errors.append(f"node {node.get('label')!r} uses {{{{{used}}}}}, "
                              f"which is not a declared input")

    # A node-consumed input that no node reads is dead weight on the operator's
    # form — usually a renamed placeholder. Agent-consumed inputs are exempt:
    # they reach the model as tool arguments and inform its judgement (a
    # threshold to compare against) rather than being substituted into a step.
    used_everywhere = {u for n in doc.get("nodes", [])
                       for u in referenced(n)}
    for name, field in sorted(by_name.items()):
        if field.get("consumedBy", "node") == "node" and name not in used_everywhere:
            errors.append(f"input '{name}' is declared consumedBy=node but no node uses it "
                          f"— set consumedBy='agent' if the model is meant to read it")

    return errors


def check(paths, validator, rules, label):
    failed = 0
    for path in paths:
        rel = path.relative_to(BASE)
        try:
            doc = json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as exc:
            print(f"FAIL  {rel}\n        not valid JSON: {exc}")
            failed += 1
            continue

        problems = [f"{'/'.join(str(p) for p in e.path) or '(root)'}: {e.message}"
                    for e in sorted(validator.iter_errors(doc), key=lambda e: list(e.path))]
        problems += rules(doc, path)

        if problems:
            failed += 1
            print(f"FAIL  {rel}")
            for problem in problems:
                print(f"        {problem}")
        else:
            blocked = "  [BLOCKED]" if doc.get("blockedBy") else ""
            detail = (f"{len(doc['inputs'])} inputs, {len(doc['nodes'])} nodes"
                      if label == "workflow" else f"{len(doc['tools'])} tool(s)")
            print(f"ok    {rel}  ({detail}){blocked}")
    return failed


def main():
    workflows = sorted(WORKFLOW_ROOT.glob("*/*.json")) if WORKFLOW_ROOT.exists() else []
    agents = sorted(p for p in AGENT_ROOT.glob("*/*.json") if p.parent.name != "_schema")
    if not workflows and not agents:
        sys.exit("nothing to validate")

    failed = check(workflows, WORKFLOW_SCHEMA, workflow_rules, "workflow")
    failed += check(agents, AGENT_SCHEMA, agent_rules, "agent")

    total = len(workflows) + len(agents)
    print(f"\n{total - failed}/{total} valid")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
