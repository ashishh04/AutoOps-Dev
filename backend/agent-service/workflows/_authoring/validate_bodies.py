#!/usr/bin/env python3
"""Check every automation body before it can reach a customer's account.

Four checks, each of which has a failure mode behind it:

1. **It parses.** A body is substituted into a step and run by `python3` on the
   execution host. A syntax error there is discovered as a failed run against
   production, minutes after someone approved it.
2. **Every ``{{Placeholder}}`` it reads is a declared input**, and every
   declared input is read by the body or marked ``consumedBy: agent``. An
   undeclared placeholder is left unsubstituted and reaches the interpreter as
   literal text; an unread input is a form field that does nothing.
3. **Every string input declares a ``pattern``.** The value is substituted into
   a running script verbatim — the pattern is the injection control, and
   `NativeInputValidator` is the only thing enforcing it.
4. **No ``{{`` that is not a placeholder.** The engine's regex is
   ``{{Identifier}}``, so an f-string escaping a literal brace would be
   silently eaten.

Run directly, or through ``pytest backend/agent-runtime/tests/test_workflow_bodies.py``.
"""
from __future__ import annotations

import ast
import json
import pathlib
import re
import sys

HERE = pathlib.Path(__file__).resolve().parent
WORKFLOWS = HERE.parent

PLACEHOLDER = re.compile(r"\{\{\s*([A-Za-z][A-Za-z0-9_]*)\s*\}\}")
#: The NATIVE graph form, `{{#start.Field#}}`. A graph workflow runs inside
#: agent-runtime and reads its inputs through references rather than through
#: ExecutionEngine's textual substitution, so a node reading one is reading the
#: form just as surely as a `{{Var}}` in a shell command is.
GRAPH_REF = re.compile(r"\{\{#\s*start\.([A-Za-z][A-Za-z0-9_]*)\s*#\}\}")
#: Any other doubled brace. The engine would not substitute it and Python
#: would read it literally.
STRAY_BRACES = re.compile(r"\{\{(?!\s*[A-Za-z][A-Za-z0-9_]*\s*\}\})")


def problems_in(path: pathlib.Path) -> list[str]:
    found: list[str] = []
    document = json.loads(path.read_text(encoding="utf-8"))
    name = path.name

    declared = {field["variable"]: field for field in document.get("inputs", [])}

    for index, node in enumerate(document.get("nodes", [])):
        value = node.get("value", "")
        label = f"{name} node[{index}]"

        used = set(PLACEHOLDER.findall(value))
        used |= set(GRAPH_REF.findall(value))
        for variable in sorted(used - set(declared)):
            found.append(f"{label}: reads {{{{{variable}}}}} which is not a declared input")

        # A graph reference is `{{#start.X#}}`, which is deliberately NOT the
        # engine's step placeholder and must not be reported as a stray brace.
        stray = STRAY_BRACES.search(GRAPH_REF.sub("x", value))
        if stray:
            found.append(
                f"{label}: contains '{{{{' that is not a placeholder at offset {stray.start()} — "
                f"the engine will not substitute it and Python will read it literally"
            )

        if node.get("type") == "pyscript":
            # Placeholders are not valid Python until substituted, so they are
            # replaced with a benign literal of the right shape before parsing.
            probe = PLACEHOLDER.sub(lambda m: _stand_in(declared.get(m.group(1))), value)
            try:
                ast.parse(probe)
            except SyntaxError as exc:
                found.append(f"{label}: is not valid Python — line {exc.lineno}: {exc.msg}")

    consumed_by_node = {
        variable for variable, field in declared.items()
        if field.get("consumedBy", "node") == "node"
    }
    # The whole node, not just `value`: a graph node scatters references across
    # windowHours, url, args and prompt, and only a step node keeps its command
    # in one field.
    serialised = json.dumps(document.get("nodes", []))
    read_anywhere = set(PLACEHOLDER.findall(serialised)) | set(GRAPH_REF.findall(serialised))
    for variable in sorted(consumed_by_node - read_anywhere):
        found.append(
            f"{name}: input '{variable}' is declared consumedBy=node but no node reads "
            f"{{{{{variable}}}}} or {{{{#start.{variable}#}}}} — it is a form field "
            f"that does nothing"
        )

    for variable, field in sorted(declared.items()):
        if field.get("type") == "string" and not field.get("pattern"):
            found.append(
                f"{name}: string input '{variable}' declares no pattern. Its value is "
                f"substituted into a running script verbatim; the pattern is the only "
                f"thing standing between a tool argument and code execution."
            )

    return found


def _stand_in(field: dict | None) -> str:
    """A syntactically valid value of the declared type, for the parse check."""
    kind = (field or {}).get("type", "string")
    if kind == "number":
        return "1"
    if kind == "boolean":
        return "false"
    return "x"


def main() -> int:
    paths = sorted(p for p in WORKFLOWS.glob("*/*.json"))
    if not paths:
        print(f"No workflows under {WORKFLOWS}. Nothing to check — that is a gap, not a pass.")
        return 1

    failures: list[str] = []
    for path in paths:
        found = problems_in(path)
        failures.extend(found)
        print(f"{'FAIL' if found else 'ok  '}  {path.relative_to(WORKFLOWS)}")

    if failures:
        print(f"\n{len(failures)} problem(s):")
        for problem in failures:
            print(f"  - {problem}")
        return 1

    print(f"\n{len(paths)} workflow(s) checked, no problems.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
