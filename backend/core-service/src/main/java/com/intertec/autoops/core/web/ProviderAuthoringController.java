package com.intertec.autoops.core.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.intertec.autoops.core.client.WorkflowRuntimeClient;
import com.intertec.autoops.core.exception.CoreException;
import com.intertec.autoops.core.service.NativeWorkflowService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the provider console is allowed to build, and what will actually run.
 *
 * <p>The designer needs a palette — which node types exist, which fields each
 * takes, which are mandatory, what values are legal. The tempting way to supply
 * that is a constant in the React bundle. This codebase has paid for that
 * decision twice already, both times in the same file: {@code
 * NativeWorkflowService.GRAPH_NODE_TYPES} is a hand-kept copy of the runtime's
 * {@code NodeType}, it went stale when {@code job} was added, and it went stale
 * again when {@code platform} was — so the two workflows that read AutoOps's
 * own record were classified as step lists and failed with "No executor for
 * step type 'start'". A third copy, in JavaScript, would go stale the same way
 * and be even harder to notice, because a missing palette entry looks like a
 * design decision rather than a bug.
 *
 * <p>So the palette comes from the runtime's own models, and this endpoint adds
 * the one thing the runtime cannot know: whether THIS service will dispatch a
 * graph containing each type. Both halves matter. A node type the runtime
 * executes and core does not recognise produces a workflow that saves cleanly
 * and fails on its first run, which is precisely the failure above — so it is
 * reported here, on the screen where somebody is choosing, rather than only in
 * a log nobody reads until an incident.
 *
 * <h2>Unreachable is an error, not a fallback</h2>
 * When the runtime does not answer, this returns 503. It would be easy to serve
 * a built-in palette instead and keep the designer working — and that is the
 * worst option available, because the author would build against a stale
 * contract with no indication anything was wrong. Refusing to answer is the
 * honest failure: it says the designer cannot be trusted right now, which is
 * true.
 */
@RestController
@RequestMapping("/api/provider/authoring")
public class ProviderAuthoringController {

    private static final String PROVIDER_ROLE = "PROVIDER";

    private final WorkflowRuntimeClient runtime;
    private final NativeWorkflowService nativeWorkflows;

    public ProviderAuthoringController(WorkflowRuntimeClient runtime,
                                       NativeWorkflowService nativeWorkflows) {
        this.runtime = runtime;
        this.nativeWorkflows = nativeWorkflows;
    }

    /**
     * The authoring contract, plus what this service can dispatch.
     *
     * <p>Provider-only. Authoring is a provider activity by design — customers
     * build jobs and scripts and receive sealed copies of what the provider
     * designs — so there is no tenant-scoped variant of this to fall back to.
     */
    @GetMapping("/schema")
    public Map<String, Object> schema(@AuthenticationPrincipal Jwt jwt) {
        requireProvider(jwt);

        JsonNode schema = runtime.authoringSchema();
        if (schema == null || schema.isEmpty()) {
            throw CoreException.serviceUnavailable("runtime_unavailable",
                    "The agent runtime did not answer, so the designer cannot be shown "
                            + "which node types and fields this build supports. Building "
                            + "against a remembered palette is how a workflow ends up using "
                            + "a field the runtime ignores.");
        }

        List<String> runtimeTypes = new ArrayList<>();
        for (JsonNode node : schema.path("workflow").path("node_types")) {
            runtimeTypes.add(node.path("type").asText());
        }

        // Empty is the healthy answer, and it is reported either way: a client
        // that only saw the key when something was wrong would have to treat
        // its absence as good news, which is the same shape of mistake as a
        // counter that reads zero when healthy and zero when dead.
        List<String> unsupported = nativeWorkflows.unsupportedNodeTypes(runtimeTypes);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("workflow", schema.path("workflow"));
        out.put("agent", schema.path("agent"));
        out.put("undispatchable_node_types", unsupported);
        return out;
    }

    /**
     * Validates a draft graph without running it, and reports its input form.
     *
     * <p>The same {@code inspect} the execution path uses, so the designer's
     * verdict and the runtime's are the same verdict. A console with its own
     * validator would be a second opinion, and the two would disagree on
     * exactly the definitions that matter — the odd ones.
     *
     * <p>Answers 200 with {@code valid: false} for a bad graph rather than 400.
     * A draft being incomplete is the normal state of something being authored;
     * an error status would make every keystroke look like a failure.
     */
    @PostMapping("/validate")
    public Map<String, Object> validate(@RequestBody JsonNode definition,
                                        @AuthenticationPrincipal Jwt jwt) {
        requireProvider(jwt);

        JsonNode inspected = runtime.inspect(definition == null ? "{}" : definition.toString());
        Map<String, Object> out = new LinkedHashMap<>();
        if (inspected == null) {
            // Distinguished from "invalid" on purpose. A designer that showed
            // an unreachable runtime as "your workflow is wrong" would send
            // somebody looking for a mistake they did not make.
            out.put("valid", false);
            out.put("unavailable", true);
            out.put("error", "The agent runtime did not answer, so this draft could not be "
                    + "checked. It has not been judged either way.");
            return out;
        }
        out.put("valid", inspected.path("valid").asBoolean(false));
        out.put("unavailable", false);
        out.put("error", inspected.path("error").asText(null));
        out.put("inputs", inspected.path("inputs"));
        // References that will not resolve — {{#node.field#}} pointing at a node
        // that does not exist or a field it never produces. A valid graph can
        // still be full of these, and they fail at RUN time with a message
        // nobody reads until an incident, so the designer has to show them
        // beside the validity verdict rather than instead of it.
        out.put("problems", inspected.path("problems"));
        return out;
    }

    private void requireProvider(Jwt jwt) {
        if (jwt == null || !PROVIDER_ROLE.equals(jwt.getClaimAsString("role"))) {
            throw CoreException.forbidden("provider_only",
                    "This endpoint is for platform operators");
        }
    }
}
