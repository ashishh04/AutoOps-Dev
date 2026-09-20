package com.intertec.autoops.agent.client;

import com.intertec.autoops.agent.config.AgentProperties;
import com.intertec.autoops.agent.exception.AgentException;
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
 * The hands. {@link ToolTargetClient} answers "may the agent touch this";
 * this one actually touches it.
 *
 * <p>The two are separate on purpose. Validation is read-only and runs on
 * every agent save; execution starts work on a tenant's infrastructure and
 * runs only inside a run. Keeping them in one class would put the method that
 * reboots a server one autocomplete away from the method that lists names.
 *
 * <p>Every call lands on core-service's {@code /internal/agent/*}, which
 * decides RUN vs APPROVAL using the tenant's own policy. This client does not
 * make that decision and does not second-guess it.
 */
@Component
public class AutomationClient {

    private static final Logger log = LoggerFactory.getLogger(AutomationClient.class);

    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() { };

    private final RestClient coreRestClient;
    private final String coreToken;

    public AutomationClient(@Qualifier("coreRestClient") RestClient coreRestClient,
                            AgentProperties properties) {
        this.coreRestClient = coreRestClient;
        this.coreToken = properties.getCore().getInternalToken();
    }

    /**
     * @param mode       RUN or APPROVAL — core decided, not us
     * @param runId      set when mode is RUN
     * @param approvalId set when mode is APPROVAL
     */
    public record Dispatch(String mode, Long runId, Long approvalId, String targetName) {

        public boolean needsApproval() {
            return "APPROVAL".equals(mode);
        }
    }

    /** A run's live state, trimmed to what a model can usefully be told. */
    public record RunState(String status, boolean terminal, String targetName,
                           int stepCompleted, int stepTotal, String error, String log) {

        public boolean succeeded() {
            return "SUCCEEDED".equals(status);
        }
    }

    /** An approval's verdict. {@code runId} is set only once APPROVED. */
    public record ApprovalState(String status, String decidedBy, Long runId,
                                String targetName) {

        public boolean pending() {
            return "PENDING".equals(status);
        }

        public boolean approved() {
            return "APPROVED".equals(status);
        }
    }

    /** One field of a workflow's published input form. */
    public record InputField(String variable, String label, String type, boolean required,
                             List<String> options) {
    }

    /**
     * @param error non-null when the workflow's form could not be read —
     *              revoked — the tool is then left OUT of the model's list
     *              rather than offered and failing on first use
     */
    public record WorkflowInputs(List<InputField> fields, String error, String description) {

        /** Pre-description callers and tests; the title is then all there is. */
        public WorkflowInputs(List<InputField> fields, String error) {
            this(fields, error, null);
        }
    }

    /** Start a target, or raise the approval that must precede it. */
    public Dispatch dispatch(String tenantId, String actor, String targetType, Long targetId,
                             Map<String, Object> inputs) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", tenantId);
        body.put("actor", actor);
        body.put("targetType", targetType);
        body.put("targetId", targetId);
        body.put("inputs", inputs);

        Map<String, Object> response = post("/internal/agent/dispatch", body);
        return new Dispatch(str(response.get("mode")), asLong(response.get("runId")),
                asLong(response.get("approvalId")), str(response.get("targetName")));
    }

    public RunState runState(String tenantId, Long runId) {
        Map<String, Object> response = get("/internal/agent/runs/{id}?tenantId={tenantId}",
                runId, tenantId);
        return new RunState(str(response.get("status")),
                Boolean.TRUE.equals(response.get("terminal")),
                str(response.get("targetName")),
                asInt(response.get("stepCompleted")), asInt(response.get("stepTotal")),
                str(response.get("error")), str(response.get("log")));
    }

    public ApprovalState approvalState(String tenantId, Long approvalId) {
        Map<String, Object> response = get("/internal/agent/approvals/{id}?tenantId={tenantId}",
                approvalId, tenantId);
        return new ApprovalState(str(response.get("status")), str(response.get("decidedBy")),
                asLong(response.get("runId")), str(response.get("targetName")));
    }

    /**
     * The input form a workflow tool should expose.
     *
     * <p>Unreachable core is reported as an {@code error}, not as an empty
     * form. An empty form means "this workflow takes no arguments", and a
     * model told that about a workflow that actually needs three would call it
     * with none and get a confusing failure — or worse, a run that
     * succeeds with every variable unset.
     */
    @SuppressWarnings("unchecked")
    public WorkflowInputs workflowInputs(String tenantId, Long workflowId) {
        try {
            Map<String, Object> response = get(
                    "/internal/agent/workflow-inputs?tenantId={tenantId}&workflowId={workflowId}",
                    tenantId, workflowId);

            List<InputField> fields = new ArrayList<>();
            Object raw = response.get("fields");
            if (raw instanceof List<?> rows) {
                for (Object row : rows) {
                    if (row instanceof Map<?, ?> map) {
                        Map<String, Object> field = (Map<String, Object>) map;
                        fields.add(new InputField(
                                str(field.get("variable")), str(field.get("label")),
                                str(field.get("type")),
                                Boolean.TRUE.equals(field.get("required")),
                                field.get("options") instanceof List<?> options
                                        ? options.stream().map(AutomationClient::str).toList()
                                        : List.of()));
                    }
                }
            }
            return new WorkflowInputs(fields, str(response.get("error")),
                    str(response.get("description")));
        } catch (Exception ex) {
            log.warn("Workflow {} inputs unavailable for tenant {}: {}", workflowId, tenantId,
                    ex.getMessage());
            return new WorkflowInputs(List.of(), "Input form unavailable: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------ plumbing --

    private Map<String, Object> post(String uri, Map<String, Object> body) {
        try {
            Map<String, Object> response = coreRestClient.post()
                    .uri(uri)
                    .header("X-Internal-Token", coreToken)
                    .body(body)
                    .retrieve()
                    .body(MAP);
            return response == null ? Map.of() : response;
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            // core's own message is the useful one — "Fill in: Hostname",
            // "An approval request for this job is already waiting". It goes
            // back to the model as the tool's error, so it must not be
            // flattened into "something went wrong".
            throw AgentException.badRequest("automation_refused", coreMessage(ex));
        } catch (Exception ex) {
            throw AgentException.serviceUnavailable("core_unavailable",
                    "Could not reach the automation service: " + ex.getMessage());
        }
    }

    private Map<String, Object> get(String uri, Object... args) {
        try {
            Map<String, Object> response = coreRestClient.get()
                    .uri(uri, args)
                    .header("X-Internal-Token", coreToken)
                    .retrieve()
                    .body(MAP);
            return response == null ? Map.of() : response;
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            throw AgentException.badRequest("automation_refused", coreMessage(ex));
        } catch (Exception ex) {
            throw AgentException.serviceUnavailable("core_unavailable",
                    "Could not reach the automation service: " + ex.getMessage());
        }
    }

    /** core's {@code {"message": "..."}} body, or the status line if absent. */
    private static String coreMessage(org.springframework.web.client.RestClientResponseException ex) {
        String body = ex.getResponseBodyAsString();
        if (body != null && body.contains("\"message\"")) {
            int start = body.indexOf("\"message\"");
            int quote = body.indexOf('"', body.indexOf(':', start) + 1);
            int end = quote < 0 ? -1 : body.indexOf('"', quote + 1);
            if (quote > 0 && end > quote) {
                return body.substring(quote + 1, end);
            }
        }
        return ex.getStatusText();
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Long asLong(Object value) {
        return value instanceof Number n ? n.longValue() : null;
    }

    private static int asInt(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }
}
