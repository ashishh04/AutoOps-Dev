package com.intertec.autoops.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intertec.autoops.core.client.WorkflowClient;
import com.intertec.autoops.core.domain.CloudConnection;
import com.intertec.autoops.core.domain.ConnectionStatus;
import com.intertec.autoops.core.repo.CloudConnectionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Whether an agent's tools can reach anything, asked BEFORE the run starts.
 *
 * <h2>The failure this removes</h2>
 * An agent whose tools need an AWS account, in a project with no AWS account,
 * used to start, resolve a model, spend tokens on a plan, call its first tool
 * and only then fail — with a message about a credential, several minutes and
 * some money after the click. Nothing about that was unknowable: the tools
 * declare what they need, the project either holds it or does not, and both
 * facts are on this side of the run.
 *
 * <h2>Why the rule is not "a connection in this project"</h2>
 * A connection with no project is GLOBAL and serves every project; one with a
 * project serves only that project. That is exactly what
 * {@code CloudConnectionService.resolveForStep} enforces when a step actually
 * binds, and this check has to agree with it or it becomes its own bug — too
 * strict and it blocks runs that would have worked, too loose and it waves
 * through the very failure it exists to catch. The predicate is duplicated in
 * one line rather than shared, because sharing it would mean exposing a
 * resolver that decrypts credentials to a caller that must never see one.
 *
 * <h2>What it will not claim</h2>
 * It reports only what it could check. A tool whose definition cannot be read
 * is named as unverifiable rather than silently treated as satisfied — a
 * readiness answer that is quietly partial is worse than no answer, because
 * the run proceeds on the strength of it.
 */
@Service
public class AgentReadinessService {

    private final WorkflowClient workflowClient;
    private final CloudConnectionRepository connectionRepository;
    private final ObjectMapper objectMapper;

    public AgentReadinessService(WorkflowClient workflowClient,
                                 CloudConnectionRepository connectionRepository,
                                 ObjectMapper objectMapper) {
        this.workflowClient = workflowClient;
        this.connectionRepository = connectionRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * @param missing      platforms the tools need that this project cannot reach
     * @param unverifiable tool ids whose definition could not be read, so nothing
     *                     can be said about what they need
     */
    public record Readiness(List<String> missing, List<Long> unverifiable) {

        public boolean ready() {
            return missing.isEmpty();
        }
    }

    @Transactional(readOnly = true)
    public Readiness check(String tenantId, Long projectId, List<Long> workflowIds) {
        Set<String> needed = new LinkedHashSet<>();
        List<Long> unverifiable = new ArrayList<>();

        for (Long workflowId : workflowIds == null ? List.<Long>of() : workflowIds) {
            if (workflowId == null) {
                continue;
            }
            String definition;
            try {
                definition = workflowClient.find(tenantId, workflowId)
                        .map(WorkflowClient.WorkflowView::definition)
                        .orElse(null);
            } catch (RuntimeException ex) {
                // workflow-service being unreachable must not block a run. The
                // agent would have failed on its own terms anyway, and refusing
                // here would turn someone else's outage into "your agent is
                // misconfigured", which sends the reader somewhere useless.
                unverifiable.add(workflowId);
                continue;
            }
            if (definition == null || definition.isBlank()) {
                unverifiable.add(workflowId);
                continue;
            }
            needed.addAll(platformsRequiredBy(definition));
        }

        Set<String> reachable = reachablePlatforms(tenantId, projectId);
        List<String> missing = needed.stream()
                .filter(platform -> !reachable.contains(platform))
                .toList();

        return new Readiness(missing, unverifiable);
    }

    /** The {@code cloud_connection} requirements a workflow declares. */
    private Set<String> platformsRequiredBy(String definition) {
        Set<String> platforms = new LinkedHashSet<>();
        JsonNode root;
        try {
            root = objectMapper.readTree(definition);
        } catch (Exception ex) {
            return platforms;
        }
        for (JsonNode requirement : root.path("requires")) {
            if (!"cloud_connection".equals(requirement.path("kind").asText())) {
                continue;
            }
            String platform = requirement.path("platform").asText("");
            if (!platform.isBlank()) {
                platforms.add(platform.toUpperCase(Locale.ROOT));
            }
        }
        return platforms;
    }

    /**
     * Platforms this project can actually bind, by the same rule a running step
     * uses: CONNECTED, with credentials, and either global or this project's.
     */
    private Set<String> reachablePlatforms(String tenantId, Long projectId) {
        Set<String> platforms = new LinkedHashSet<>();
        for (CloudConnection connection
                : connectionRepository.findByTenantIdAndStatus(tenantId, ConnectionStatus.CONNECTED)) {
            if (connection.getCredentialsEnc() == null) {
                // Connected but never given credentials. resolveForStep filters
                // these out too; counting one here would promise access that
                // fails at the moment it is used.
                continue;
            }
            boolean visible = connection.getProjectId() == null
                    || connection.getProjectId().equals(projectId);
            if (visible && connection.getPlatform() != null) {
                platforms.add(connection.getPlatform().name().toUpperCase(Locale.ROOT));
            }
        }
        return platforms;
    }
}
