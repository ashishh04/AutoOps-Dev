package com.intertec.autoops.agent.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intertec.autoops.agent.config.AgentProperties;
import com.intertec.autoops.agent.exception.AgentException;
import com.intertec.autoops.agent.loop.ToolCall;
import com.intertec.autoops.agent.loop.ToolSpec;
import com.intertec.autoops.agent.modelsdk.ModelCredentials;
import com.intertec.autoops.agent.modelsdk.ModelVendor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The brain. {@link AutomationClient} is the hands.
 *
 * <p>Sends one {@code (state, event)} pair to the Python runtime and gets back
 * the next state plus a directive. This service keeps the loop, the database,
 * the approvals and the audit trail; the runtime decides only what should
 * happen next, and cannot make it happen.
 *
 * <h2>The state is opaque here on purpose</h2>
 * It arrives as a {@code Map} and is stored as the JSON string it serialises
 * to, unread. This service does not know what a phase is, what an evidence
 * ledger contains, or how a graph routes — and must not learn, because the
 * moment it does the two services have to be deployed together. The only
 * fields it reads are the directive, the tool calls and the output, which is
 * the contract it genuinely depends on.
 *
 * <h2>A failure here is a run outcome, not an exception</h2>
 * The runtime answers 200 with {@code directive: FAIL} for anything that went
 * wrong INSIDE a run, and this client passes that through. Only an unreachable
 * or malformed runtime raises — because that is the one case where the run's
 * state is genuinely unknown and continuing would be guessing.
 */
@Component
public class RuntimeClient {

    private static final Logger log = LoggerFactory.getLogger(RuntimeClient.class);

    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() { };

    private final RestClient runtimeRestClient;
    private final String token;
    private final ObjectMapper objectMapper;

