package com.intertec.autoops.core.execution;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Whether a workflow definition can actually be walked.
 *
 * <h2>The failure this exists to catch</h2>
 * A workflow is a graph: {@code nodes[]} says what the steps are and
 * {@code edges[]} says what follows what. The runtime starts at the {@code
 * start} node and follows edges. A definition carrying nodes but no edges is
 * therefore not a slow workflow or a wrong one — it is a workflow where nothing
 * after {@code start} is reachable.
 *
 * <p>And it did not look like a failure. The publisher dropped {@code edges}
 * when writing catalog rows, so three shipped workflows arrived with three
 * nodes and no connections. Running one took 70ms, executed {@code start}
 * alone, produced no output at all, and reported <b>Success</b> — above a
 * validation badge reading <b>Valid</b>. Every instrument a customer had said
 * the thing was working.
 *
 * <p>That is the same shape as the other bugs this codebase keeps finding: the
 * answer is well-formed, plausible and wrong. So the check lives here and is
 * applied in two places — once when a definition is saved, and again before a
 * run is dispatched — because a definition can reach the runtime without ever
 * passing through the save path. {@code publish.py} inserts catalog rows
 * directly, which is exactly how these three got in.
 */
public final class WorkflowGraph {

    private WorkflowGraph() {
    }

    /**
     * Node ids that no path from {@code start} reaches, in declaration order.
     *
     * <p>Empty means the graph is walkable — not that it is correct, which is a
     * question only running it answers.
     *
     * <p><b>A definition with no {@code start} node is exempt</b>, and that is
     * deliberate rather than an oversight. The single-node workflows that back
     * an agent's tools are one PowerShell or Python operation with no graph at
     * all; they are dispatched directly, never walked, and have no edges to
     * miss. Eleven of them ship today. Demanding a start node of those would
     * reject every one and break every agent — a check that fires on the
     * healthy majority gets switched off, and then it protects nothing.
     */
    public static List<String> unreachable(JsonNode definition) {
        if (definition == null || !definition.path("nodes").isArray()) {
            return List.of();
        }
        JsonNode nodes = definition.path("nodes");

        Set<String> declared = new LinkedHashSet<>();
        String start = null;
        for (JsonNode node : nodes) {
            String id = node.path("id").asText(null);
            if (id == null || id.isBlank()) {
                // A node without an id cannot be an edge target, so it cannot
                // be reached by anything. Named rather than skipped: silently
                // ignoring it is how a malformed definition passes a check
                // written to catch malformed definitions.
                declared.add("<node with no id>");
                continue;
            }
            declared.add(id);
            if (start == null && "start".equalsIgnoreCase(node.path("type").asText(""))) {
                start = id;
            }
        }
        if (start == null || declared.size() <= 1) {
            return List.of();
        }

        Set<String> seen = new LinkedHashSet<>();
        Deque<String> pending = new ArrayDeque<>();
        pending.add(start);
        seen.add(start);
        while (!pending.isEmpty()) {
            String from = pending.poll();
            for (JsonNode edge : definition.path("edges")) {
                // `source`/`target` is the shape every shipped definition uses.
                // `from`/`to` is accepted because the canvas emits it, and a
                // graph rejected for spelling would be a maddening thing to
                // debug from the message this produces.
                String source = text(edge, "source", "from");
                String target = text(edge, "target", "to");
                if (from.equals(source) && target != null && seen.add(target)) {
                    pending.add(target);
                }
            }
        }

        List<String> orphans = new ArrayList<>();
        for (String id : declared) {
            if (!seen.contains(id)) {
                orphans.add(id);
            }
        }
        return orphans;
    }

    /** A sentence for a human, naming what cannot run and the likely reason. */
    public static String describe(List<String> unreachable) {
        String ids = String.join(", ", unreachable);
        return "This workflow cannot run: " + unreachable.size()
                + " step(s) are not connected to the start of the graph (" + ids + "). "
                + "The definition has nodes but no path to them, so a run would execute "
                + "the start step and stop without doing anything.";
    }

    private static String text(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.path(name);
            if (value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }
}
