package com.intertec.autoops.core.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intertec.autoops.core.config.CoreProperties;
import com.intertec.autoops.core.exception.CoreException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs a native workflow graph on agent-runtime.
 *
 * <p>The replacement for the deleted Dify bridge. Two things about it are
 * deliberately identical to what it replaces, because they are what the layers
 * above already depend on:
 *
 * <ul>
 *   <li>{@link Progress} has the same signature, so
 *       {@code ExecutionEngine}'s per-node log writing is untouched;</li>
 *   <li>{@link RunOutcome} has the same shape, so the run row is populated the
 *       same way.</li>
 * </ul>
 *
 * <p>What changes underneath is everything else. There is no app key, no second
 * credential store and no remote form lookup: the definition travels with the
 * request, the tenant's own model credentials are resolved here and sent with
 * it, and the input form is read out of the definition rather than fetched from
 * a vendor over the network on every list.
 *
 * <p><b>NDJSON, not SSE.</b> One JSON object per line, consumed with
 * {@link BufferedReader#readLine()}. Dify spoke SSE, which meant stripping
 * {@code data:} prefixes, skipping blank separators and ignoring keep-alive
 * {@code ping} frames — three ways to mis-parse a stream, for framing this does
 * not need.
 *
 * <p><b>Streaming is for progress, not latency.</b> A blocking call returns one
 * response when the whole workflow is done, which for a long workflow is many
 * minutes during which the run screen shows nothing, and an operator watching a
 * motionless spinner reasonably cancels work that was about to succeed. It also
 * removes a failure mode: a blocking call sits on one idle socket and dies on
 * the first read timeout, whereas a streamed one has traffic every few seconds,
 * so the timeout bounds the gap BETWEEN nodes rather than the length of the run.
 */
@Component
public class WorkflowRuntimeClient {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRuntimeClient.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CoreProperties properties;
    private final RestClient restClient;

    public WorkflowRuntimeClient(CoreProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) properties.getRuntime().getConnectTimeout().toMillis());
        factory.setReadTimeout((int) properties.getRuntime().getReadTimeout().toMillis());
        this.restClient = RestClient.builder()
                .baseUrl(properties.getRuntime().getBaseUrl())
                .requestFactory(factory)
                .build();
    }

    /**
     * A node of the workflow started or finished.
     *
     * <p>Called on the execution thread as each event arrives, so the run's log
     * is written while the workflow is still going. Implementations must be
     * quick and must not throw — a listener that failed would abort a workflow
     * over a bookkeeping problem.
     *
     * <p>Signature copied verbatim from the bridge it replaces. That is
     * the point: the callback above this is the live run log, and swapping the
     * engine must not change what an operator sees.
     */
    @FunctionalInterface
    public interface Progress {

        Progress IGNORED = (title, finished, index, elapsedMs, failed) -> { };

        void node(String title, boolean finished, Integer index, Long elapsedMs, boolean failed);
    }

    /** One run's outcome. Same shape the deleted bridge returned. */
    public record RunOutcome(boolean success, String status, String error,
                             String outputs, Long elapsedMs, Integer totalSteps,
                             String workflowRunId) {
    }

    /** The model a workflow's LLM nodes run against. */
    public record ModelBinding(String model, String vendor, Map<String, String> credentials) {
    }

    /**
     * Executes a workflow definition, reporting each node as it happens.
     *
     * @param definition the workflow graph, verbatim from the run's snapshot
     * @param binding    the tenant's resolved model credentials, or null for a
     *                   workflow with no LLM node. Null is legal: a graph of
     *                   http/template/condition nodes needs no model, and
     *                   demanding one would block a perfectly good workflow
     *                   behind a vendor connection it never uses.
     */
    public RunOutcome run(Long runId, String tenantId, Long projectId, String definition,
                          Map<String, Object> inputs, ModelBinding binding, Progress progress) {
        JsonNode graph;
        try {
            graph = MAPPER.readTree(definition == null ? "{}" : definition);
        } catch (Exception ex) {
            throw CoreException.badRequest("invalid_definition",
                    "This workflow's definition is not readable JSON.");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", runId);
        payload.put("tenantId", tenantId);
        // The `platform` node reads a PROJECT's history, so the runtime needs
        // to know which project this run belongs to. Tenant alone would make
        // "what happened here" a question about the whole customer.
        payload.put("projectId", projectId);
        payload.put("definition", graph);
        payload.put("inputs", inputs == null ? Map.of() : inputs);
        payload.put("model", binding == null
                // The runtime requires the field; a graph with no LLM node
                // never builds a client from it, so an empty descriptor is
                // honest rather than a placeholder credential.
                ? Map.of("model", "", "vendor", "OPENAI", "credentials", Map.of())
                : Map.of("model", binding.model(),
                         "vendor", binding.vendor(),
                         "credentials", binding.credentials()));

        long started = System.currentTimeMillis();
        try {
            RunOutcome outcome = restClient.post()
                    .uri("/v1/workflows/run")
                    .header("X-Internal-Token", properties.getRuntime().getInternalToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.ALL)
                    .body(payload)
                    .exchange((req, response) -> consume(response.getBody(), progress), false);

            long elapsed = System.currentTimeMillis() - started;
            if (outcome == null) {
                // The stream ended without a `done` event. That is NOT the same
                // as a failed run: the workflow may have completed and had side
                // effects, and saying "it failed" would be a claim we cannot
                // support. See the runtime's own note on this guarantee.
                return new RunOutcome(false, "unknown",
                        "The workflow runtime closed the stream without reporting an "
                                + "outcome. The run may or may not have completed — check "
                                + "the runtime's logs before re-running it.",
                        null, elapsed, null, null);
            }
            return new RunOutcome(outcome.success(), outcome.status(), outcome.error(),
                    outcome.outputs(), elapsed, outcome.totalSteps(), outcome.workflowRunId());
        } catch (Exception ex) {
            log.warn("Workflow run {} could not be dispatched to the runtime: {}",
                    runId, ex.getMessage());
            throw CoreException.badGateway("workflow_runtime_unreachable",
                    "Could not reach the workflow runtime: " + ex.getMessage());
        }
    }

    /**
     * Validates a definition and returns its declared input form.
     *
     * <p>Replaces fetching a Dify app's {@code /parameters} over the network on
     * every list and every run. The form now travels WITH the definition, so it
     * cannot desynchronise from the variables the workflow actually reads —
     * which was the failure the remote lookup existed to avoid, and which it
     * only ever avoided while Dify was reachable.
     */
    public JsonNode inspect(String definition) {
        try {
            return restClient.post()
                    .uri("/v1/workflows/inspect")
                    .header("X-Internal-Token", properties.getRuntime().getInternalToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(MAPPER.readTree(definition == null ? "{}" : definition))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception ex) {
            log.warn("Could not inspect a workflow definition: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * Reads the NDJSON stream and returns the final outcome, or null when the
     * stream ends without one.
     *
     * <p>A line that will not parse is skipped rather than thrown on: one
     * malformed frame must not lose a workflow that finished.
     */
    private RunOutcome consume(InputStream body, Progress progress) throws IOException {
        RunOutcome outcome = null;
        int index = 0;
        try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode event;
                try {
                    event = MAPPER.readTree(line);
                } catch (Exception ex) {
                    log.debug("Skipping unparseable runtime event: {}", ex.getMessage());
                    continue;
                }
                String type = event.path("event").asText("");
                if ("node".equals(type)) {
                    boolean finished = event.path("finished").asBoolean(false);
                    if (!finished) {
                        index++;
                    }
                    Long elapsed = event.hasNonNull("elapsedMs")
                            ? event.path("elapsedMs").asLong() : null;
                    try {
                        progress.node(event.path("title").asText(event.path("nodeId").asText("node")),
                                finished, index, elapsed, event.path("failed").asBoolean(false));
                    } catch (Exception ex) {
                        // A bookkeeping failure must not abort a running workflow.
                        log.warn("Progress listener threw, continuing: {}", ex.getMessage());
                    }
                } else if ("done".equals(type)) {
                    boolean success = event.path("success").asBoolean(false);
                    JsonNode outputs = event.get("outputs");
                    outcome = new RunOutcome(success, success ? "succeeded" : "failed",
                            event.path("error").asText(null),
                            outputs == null || outputs.isNull() ? null : outputs.toString(),
                            null,
                            event.hasNonNull("totalNodes")
                                    ? event.path("totalNodes").asInt() : null,
                            event.path("traceId").asText(null));
                }
            }
        }
        return outcome;
    }

    /** Field names this build understands, for the health/readiness surface. */
    public List<String> supportedNodeTypes() {
        return List.of("start", "llm", "http", "template", "condition", "end");
    }
}