    public RuntimeClient(@Qualifier("runtimeRestClient") RestClient runtimeRestClient,
                         AgentProperties properties, ObjectMapper objectMapper) {
        this.runtimeRestClient = runtimeRestClient;
        this.token = properties.getRuntime().getInternalToken();
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------ inputs ---

    /** What the runtime should be told happened since it last answered. */
    public sealed interface Event {

        record Start(String input) implements Event { }

        /**
         * Every result for one turn, together.
         *
         * <p>There is deliberately no separate "a human decided" event. Every
         * vendor requires all of a turn's tool calls to be answered at once, so
         * a turn that parked on its second call has to deliver that verdict
         * alongside the first call's result — which means the verdict belongs
         * on the {@link Result}, not beside it.
         */
        record ToolResults(List<Result> results) implements Event { }
    }

    /**
     * One tool result on its way back.
     *
     * @param evidenceId the id of the step row this result was written to.
     *                   This is the citation an operator can open, and it is
     *                   why the evidence ledger needs no table of its own —
     *                   it indexes rows this service was writing anyway.
     * @param decision   {@code APPROVED} or {@code REJECTED} when this result
     *                   came back through the approvals inbox; null otherwise.
     *                   It rides on the result rather than arriving as its own
     *                   event because a turn can hold several tool calls and
     *                   every vendor requires them answered together — so the
     *                   turn where a human rejected the second of three still
     *                   has to carry the other two.
     */
    public record Result(String callId, boolean ok, String content, Long evidenceId,
                         String decision, String decidedBy) {
    }

    /** A tool spec plus the one thing the runtime cannot work out for itself. */
    public record OfferedTool(ToolSpec spec, boolean mutating) {
    }

    // ----------------------------------------------------------- outputs ---

    public enum Directive {
        CALL_TOOLS, FINISH, FAIL
    }

    /**
     * @param state    stored verbatim; never inspected
     * @param citations evidence ids the report used, re-checked here against
     *                  this run's own step ids. The runtime enforces the same
     *                  rule, and doing it twice is deliberate: an id that was
     *                  never issued for this run must not survive even if the
     *                  runtime's own check is wrong.
     */
    /**
     * @param declaredSubjectKinds the kinds this agent's tools can enumerate.
     *                             A property of the AGENT, present on every
     *                             reduce, and what the run's opening scope is
     *                             built from. Deliberately not derived from
     *                             results seen so far: that would make the
     *                             declaration a function of which tool happened
     *                             to run first, and a coverage claim that varies
     *                             with scheduling has no business near state
     *                             deletion.
     * @param subjectScope         what the run actually covered, per kind.
     *                             Present ONLY on a terminal directive — a run
     *                             that stopped halfway examined an unknown
     *                             fraction of what it set out to, and publishing
     *                             that fraction as coverage is how one bad night
     *                             reaps a backlog.
     * @param subjectIds           the ids behind every enumerated element.
     *                             Travels beside the scope rather than inside
     *                             it, so the digest the runtime computed can be
     *                             checked against the subjects it sent.
     * @param findings             the run's verdicts — what a scheduler
     *                             deduplicates against, as opposed to the prose
     *                             report a person reads. Emitted only on a
     *                             FINISHed run: a half-finished investigation
     *                             published as verdicts can be dismissed as
     *                             though it were complete.
     */
    public record Reduction(String state, Integer stateVersion, String phase,
                            Directive directive, List<ToolCall> toolCalls, String output,
                            String error, long promptTokens, long completionTokens,
                            int modelCalls, String traceId, List<Long> citations,
                            List<String> uncitedClaims, List<String> declaredSubjectKinds,
                            JsonNode subjectScope, Map<String, List<String>> subjectIds,
                            JsonNode findings) {

        public Reduction {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
            citations = citations == null ? List.of() : List.copyOf(citations);
            uncitedClaims = uncitedClaims == null ? List.of() : List.copyOf(uncitedClaims);
            declaredSubjectKinds = declaredSubjectKinds == null
                    ? List.of() : List.copyOf(declaredSubjectKinds);
            subjectIds = subjectIds == null ? Map.of() : Map.copyOf(subjectIds);
        }

        /** The verdicts, never null — an absent list reads as no findings. */
        public List<JsonNode> verdicts() {
            if (findings == null || !findings.isArray()) {
                return List.of();
            }
            List<JsonNode> out = new ArrayList<>();
            findings.forEach(out::add);
            return out;
        }

        /** True when the runtime reported coverage, which only a finished run does. */
        public boolean carriesCoverage() {
            return subjectScope != null && subjectScope.isArray() && !subjectScope.isEmpty();
        }

        public boolean failed() {
            return directive == Directive.FAIL;
        }
    }

    // ------------------------------------------------------------- calls ---

    /**
     * Advances one run by one boundary.
     *
     * @param state the previous reduction's state, or null to start
     */
    public Reduction reduce(Request request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("agent", agentOf(request));
        body.put("tools", toolsOf(request.tools()));
        body.put("state", readState(request.state()));
        body.put("event", eventOf(request.event()));
        body.put("run_id", request.runId());
        body.put("tenant_id", request.tenantId());
        body.put("unavailable", request.unavailable());

        Map<String, Object> response;
        try {
            response = runtimeRestClient.post()
                    .uri("/v1/reduce")
                    .header("X-Internal-Token", token)
                    .body(body)
                    .retrieve()
                    .body(MAP);
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            // The runtime answers 200 for anything that is a run outcome, so a
            // status here means the REQUEST was rejected — usually a schema
            // mismatch after a one-sided deploy.
            //
            // The body names the offending field; the first version of this
            // discarded it and said only "the two services may be on different
            // versions", which turned a one-line fix into a hunt. Whatever
            // FastAPI says about which field it disliked is the useful part.
            throw AgentException.serviceUnavailable("runtime_rejected",
                    "The agent runtime rejected this run (" + ex.getStatusCode() + "): "
                            + validationDetail(ex));
        } catch (Exception ex) {
            throw AgentException.serviceUnavailable("runtime_unavailable",
                    "Could not reach the agent runtime: " + ex.getMessage());
        }
        if (response == null) {
            throw AgentException.serviceUnavailable("runtime_unavailable",
                    "The agent runtime returned nothing.");
        }

        return read(response);
    }

    /**
     * @param state      the previous reduction's state; null starts a run
     * @param unavailable allow-list entries that could not be offered, with the
     *                    reason. Passed on so the agent can SAY a tool is gone
     *                    rather than behaving as though it never had one.
     */
    public record Request(Long runId, String tenantId, String ref, String version,
                          String model, ModelVendor vendor, ModelCredentials credentials,
                          String instructions, String state, Event event,
                          List<OfferedTool> tools, List<String> unavailable) {

        public Request {
            tools = tools == null ? List.of() : List.copyOf(tools);
            unavailable = unavailable == null ? List.of() : List.copyOf(unavailable);
        }
    }

    // ---------------------------------------------------------- encoding ---

    private Map<String, Object> agentOf(Request request) {
        Map<String, Object> agent = new LinkedHashMap<>();
        agent.put("ref", request.ref());
        agent.put("version", request.version());
        agent.put("model", request.model());
        agent.put("vendor", request.vendor().name());
        agent.put("credentials", request.credentials() == null
                ? Map.of() : request.credentials().values());
        // Populated only for legacy JSON agents, whose persona lives in the
        // tenant's own row. A Python-authored agent's prompts are in the
        // runtime's image and this is null for them — which is the point.
        agent.put("instructions", request.instructions());
        return agent;
    }

    private List<Map<String, Object>> toolsOf(List<OfferedTool> tools) {
        List<Map<String, Object>> encoded = new ArrayList<>();
        for (OfferedTool tool : tools) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", tool.spec().name());
            entry.put("description", tool.spec().description());
            entry.put("input_schema", tool.spec().inputSchema());
            entry.put("mutating", tool.mutating());
            // The stable name the agent's author declared against. The runtime
            // cannot use `name` for this — it is workflow_<id>, a tenant-local
            // number — and it needs the ref to match a result back to what the
            // author said the tool returns.
            if (tool.spec().ref() != null) {
                entry.put("ref", tool.spec().ref());
            }
            encoded.add(entry);
        }
        return encoded;
    }

