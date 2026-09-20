package com.intertec.autoops.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intertec.autoops.core.exception.CoreException;
import com.intertec.autoops.core.repo.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Whether a workflow this workspace holds can actually run — asked BEFORE
 * anyone presses Run.
 *
 * <h2>Why this exists</h2>
 *
 * A rolled-out workflow arrives complete and looks identical to one that is
 * ready. The provider designed it against their own workspace; the customer's
 * has different AI connections, different jobs, different cloud accounts.
 * Until this existed the only way to discover the difference was to press Run
 * and read a red error — which is a bad first impression of an automation
 * someone has just been given, and worse, it looks like the automation is
 * broken rather than unconfigured.
 *
 * <p>So the checks run on demand and report BLOCKERS, each one naming the
 * screen that fixes it. Nothing here executes anything or spends a token.
 *
 * <h2>What it deliberately does not do</h2>
 *
 * It does not guess. A check that cannot be made confidently is not reported
 * as a blocker — a false "this will not work" stops a customer running an
 * automation that would have been fine, and they have no way to overrule it.
 * Everything below is a fact that can be established from this workspace's own
 * rows.
 */
@Service
public class WorkflowReadiness {

    private static final Logger log = LoggerFactory.getLogger(WorkflowReadiness.class);

    private final ModelProviderService modelProviders;
    private final JobRepository jobRepository;
    private final ObjectMapper objectMapper;

    public WorkflowReadiness(ModelProviderService modelProviders, JobRepository jobRepository,
                             ObjectMapper objectMapper) {
        this.modelProviders = modelProviders;
        this.jobRepository = jobRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * One thing standing between this workspace and running this workflow.
     *
     * @param kind   a stable code the console can branch on
     * @param title  one line, for a badge or a list row
     * @param detail what is actually wrong, in the customer's terms
     * @param action the label for the button that fixes it, or null when there
     *               is nothing the customer can press
     * @param href   where that button goes, or null
     */
    public record Blocker(String kind, String title, String detail, String action, String href) {
    }

    /** {@code ready} is simply "no blockers" — never a separate opinion. */
    public record Readiness(boolean ready, List<Blocker> blockers) {
    }

    @Transactional(readOnly = true)
    public Readiness check(String tenantId, String definition) {
        List<Blocker> blockers = new ArrayList<>();
        JsonNode nodes;
        try {
            nodes = objectMapper.readTree(definition == null ? "{}" : definition).path("nodes");
        } catch (Exception ex) {
            // An unreadable definition is a provider problem, not a customer
            // setup problem, and there is no screen a customer can open to fix
            // it. Say so rather than listing setup steps that would not help.
            return new Readiness(false, List.of(new Blocker(
                    "definition_unreadable",
                    "This automation could not be read",
                    "Its definition is not valid. Ask your provider to republish it.",
                    null, null)));
        }
        if (!nodes.isArray()) {
            return new Readiness(true, List.of());
        }

        checkModel(tenantId, nodes, blockers);
        checkAutomations(tenantId, nodes, blockers);
        return new Readiness(blockers.isEmpty(), blockers);
    }

    /**
     * An llm node that names no model needs THIS workspace to have chosen one.
     *
     * <p>A node that names its own model is checked too, but differently: the
     * question there is whether this workspace holds a connection that offers
     * it. A provider-authored workflow should not name one at all — see
     * {@code NativeWorkflowService} — but a customer's own workflow legitimately
     * can, and a typo in it is exactly the kind of thing worth catching here.
     */
    private void checkModel(String tenantId, JsonNode nodes, List<Blocker> blockers) {
        String declared = null;
        boolean needsModel = false;
        for (JsonNode node : nodes) {
            if ("llm".equals(node.path("type").asText(""))) {
                needsModel = true;
                if (node.hasNonNull("model") && !node.path("model").asText("").isBlank()) {
                    declared = node.path("model").asText();
                }
                break;
            }
        }
        if (!needsModel) {
            return;
        }

        String wanted = declared;
        if (wanted == null || "@tenant-default".equalsIgnoreCase(wanted)) {
            try {
                wanted = modelProviders.defaultChatModel(tenantId);
            } catch (CoreException ex) {
                blockers.add(new Blocker(
                        "model_not_set",
                        "Choose an AI model",
                        ex.getMessage(),
                        "Open AI Providers", "/app/settings/ai-providers"));
                return;
            }
        }
        try {
            modelProviders.resolveForModel(tenantId, wanted);
        } catch (CoreException ex) {
            blockers.add(new Blocker(
                    "model_unavailable",
                    "This workspace cannot reach \"" + wanted + "\"",
                    ex.getMessage(),
                    "Open AI Providers", "/app/settings/ai-providers"));
        }
    }

    /**
     * A {@code job} node points at a row in THIS workspace.
     *
     * <p>A rolled-out workflow carries the provider's ids, and those ids mean
     * nothing here. Catching it now turns "the automation failed halfway
     * through" into "this automation needs a job you do not have", which is a
     * different conversation.
     */
    private void checkAutomations(String tenantId, JsonNode nodes, List<Blocker> blockers) {
        for (JsonNode node : nodes) {
            if (!"job".equals(node.path("type").asText(""))) {
                continue;
            }
            // Workflow targets are resolved by workflow-service, which this
            // class deliberately does not call: readiness must not fail because
            // a peer is briefly down, and a false blocker is worse than none.
            if (!"JOB".equalsIgnoreCase(node.path("target").asText("JOB"))) {
                continue;
            }
            long targetId = node.path("targetId").asLong(0);
            if (targetId <= 0 || jobRepository.findByIdAndTenantId(targetId, tenantId).isEmpty()) {
                String label = node.path("title").asText(node.path("id").asText("a step"));
                blockers.add(new Blocker(
                        "job_missing",
                        "\"" + label + "\" has no job to run",
                        "This automation runs job #" + targetId + ", which is not in this "
                                + "workspace. Ask your provider to roll it out, or point the "
                                + "step at one of your own.",
                        "Open Jobs", "/app/jobs"));
            }
        }
    }
}
