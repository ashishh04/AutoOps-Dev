"""Resolving ``{{#node.field#}}`` against a run's scope.

Every node writes its result into a scope keyed by node id, and every node's
configuration may read any *already-executed* node's output. That is the whole
data model — there are no globals, no mutable workflow variables and no
assignment, because a canvas where any node can rewrite any value is a canvas
whose behaviour cannot be read off the picture.

**An unresolved reference is an error, not an empty string.** Dify substituted
a missing variable with nothing, which is how a prompt silently becomes
"Severity: \\nAffected service: \\n" and the model writes a confident report
about an incident it was told nothing about. A run that stops with "node
'writer' references {{#start.serverity#}}, which no node produced" costs a
retype; a run that continues costs a wrong postmortem nobody knows is wrong.
"""

from __future__ import annotations

import re
from typing import Any

#: ``{{#node.field#}}`` — the hash delimiters are what keep this from colliding
#: with core-service's ``{{Name}}`` job-step substitution, which runs over step
#: text before execution and would otherwise consume these.
PATTERN = re.compile(r"\{\{#\s*([A-Za-z_][A-Za-z0-9_]*)\.([A-Za-z_][A-Za-z0-9_]*)\s*#\}\}")


class UnresolvedReference(ValueError):
    """A reference naming a node or field the scope does not hold."""


def references(text: str | None) -> set[tuple[str, str]]:
    """Every (node, field) pair a piece of configuration reads."""
    if not text:
        return set()
    return {(m.group(1), m.group(2)) for m in PATTERN.finditer(text)}


def resolve(text: str | None, scope: dict[str, dict[str, Any]], *, where: str) -> str | None:
    """Substitute every reference in ``text``.

    :param where: the node being configured, so a failure names the node an
                  author has to go and fix rather than just the missing name.
    """
    if text is None:
        return None

    def replace(match: re.Match[str]) -> str:
        node, field = match.group(1), match.group(2)
        if node not in scope:
            raise UnresolvedReference(
                f"node {where!r} references {{{{#{node}.{field}#}}}}, but no node "
                f"{node!r} has produced output yet. Nodes can only read from "
                f"nodes that run before them."
            )
        if field not in scope[node]:
            available = ", ".join(sorted(scope[node])) or "nothing"
            raise UnresolvedReference(
                f"node {where!r} references {{{{#{node}.{field}#}}}}, but {node!r} "
                f"produced: {available}."
            )
        value = scope[node][field]
        # Booleans render as PowerShell/JSON-ish lowercase rather than Python's
        # True/False: these strings end up in prompts and HTTP bodies read by
        # things that are not Python.
        if isinstance(value, bool):
            return "true" if value else "false"
        return "" if value is None else str(value)

    return PATTERN.sub(replace, text)


def validate_against(spec: Any) -> list[str]:
    """Every reference that cannot possibly resolve, found without running.

    Walks the graph in declaration order and checks each node's references
    against the nodes that could have run before it. Returns messages rather
    than raising so a console can show an author all of their mistakes at once
    instead of one per save.
    """
    problems: list[str] = []
    produced: set[str] = set()
    for node in spec.nodes:
        texts: list[str | None] = [node.template, node.url, node.body, node.when]
        texts.extend(message.text for message in node.prompt)
        texts.extend(node.headers.values())
        for node_id, field in sorted({r for t in texts for r in references(t)}):
            if node_id not in produced:
                problems.append(
                    f"node {node.id!r} reads {{{{#{node_id}.{field}#}}}} before "
                    f"{node_id!r} has run"
                )
        produced.add(node.id)
    return problems