    private Map<String, Object> eventOf(Event event) {
        Map<String, Object> encoded = new LinkedHashMap<>();
        switch (event) {
            case Event.Start start -> {
                encoded.put("kind", "START");
                encoded.put("input", start.input());
            }
            case Event.ToolResults results -> {
                encoded.put("kind", "TOOL_RESULTS");
                List<Map<String, Object>> rows = new ArrayList<>();
                for (Result result : results.results()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("call_id", result.callId());
                    row.put("ok", result.ok());
                    row.put("content", result.content());
                    row.put("evidence_id", result.evidenceId());
                    row.put("decision", result.decision());
                    row.put("decided_by", result.decidedBy());
                    rows.add(row);
                }
                encoded.put("results", rows);
            }
        }
        return encoded;
    }

    /**
     * The stored state, back into the shape the wire wants.
     *
     * <p>Unreadable JSON is sent as null rather than raising, which the runtime
     * turns into "there is nothing to resume". A corrupt transcript should end
     * one run with a clear message, not take down the loop.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> readState(String state) {
        if (state == null || state.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(state, Map.class);
        } catch (Exception ex) {
            log.warn("Unreadable agent state; the run will be told it cannot resume: {}",
                    ex.getMessage());
            return null;
        }
    }

    // ---------------------------------------------------------- decoding ---

    @SuppressWarnings("unchecked")
    private Reduction read(Map<String, Object> response) {
        String state;
        try {
            Object raw = response.get("state");
            state = raw == null ? null : objectMapper.writeValueAsString(raw);
        } catch (Exception ex) {
            throw AgentException.serviceUnavailable("runtime_unavailable",
                    "The agent runtime returned a state that could not be stored: "
                            + ex.getMessage());
        }

        List<ToolCall> calls = new ArrayList<>();
        if (response.get("tool_calls") instanceof List<?> rows) {
            for (Object row : rows) {
                if (row instanceof Map<?, ?> map) {
                    Map<String, Object> call = (Map<String, Object>) map;
                    calls.add(new ToolCall(str(call.get("id")), str(call.get("name")),
                            call.get("arguments") instanceof Map<?, ?> args
                                    ? (Map<String, Object>) args : Map.of()));
                }
            }
        }

        Map<String, Object> usage = response.get("usage") instanceof Map<?, ?> map
                ? (Map<String, Object>) map : Map.of();

        return new Reduction(state, asInt(response.get("state_version")),
                str(response.get("phase")),
                directiveOf(str(response.get("directive"))), calls,
                str(response.get("output")), str(response.get("error")),
                asLong(usage.get("prompt_tokens")), asLong(usage.get("completion_tokens")),
                modelCallsOf(response), str(response.get("trace_id")),
                longs(response.get("citations")),
                strings(response.get("uncited_claims")),
                strings(response.get("declared_subject_kinds")),
                objectMapper.valueToTree(response.get("subject_scope")),
                subjectIdsOf(response.get("subject_ids")),
                objectMapper.valueToTree(response.get("findings")));
    }

    /**
     * The ids behind each enumerated element, keyed by subject kind.
     *
     * <p>An unreadable shape yields an EMPTY map rather than an exception: the
     * consequence is a scope agent-service refuses to materialise, which costs
     * this run its reap. That is the safe direction, and the alternative — a
     * 500 on the reduce — costs the run its report as well.
     */
    @SuppressWarnings("unchecked")
    private Map<String, List<String>> subjectIdsOf(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        map.forEach((kind, ids) -> {
            if (kind instanceof String name && ids instanceof List<?> rows) {
                List<String> values = new ArrayList<>();
                rows.forEach(id -> values.add(String.valueOf(id)));
                out.put(name, values);
            }
        });
        return out;
    }

