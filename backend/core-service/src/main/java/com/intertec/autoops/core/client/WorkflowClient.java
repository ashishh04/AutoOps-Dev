package com.intertec.autoops.core.client;

import com.intertec.autoops.core.config.CoreProperties;
import com.intertec.autoops.core.exception.CoreException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Workflow definitions, which now live in workflow-service.
 *
 * <p>This is the seam the split left behind. Everything in core-service that
 * used to reach {@code WorkflowRepository} — the run engine, approval
 * interception, governance checks, webhook targets, SCM sync and compliance
 * evidence — comes through here instead, and gets a {@link WorkflowView}
 * record where it used to get a JPA entity.
 *
 * <p>Failure policy: a workflow that cannot be fetched is NOT treated as
 * missing. {@link #find} returns empty only on a real 404; anything else
 * (workflow-service down, timeout) throws 503 {@code workflow_unavailable}.
 * Collapsing the two would let an outage look like "this workflow was
 * deleted", which in the governance and compliance paths would silently
 * report a project as having no workflows to gate.
 */
@Component
public class WorkflowClient {

    private static final Logger log = LoggerFactory.getLogger(WorkflowClient.class);

    private final RestClient workflowRestClient;
    private final String internalToken;

    public WorkflowClient(@Qualifier("workflowRestClient") RestClient workflowRestClient,
                          CoreProperties properties) {
        this.workflowRestClient = workflowRestClient;
        this.internalToken = properties.getWorkflow().getInternalToken();
    }

    /**
     * The projection core-service works with. Same fields the entity exposed
     * to these call sites, minus the JPA machinery.
     */
    public record WorkflowView(Long id, String tenantId, Long projectId, String name,
                               String definition, int nodeCount, boolean enabled,
                               String delivery) {

        /**
         * A view with no delivery stated, which is treated as a product.
         *
         * <p>Matches what an older workflow-service sends and what every
         * caller that has no opinion means. Keeping it as a constructor rather
         * than making every call site say "PRODUCT" also keeps the default in
         * one place, next to the reasoning for it.
         */
        public WorkflowView(Long id, String tenantId, Long projectId, String name,
                            String definition, int nodeCount, boolean enabled) {
            this(id, tenantId, projectId, name, definition, nodeCount, enabled, "PRODUCT");
        }

        /**
         * True when this copy exists only so an agent has something to call.
         *
         * <p>Defaults to false when the field is absent, which is what an
         * older workflow-service sends. That is the safe direction for the
         * skew: a component briefly behaving like a product is a visible
         * automation, where the reverse would make a delivered agent's tool
         * unrunnable.
         */
        public boolean isAgentComponent() {
            return "AGENT_COMPONENT".equals(delivery);
        }
    }

    /** Empty ONLY when workflow-service says 404. */
    public Optional<WorkflowView> find(String tenantId, Long workflowId) {
        try {
            return Optional.ofNullable(workflowRestClient.get()
                    .uri("/internal/workflows/{id}?tenantId={tenantId}", workflowId, tenantId)
                    .header("X-Internal-Token", internalToken)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        throw new NotFound();
                    })
                    .body(WorkflowView.class));
        } catch (NotFound ex) {
            return Optional.empty();
        } catch (Exception ex) {
            throw unavailable(ex);
        }
    }

    /** The monolith's {@code workflowRepository.findByIdAndTenantId(...).orElseThrow(...)}. */
    public WorkflowView require(String tenantId, Long workflowId) {
        return find(tenantId, workflowId)
                .orElseThrow(() -> CoreException.notFound("workflow_not_found",
                        "No such workflow"));
    }

    /** A project's workflows — SCM export and compliance evidence. */
    public List<WorkflowView> listByProject(String tenantId, Long projectId) {
        try {
            List<WorkflowView> rows = workflowRestClient.get()
                    .uri("/internal/projects/{projectId}/workflows?tenantId={tenantId}",
                            projectId, tenantId)
                    .header("X-Internal-Token", internalToken)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<WorkflowView>>() {
                    });
            return rows != null ? rows : List.of();
        } catch (Exception ex) {
            throw unavailable(ex);
        }
    }

    /** Tenant-wide count for the governance quota widget. */
    public long countForTenant(String tenantId) {
        try {
            Map<String, Object> body = workflowRestClient.get()
                    .uri("/internal/workflows/count?tenantId={tenantId}", tenantId)
                    .header("X-Internal-Token", internalToken)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {
                    });
            return body != null && body.get("count") instanceof Number n ? n.longValue() : 0L;
        } catch (Exception ex) {
            // Governance summary is a dashboard read, never a gate: report the
            // rest of the picture rather than failing the whole page.
            log.warn("Workflow count unavailable for tenant {}: {}", tenantId, ex.getMessage());
            return 0L;
        }
    }

    /** Provider console usage table: workflow counts per tenant. */
    public Map<String, Long> countsByTenant() {
        try {
            Map<String, Long> counts = workflowRestClient.get()
                    .uri("/internal/workflows/counts")
                    .header("X-Internal-Token", internalToken)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Long>>() {
                    });
            return counts != null ? counts : Map.of();
        } catch (Exception ex) {
            log.warn("Workflow counts unavailable for the provider usage view: {}",
                    ex.getMessage());
            return Map.of();
        }
    }

    /**
     * Provider library: how many delivered copies each catalog workflow has.
     *
     * <p>Degrades to an empty map rather than failing the library page — the
     * count is decoration next to the catalog itself, and a provider who cannot
     * see their own templates because workflow-service is briefly down is worse
     * off than one looking at a stale zero.
     */
    public Map<String, Long> rolloutCountsBySource() {
        try {
            Map<String, Long> counts = workflowRestClient.get()
                    .uri("/internal/workflows/rollout-counts")
                    .header("X-Internal-Token", internalToken)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Long>>() {
                    });
            return counts != null ? counts : Map.of();
        } catch (Exception ex) {
            log.warn("Workflow rollout counts unavailable: {}", ex.getMessage());
            return Map.of();
        }
    }

    /** SCM import: create, with the user's token still passing the plan gate. */
    public WorkflowView create(String tenantId, String actor, String accessToken, Long projectId,
                               String name, String definition) {
        try {
            return workflowRestClient.post()
                    .uri("/internal/projects/{projectId}/workflows?tenantId={tenantId}&actor={actor}",
                            projectId, tenantId, actor)
                    .header("X-Internal-Token", internalToken)
                    .header("X-Access-Token", accessToken)
                    .body(Map.of("name", name, "definition", definition == null ? "" : definition))
                    .retrieve()
                    .body(WorkflowView.class);
        } catch (Exception ex) {
            throw unavailable(ex);
        }
    }

    /**
     * Roll a catalog workflow out into a tenant's project. The caller MUST
     * have already proved two things, because workflow-service does not
     * re-check either: that the caller holds the PROVIDER role, and that
     * {@code projectId} belongs to {@code tenantId}. Getting the second wrong
     * would plant a workflow across a tenant boundary.
     */
    public WorkflowView rollOut(String tenantId, String actor, String accessToken, Long projectId,
                                Long sourceId, String name, String definition, String delivery) {
        try {
            return workflowRestClient.post()
                    .uri("/internal/projects/{projectId}/workflows/rollout"
                                    + "?tenantId={tenantId}&actor={actor}&sourceId={sourceId}"
                                    + "&delivery={delivery}",
                            projectId, tenantId, actor, sourceId, delivery)
                    .header("X-Internal-Token", internalToken)
                    .header("X-Access-Token", accessToken)
                    .body(Map.of("name", name, "definition", definition == null ? "" : definition))
                    .retrieve()
                    .body(WorkflowView.class);
        } catch (RestClientResponseException ex) {
            throw rejected(ex);
        } catch (Exception ex) {
            throw unavailable(ex);
        }
    }

    /**
     * A 4xx from workflow-service is a DECISION the provider needs to read —
     * "this project already has this workflow" — not an outage.
     *
     * <p>Without this branch every failure went through {@link #unavailable},
     * so a refused delivery came back as "workflow-service is unavailable,
     * please retry": wrong, and actively misleading, because retrying could
     * never succeed. RolloutService collects the message per target, so this is
     * the text the provider console shows next to the customer that was
     * skipped. Applied to the rollout path only — {@link #find}'s 404 handling
     * is deliberate and documented on the class, and keeps its own semantics.
     */
    private CoreException rejected(RestClientResponseException ex) {
        Map<?, ?> body;
        try {
            body = ex.getResponseBodyAs(Map.class);
        } catch (Exception ignored) {
            body = null; // Not the uniform {"error","message"} body — use the status.
        }
        String code = field(body, "error", "workflow_rejected");
        String message = field(body, "message", "workflow-service rejected this delivery");
        if (ex.getStatusCode().is5xxServerError()) {
            log.warn("workflow-service failed the call: {} {}", ex.getStatusCode(), message);
            return CoreException.badGateway(code, message);
        }
        log.info("workflow-service refused the call: {} ({})", message, code);
        return switch (ex.getStatusCode().value()) {
            case 403 -> CoreException.forbidden(code, message);
            case 404 -> CoreException.notFound(code, message);
            case 409 -> CoreException.conflict(code, message);
            default -> CoreException.badRequest(code, message);
        };
    }

    private static String field(Map<?, ?> body, String key, String fallback) {
        Object value = body == null ? null : body.get(key);
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    /** One customer's copy of a catalog workflow. */
    public record Delivery(Long id, String tenantId, Long projectId, String name,
                           boolean enabled) {
    }

    /** Who holds this catalog workflow. Empty on failure — see AgentClient. */
    public List<Delivery> deliveries(Long sourceId) {
        try {
            Delivery[] rows = workflowRestClient.get()
                    .uri("/internal/workflows/deliveries?sourceId={sourceId}", sourceId)
                    .header("X-Internal-Token", internalToken)
                    .retrieve()
                    .body(Delivery[].class);
            return rows == null ? List.of() : List.of(rows);
        } catch (Exception ex) {
            log.warn("Could not list workflow deliveries for {}: {}", sourceId, ex.getMessage());
            return List.of();
        }
    }

    /** Revoke a rolled-out workflow. */
    public void revoke(String tenantId, String accessToken, Long workflowId) {
        try {
            workflowRestClient.delete()
                    .uri("/internal/workflows/{id}?tenantId={tenantId}", workflowId, tenantId)
                    .header("X-Internal-Token", internalToken)
                    .header("X-Access-Token", accessToken)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception ex) {
            throw unavailable(ex);
        }
    }

    /** SCM import: update an existing workflow in place. */
    public WorkflowView update(String tenantId, String accessToken, Long workflowId,
                               String name, String definition) {
        try {
            return workflowRestClient.put()
                    .uri("/internal/workflows/{id}?tenantId={tenantId}", workflowId, tenantId)
                    .header("X-Internal-Token", internalToken)
                    .header("X-Access-Token", accessToken)
                    .body(Map.of("name", name, "definition", definition == null ? "" : definition))
                    .retrieve()
                    .body(WorkflowView.class);
        } catch (Exception ex) {
            throw unavailable(ex);
        }
    }

    private CoreException unavailable(Exception ex) {
        log.warn("workflow-service call failed: {}", ex.getMessage());
        return CoreException.serviceUnavailable("workflow_unavailable",
                "Workflow service is temporarily unavailable — please retry");
    }

    /** Internal signal only: a real 404 from workflow-service. */
    private static class NotFound extends RuntimeException {
        NotFound() {
            super(null, null, false, false);
        }
    }
}
