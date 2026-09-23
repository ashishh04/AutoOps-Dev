package com.intertec.autoops.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intertec.autoops.core.client.WorkflowRuntimeClient;
import com.intertec.autoops.core.config.CoreProperties;
import com.intertec.autoops.core.exception.CoreException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The one place that decides a workflow is a NATIVE GRAPH, and runs it.
 *
 * <p>Replaced a bridge that turned a slug into a third party's app key and
 * handed the whole run to that vendor. This recognises a definition carrying
 * its own graph and sends it to agent-runtime with the tenant's own model
 * credentials attached, decrypted here and used for exactly one call.
 *
 * <h2>What makes a definition native</h2>
 *
 * A {@code nodes[]} array whose entries carry a {@code type} the runtime knows,
 * plus at least one {@code start}. That is deliberately a property of the
 * definition itself rather than a flag alongside it: a flag can disagree with
 * the thing it describes, and the failure mode is a graph executed by the step
 * walker, which finds no {@code steps[]}, does nothing, and reports success.
 *
 * <p>Note the order this is checked in {@code ExecutionEngine}: before
 * {@code parseSteps}, for exactly that reason.
 */
@Service
public class NativeWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(NativeWorkflowService.class);

    /**
     * Node types that only a native graph uses.
     *
     * <p><b>This list must match {@code NodeType} in the runtime's
     * {@code workflows/spec.py}.</b> The coupling is real and it has now bitten
     * TWICE. First a {@code job} node was added there and not here. Then a
     * {@code platform} node was, and both {@code RD-220} and {@code RD-221}
     * — the two workflows that read AutoOps's own record, and the evidence
     * source behind the escalation agent — stopped being recognised as native,
     * fell through to the step walker and failed with "No executor for step
     * type 'start'".
     *
     * <p>Twice is a pattern, and the pattern is that a hand-kept copy of
     * somebody else's enum goes stale silently. {@link #isNative} refuses a
     * graph it does not fully understand rather than running the parts it
     * recognises, so the failure is at least loud at run time — but by then a
     * customer has watched a workflow fail. The runtime now publishes its own
     * node types at {@code /v1/authoring/schema}, and
     * {@link #unsupportedNodeTypes} compares this list against them so the
     * divergence surfaces on the designer screen rather than in a run.
     *
     * <p>This stays a constant rather than becoming a fetch because
     * {@link #isNative} runs on the dispatch path: a workflow must still be
     * dispatchable correctly when the runtime is briefly unreachable, and a
     * graph that got classified as a step list because of a network blip would
     * do nothing and report success.
     */
    private static final java.util.Set<String> GRAPH_NODE_TYPES =
            java.util.Set.of("start", "llm", "platform", "job", "http", "template",
                    "condition", "end");

    /**
     * Node types the runtime can execute and this service would not dispatch.
     *
     * <p>Empty is the healthy answer. A non-empty one means a workflow built
     * from those types will be mis-classified here, so the designer must not
     * offer them — which is why this is reported to the authoring screen rather
     * than only logged.
     *
     * @param runtimeTypes what the runtime published; an empty or absent list
     *                     yields no complaint, because "the runtime did not
     *                     answer" and "the runtime supports nothing" must not
     *                     look the same
     */
    public java.util.List<String> unsupportedNodeTypes(java.util.List<String> runtimeTypes) {
        if (runtimeTypes == null || runtimeTypes.isEmpty()) {
            return java.util.List.of();
        }
        java.util.List<String> unsupported = runtimeTypes.stream()
                .filter(type -> !GRAPH_NODE_TYPES.contains(type))
                .sorted()
                .toList();
        if (!unsupported.isEmpty()) {
            log.error("The agent runtime can execute node type(s) {} that this service does not "
                            + "recognise as native. A workflow containing one would be dispatched "
                            + "to the step walker and fail. Add them to GRAPH_NODE_TYPES.",
                    unsupported);
        }
        return unsupported;
    }

    private final WorkflowRuntimeClient runtime;
    private final ModelProviderService modelProviders;
    private final CoreProperties properties;
    private final ObjectMapper objectMapper;

    public NativeWorkflowService(WorkflowRuntimeClient runtime,
                                 ModelProviderService modelProviders,
                                 CoreProperties properties,
                                 ObjectMapper objectMapper) {
        this.runtime = runtime;
        this.modelProviders = modelProviders;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * True when this definition is a graph for the native runtime.
     *
     * <p>Requires a {@code start} node specifically, not merely a recognised
     * type. A JOB's {@code steps[]} and a legacy canvas's {@code nodes[]} both
     * carry {@code type} fields too — {@code command}, {@code powershell},
     * {@code ssh} — and none of them is a graph. The start node is the one
     * marker no step list has.
     */
    public boolean isNative(String definition) {
        if (definition == null || definition.isBlank() || !definition.contains("\"start\"")) {
            return false;
        }
        try {
            JsonNode nodes = objectMapper.readTree(definition).path("nodes");
            if (!nodes.isArray() || nodes.isEmpty()) {
                return false;
            }
            boolean hasStart = false;
            for (JsonNode node : nodes) {
                String type = node.path("type").asText("");
                if (!GRAPH_NODE_TYPES.contains(type)) {
                    return false;
                }
                hasStart |= "start".equals(type);
            }
            return hasStart;
        } catch (Exception ex) {
            return false;
        }
    }

    /** The declared input form, read from the definition rather than a vendor. */
    public List<WorkflowInputField> formFor(String definition) {
        JsonNode inspected = runtime.inspect(definition);
        List<WorkflowInputField> fields = new ArrayList<>();
        if (inspected == null || !inspected.path("valid").asBoolean(false)) {
            return fields;
        }
        for (JsonNode field : inspected.path("inputs")) {
            List<String> options = new ArrayList<>();
            field.path("options").forEach(option -> options.add(option.asText()));
            fields.add(new WorkflowInputField(
                    field.path("variable").asText(),
                    field.path("label").asText(field.path("variable").asText()),
                    field.path("type").asText("text"),
                    field.path("required").asBoolean(false),
                    field.path("default").isNull() ? null : field.path("default").asText(null),
                    options,
                    field.hasNonNull("maxLength") ? field.path("maxLength").asInt() : null));
        }
        return fields;
    }

    /**
     * Runs the graph.
     *
     * <p>The model is resolved ONLY when the graph actually has an {@code llm}
     * node. A graph of http/template/condition nodes needs no vendor, and
     * demanding a connection it never uses would block a perfectly good
     * workflow behind a setup step with no purpose.
     */
    public WorkflowRuntimeClient.RunOutcome run(Long runId, String tenantId, Long projectId,
                                                String definition,
                                                Map<String, Object> inputs,
                                                WorkflowRuntimeClient.Progress progress) {
        return runtime.run(runId, tenantId, projectId, definition, inputs,
                needsModel(definition) ? resolveModel(tenantId, definition) : null, progress);
    }

    private boolean needsModel(String definition) {
        try {
            for (JsonNode node : objectMapper.readTree(definition).path("nodes")) {
                if ("llm".equals(node.path("type").asText(""))) {
                    return true;
                }
            }
        } catch (Exception ex) {
            return false;
        }
        return false;
    }

    /**
     * The tenant's credentials for the model this workflow asks for.
     *
     * <p>A definition may name a model on an {@code llm} node; otherwise the
     * deployment default applies. Resolution and decryption happen HERE, in
     * core-service, and the runtime is handed a decrypted binding for exactly
     * one call — the same arrangement agent-service already uses, and the
     * reason the runtime holds no credential store of its own.
     */
    /**
     * The sentinel a PORTABLE workflow names instead of a model.
     *
     * <p>A provider-authored workflow is rolled out to many workspaces, and a
     * literal model id only resolves on the ones that happen to hold a
     * connection offering it. This says "whatever this workspace chose",
     * explicitly — omitting {@code model} means the same thing, but a designer
     * that can show the intent is better than one where the absence of a field
     * is the intent.
     */
    private static final String TENANT_DEFAULT = "@tenant-default";

    private WorkflowRuntimeClient.ModelBinding resolveModel(String tenantId, String definition) {
        String model = declaredModel(definition);

        // Resolution order, and it matters for a SaaS: the step's own choice,
        // then THIS WORKSPACE'S default, then the deployment's. The tenant
        // default sits in the middle deliberately — before this it did not
        // exist, so a workflow naming nothing fell straight through to one
        // environment variable shared by every customer on the box.
        if (model == null || model.isBlank() || TENANT_DEFAULT.equalsIgnoreCase(model)) {
            try {
                model = modelProviders.defaultChatModel(tenantId);
            } catch (CoreException ex) {
                // A platform-wide fallback is a deployment convenience, not a
                // per-customer answer, so it only applies when the workspace
                // has said nothing at all. If it is unset too, the workspace's
                // own error is the useful one — it names the screen to open.
                String platform = properties.getRuntime().getDefaultModel();
                if (platform == null || platform.isBlank()) {
                    throw ex;
                }
                log.info("Tenant {} has no default model; falling back to the platform default",
                        tenantId);
                model = platform;
            }
        }
        ModelProviderService.ResolvedCredentials resolved =
                modelProviders.resolveForModel(tenantId, model);
        log.info("Workflow model {} resolved to {} connection #{}", model,
                resolved.kind(), resolved.providerId());
        return new WorkflowRuntimeClient.ModelBinding(
                resolved.model(), resolved.kind().name(), resolved.values());
    }

    /** The first model named by an llm node, or null when none names one. */
    private String declaredModel(String definition) {
        try {
            for (JsonNode node : objectMapper.readTree(definition).path("nodes")) {
                if ("llm".equals(node.path("type").asText("")) && node.hasNonNull("model")) {
                    return node.path("model").asText(null);
                }
            }
        } catch (Exception ex) {
            return null;
        }
        return null;
    }
}