    /**
     * An unrecognised directive is FAIL, never a default that continues.
     *
     * <p>A newer runtime inventing a directive this build does not know must
     * stop the run, not be treated as FINISH — a run reported as finished
     * having done something this service could not name is unauditable.
     */
    private static Directive directiveOf(String value) {
        try {
            return Directive.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException ex) {
            log.error("Unknown directive \"{}\" from the agent runtime; failing the run.", value);
            return Directive.FAIL;
        }
    }

    private static List<Long> longs(Object value) {
        List<Long> values = new ArrayList<>();
        if (value instanceof List<?> rows) {
            for (Object row : rows) {
                if (row instanceof Number number) {
                    values.add(number.longValue());
                }
            }
        }
        return values;
    }

    private static List<String> strings(Object value) {
        List<String> values = new ArrayList<>();
        if (value instanceof List<?> rows) {
            for (Object row : rows) {
                values.add(String.valueOf(row));
            }
        }
        return values;
    }

    /**
     * FastAPI's validation complaint, flattened to one readable line.
     *
     * <p>The body is {@code {"detail":[{"loc":["body","agent","ref"],"msg":…}]}}.
     * The {@code loc} path is the whole diagnosis — it says exactly which field
     * was wrong — so it is worth the small amount of parsing here rather than
     * making someone reproduce the call by hand to see it.
     */
    private String validationDetail(
            org.springframework.web.client.RestClientResponseException ex) {
        String body = ex.getResponseBodyAsString();
        if (body == null || body.isBlank()) {
            return "no detail returned; the two services may be on different versions";
        }
        try {
            JsonNode detail = objectMapper.readTree(body).path("detail");
            if (detail.isArray() && !detail.isEmpty()) {
                List<String> problems = new ArrayList<>();
                for (JsonNode entry : detail) {
                    List<String> path = new ArrayList<>();
                    entry.path("loc").forEach(part -> path.add(part.asText()));
                    problems.add(String.join(".", path) + ": " + entry.path("msg").asText());
                }
                return String.join("; ", problems);
            }
            if (detail.isTextual()) {
                return detail.asText();
            }
        } catch (Exception ignored) {
            // Not JSON, or not FastAPI's shape — the raw body is still better
            // than a generic sentence.
        }
        return body.length() > 300 ? body.substring(0, 300) + "…" : body;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static Integer asInt(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    /**
     * How many model calls the runtime made, floored at ONE.
     *
     * <p>The floor is the point. This number is added to the run's step count,
     * and the step budget is what stops a run costing a weekend. A runtime that
     * omitted the field — an older build, a malformed reply — would report zero
     * and the budget would never advance, turning a loop into an unbounded one.
     * Charging one call for a reduce that made none is the harmless direction to
     * be wrong in.
     */
    private static int modelCallsOf(Map<String, Object> response) {
        Integer calls = asInt(response.get("model_calls"));
        return calls == null || calls < 1 ? 1 : calls;
    }
}
