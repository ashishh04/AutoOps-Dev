package com.intertec.autoops.core.web;

import com.intertec.autoops.core.service.ModelProviderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The one place a decrypted model credential leaves this service.
 *
 * <p>It has a file of its own so that it is easy to find and easy to audit.
 * agent-service can talk to eleven vendors and holds no encryption key;
 * core-service holds the key and cannot talk to any of them. That split is
 * the reason this endpoint exists, and keeping it to a single small class
 * keeps the crossing visible.
 *
 * <p>Protections, all of which matter:
 * <ul>
 *   <li>{@code /internal} — behind {@code InternalTokenFilter}, and the
 *       gateway does not route the prefix, so no browser can reach it;</li>
 *   <li>tenant-scoped by an explicit parameter, like every internal read;</li>
 *   <li>resolved per MODEL rather than "give me provider 7's key", so a caller
 *       cannot enumerate a workspace's credentials;</li>
 *   <li>nothing is logged but the vendor and the model — never a value.</li>
 * </ul>
 */
@RestController
public class InternalModelCredentialController {

    private static final Logger log = LoggerFactory.getLogger(InternalModelCredentialController.class);

    private final ModelProviderService modelProviderService;

    public InternalModelCredentialController(ModelProviderService modelProviderService) {
        this.modelProviderService = modelProviderService;
    }

    /**
     * The model an agent names, or the workspace's own default when it names
     * none.
     *
     * <p>Agents had no middle step here and workflows did. A blank model was a
     * hard {@code model_required} error, so every agent had to carry a literal
     * id — and a Python agent's manifest carries
     * {@code anthropic.claude-sonnet-5} as a documented PLACEHOLDER, which
     * rollout then delivered as the customer's real choice. A workspace with no
     * Anthropic connection got {@code model_not_available} on an agent it never
     * chose a vendor for.
     *
     * <p>Resolution order now matches {@code NativeWorkflowService}: the
     * agent's own choice, then THIS WORKSPACE'S default. A provider who leaves
     * the model unset is saying "whatever this customer runs on", which is the
     * only answer that is correct in every workspace an agent is delivered to.
     */
    private static final String TENANT_DEFAULT = "@tenant-default";

    @GetMapping("/internal/model-credentials")
    public Map<String, Object> credentials(@RequestParam String tenantId,
                                           @RequestParam(required = false) String model) {
        String wanted = model;
        if (wanted == null || wanted.isBlank() || TENANT_DEFAULT.equalsIgnoreCase(wanted)) {
            wanted = modelProviderService.defaultChatModel(tenantId);
            log.info("Tenant {} named no model; using its own default {}", tenantId, wanted);
        }
        ModelProviderService.ResolvedCredentials resolved =
                modelProviderService.resolveForModel(tenantId, wanted);

        log.info("Tenant {} resolved model {} to {} connection #{}", tenantId, wanted,
                resolved.kind(), resolved.providerId());

        return Map.of(
                "kind", resolved.kind().name(),
                "providerId", resolved.providerId(),
                "providerName", resolved.providerName(),
                "model", resolved.model(),
                "values", resolved.values());
    }
}
