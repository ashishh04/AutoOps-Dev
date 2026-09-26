package com.intertec.autoops.core.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether a workflow definition can be walked at all.
 *
 * <p>This pins a bug that shipped and stayed invisible: the publisher dropped
 * {@code edges} when writing catalog rows, so three workflows arrived with
 * three nodes and no connections between them. A run executed {@code start},
 * found nothing to follow, finished in 70ms having produced no output — and
 * reported <b>Success</b>, under a validation badge reading <b>Valid</b>.
 *
 * <p>So the tests that matter most here are the two that decide whether the
 * check is usable at all: it must fire on the broken shape, and it must stay
 * silent on the single-node tool workflows that legitimately have no graph.
 * A check that also rejected those would be switched off within a day.
 */
class WorkflowGraphTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private java.util.List<String> unreachable(String json) throws Exception {
        return WorkflowGraph.unreachable(mapper.readTree(json));
    }

    @Test
    @DisplayName("nodes with NO edges at all — the shape that shipped")
    void missingEdgesIsCaught() throws Exception {
        // Exactly what was found in the catalog: start -> ? -> end, no edges.
        String json = """
                {"nodes":[{"id":"start","type":"start"},
                          {"id":"open","type":"platform"},
                          {"id":"end","type":"end"}]}
                """;

        assertThat(unreachable(json)).containsExactly("open", "end");
    }

    @Test
    @DisplayName("a properly connected graph is clean")
    void connectedGraphIsClean() throws Exception {
        String json = """
                {"nodes":[{"id":"start","type":"start"},
                          {"id":"open","type":"platform"},
                          {"id":"end","type":"end"}],
                 "edges":[{"source":"start","target":"open"},
                          {"source":"open","target":"end"}]}
                """;

        assertThat(unreachable(json)).isEmpty();
    }

    @Test
    @DisplayName("a single-node tool workflow is EXEMPT, not broken")
    void singleNodeToolWorkflowIsExempt() throws Exception {
        // Eleven of these back the agents' tools. One PowerShell or Python
        // operation, no start node, no graph, dispatched directly. Rejecting
        // them would break every agent — and a check that fires on the healthy
        // majority gets switched off, after which it protects nothing.
        String json = """
                {"nodes":[{"id":"run","type":"pyscript"}]}
                """;

        assertThat(unreachable(json)).isEmpty();
    }

    @Test
    @DisplayName("a definition with no start node is left alone")
    void noStartNodeIsLeftAlone() throws Exception {
        String json = """
                {"nodes":[{"id":"a","type":"pyscript"},{"id":"b","type":"pyscript"}]}
                """;

        assertThat(unreachable(json)).isEmpty();
    }

    @Test
    @DisplayName("one orphaned branch is caught even when the rest connects")
    void partiallyConnectedIsCaught() throws Exception {
        // The subtler version, and the one a human reading the JSON would miss:
        // the graph looks wired because most of it is.
        String json = """
                {"nodes":[{"id":"start","type":"start"},
                          {"id":"a","type":"llm"},
                          {"id":"orphan","type":"llm"},
                          {"id":"end","type":"end"}],
                 "edges":[{"source":"start","target":"a"},
                          {"source":"a","target":"end"}]}
                """;

        assertThat(unreachable(json)).containsExactly("orphan");
    }

    @Test
    @DisplayName("the canvas's from/to spelling is accepted")
    void canvasEdgeSpellingIsAccepted() throws Exception {
        // A graph rejected over spelling would be maddening to debug from the
        // message this produces.
        String json = """
                {"nodes":[{"id":"start","type":"start"},{"id":"end","type":"end"}],
                 "edges":[{"from":"start","to":"end"}]}
                """;

        assertThat(unreachable(json)).isEmpty();
    }

    @Test
    @DisplayName("a node with no id is named, not silently skipped")
    void idlessNodeIsNamed() throws Exception {
        // RD-079 ships with four nodes and no ids at all. Skipping them is how
        // a malformed definition passes a check written to catch malformed
        // definitions.
        String json = """
                {"nodes":[{"id":"start","type":"start"},{"type":"llm"}],
                 "edges":[]}
                """;

        assertThat(unreachable(json)).containsExactly("<node with no id>");
    }

    @Test
    @DisplayName("a cycle does not hang the walk")
    void cycleTerminates() throws Exception {
        String json = """
                {"nodes":[{"id":"start","type":"start"},{"id":"a"},{"id":"b"}],
                 "edges":[{"source":"start","target":"a"},
                          {"source":"a","target":"b"},
                          {"source":"b","target":"a"}]}
                """;

        assertThat(unreachable(json)).isEmpty();
    }

    @Test
    @DisplayName("the message says what cannot run and why a run would look fine")
    void messageExplainsTheSymptom() {
        // The customer's instruments all said Success. The message has to
        // explain that, or the next person re-debugs it from scratch.
        String message = WorkflowGraph.describe(java.util.List.of("open", "end"));

        assertThat(message).contains("open, end");
        assertThat(message).contains("start step and stop");
    }

    @Test
    @DisplayName("a definition with no nodes[] is not this check's problem")
    void missingNodesIsNotOurProblem() throws Exception {
        assertThat(unreachable("{}")).isEmpty();
        assertThat(WorkflowGraph.unreachable(null)).isEmpty();
    }
}
