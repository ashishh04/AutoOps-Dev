package com.intertec.autoops.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intertec.autoops.core.client.AgentClient;
import com.intertec.autoops.core.client.EntitlementClient;
import com.intertec.autoops.core.client.WorkflowClient;
import com.intertec.autoops.core.domain.LibraryItem;
import com.intertec.autoops.core.exception.CoreException;
import com.intertec.autoops.core.repo.LibraryItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Template catalog. The platform rows (tenant NULL) are authored by PROVIDER
 * accounts; a tenant "imports" one by cloning it into its own rows.
 *
 * <p>Premium templates are locked behind PREMIUM_TEMPLATES, a plan feature
 * decided by subscription-service. Writing and adapting SCRIPTS needs only a
 * live subscription: those are the customer's own work, not something the
 * catalog sells them.
 */
@Service
public class LibraryService {

    private static final Logger log = LoggerFactory.getLogger(LibraryService.class);

    private final LibraryItemRepository libraryRepository;
    private final SubscriptionGate gate;
    private final EntitlementClient entitlementClient;
    private final ObjectMapper objectMapper;
    private final WorkflowClient workflowClient;
    private final AgentClient agentClient;

    public LibraryService(LibraryItemRepository libraryRepository, SubscriptionGate gate,
                          EntitlementClient entitlementClient, ObjectMapper objectMapper,
                          WorkflowClient workflowClient, AgentClient agentClient) {
        this.libraryRepository = libraryRepository;
        this.gate = gate;
        this.entitlementClient = entitlementClient;
        this.objectMapper = objectMapper;
        this.workflowClient = workflowClient;
        this.agentClient = agentClient;
    }

    /**
     * Live delivered-copy count per catalog item, keyed by catalog id.
     *
     * <p>Read from the services that hold the deliveries rather than kept as a
     * counter on the catalog row. A counter is wrong in both directions: it
     * would miss a delivery made through the /internal endpoint by anything
     * other than RolloutService, and it could never come back down when a
     * customer's copy is revoked. Two rows in the catalog claiming "3 rollouts"
     * when one customer has since been removed is worse than no number.
     *
     * <p>PROVIDER-only data — it says how many customers hold each template —
     * so the caller decides whether to ask for it. Both underlying reads
     * degrade to empty on failure, so a service being down costs the number,
     * never the catalog.
     */
    public java.util.Map<String, Long> rolloutCounts() {
        java.util.Map<String, Long> merged =
                new java.util.HashMap<>(workflowClient.rolloutCountsBySource());
        // Merged, not overwritten: catalog ids are unique across types, but a
        // put() here would silently drop every agent count if that ever changed.
        agentClient.rolloutCountsBySource()
                .forEach((sourceId, count) -> merged.merge(sourceId, count, Long::sum));
        return merged;
    }

    /** Catalog + the tenant's own copies; locked computed from the plan. */
    public record LibraryView(LibraryItem item, boolean managed, boolean owned, boolean locked) {
    }

    @Transactional(readOnly = true)
    public List<LibraryView> list(String tenantId, String accessToken) {
        boolean premiumEntitled = entitlementClient
                .checkFeature(accessToken, "PREMIUM_TEMPLATES").entitled();
        List<LibraryView> out = new ArrayList<>();
        for (LibraryItem item : libraryRepository.findByTenantIdIsNullOrderByCreatedAtDesc()) {
            out.add(new LibraryView(item, true, false,
                    item.isPremium() && !premiumEntitled));
        }
        for (LibraryItem item : libraryRepository.findByTenantIdOrderByCreatedAtDesc(tenantId)) {
            out.add(new LibraryView(item, false, true, false));
        }
        return out;
    }

    /** Import a catalog template into the tenant's workspace. */
    @Transactional
    public LibraryItem clone(String tenantId, String actor, String accessToken, Long catalogId) {
        LibraryItem source = libraryRepository.findByIdAndTenantIdIsNull(catalogId)
                .orElseThrow(() -> CoreException.notFound("template_not_found",
                        "No such catalog template"));
        // Importing a WORKFLOW or AGENT template would hand the tenant an
        // editable copy of the provider's design — the exact thing the
        // provider-authored model exists to prevent. Those are DELIVERED by a
        // rollout instead (RolloutService). Scripts remain freely importable:
        // customers are meant to own and adapt those.
        if (source.getType() != LibraryItem.Type.SCRIPT) {
            throw CoreException.forbidden("rollout_only",
                    "Workflows and agents are rolled out to your workspace by your provider, "
                            + "not imported. Ask them to enable this one for you.");
        }
        if (source.isPremium()) {
            gate.requireFeature(accessToken, "PREMIUM_TEMPLATES", "premium templates");
        } else {
            gate.requireActive(accessToken);
        }
        if (libraryRepository.existsByTenantIdAndTitleIgnoreCase(tenantId, source.getTitle())) {
            throw CoreException.conflict("template_owned",
                    "This template is already in your workspace");
        }
        LibraryItem copy = new LibraryItem();
        copy.setTenantId(tenantId);
        copy.setTitle(source.getTitle());
        copy.setDescription(source.getDescription());
        copy.setType(source.getType());
        copy.setCategory(source.getCategory());
        copy.setPremium(source.isPremium());
        copy.setDefinition(source.getDefinition());
        copy.setSourceId(source.getId());
        copy.setCreatedBy(actor);
        LibraryItem saved = libraryRepository.save(copy);
        source.setInstalls(source.getInstalls() + 1);
        libraryRepository.save(source);
        log.info("Tenant {} imported template {} ('{}')", tenantId, catalogId,
                source.getTitle());
        return saved;
    }

    /**
     * Author a script of your own. Needs a LIVE SUBSCRIPTION, nothing more.
     *
     * <p>Formerly behind PRIVATE_TEMPLATES. That gate could not hold its line:
     * every plan may import a catalog script and (see {@link #update}) adapt
     * it, so a customer could reach an entirely rewritten script anyway by
     * importing a free one first. Writing scripts is what a customer's side of
     * this platform IS — the console's own capability matrix says so — and a
     * feature flag that is one import away from being bypassed only produced a
     * confusing 403 on the honest path.
     *
     * <p>SCRIPTS ONLY. A customer builds jobs and scripts; workflows and agents
     * are designed by the provider and delivered — the same rule the console's
     * {@code authorAutomation} capability states and {@link #clone} enforces on
     * the way in. Without this check the rule held only in the browser, and a
     * direct POST could put a tenant-owned workflow in the catalog.
     */
    @Transactional
    public LibraryItem createOwn(String tenantId, String actor, String accessToken,
                                 String title, String description, String typeCode,
                                 String category, String definition) {
        gate.requireActive(accessToken);
        requireScript(parseType(typeCode));
        return save(tenantId, actor, title, description, typeCode, category, definition, false);
    }

    /**
     * Edit a script this workspace owns — one it authored, or the copy it
     * imported from the catalog.
     *
     * <p>Editing an imported copy is the point: importing exists so a customer
     * can ADAPT a script. The copy diverges from the provider's original and
     * the original is never touched — {@code require} reads by
     * (id, tenantId) together, so a platform row (tenant NULL) can never be
     * reached from here however the id was obtained.
     *
     * <p>Gated on a LIVE SUBSCRIPTION only: importing a script is available to
     * every paying plan, and a script you may take but may not change is not
     * one you own.
     */
    @Transactional
    public LibraryItem update(String tenantId, String accessToken, Long id, String title,
                              String description, String category, String definition) {
        gate.requireActive(accessToken);
        LibraryItem item = libraryRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> CoreException.notFound("template_not_found",
                        "No such script in your workspace"));
        requireScript(item.getType());
        return apply(item, title, description, category, definition,
                t -> libraryRepository.existsByTenantIdAndTitleIgnoreCase(tenantId, t));
    }

    /** PROVIDER path: edit a PLATFORM catalog item (tenant NULL). */
    @Transactional
    public LibraryItem updatePlatform(Long id, String title, String description,
                                      String category, String definition, Boolean premium) {
        LibraryItem item = libraryRepository.findByIdAndTenantIdIsNull(id)
                .orElseThrow(() -> CoreException.notFound("template_not_found",
                        "No such catalog item"));
        if (premium != null) {
            item.setPremium(premium);
        }
        // No title-clash check: the platform catalog has never enforced unique
        // titles, and inventing that rule here would reject edits to rows that
        // already exist.
        return apply(item, title, description, category, definition, t -> false);
    }

    /**
     * The shared edit. Every field is optional — a blank one keeps what is
     * stored, so a caller that only renames does not have to resend the whole
     * script body.
     */
    private LibraryItem apply(LibraryItem item, String title, String description,
                              String category, String definition,
                              java.util.function.Predicate<String> titleTaken) {
        if (title != null && !title.isBlank() && !title.equals(item.getTitle())) {
            if (titleTaken.test(title.trim())) {
                throw CoreException.conflict("template_owned",
                        "A template with this title already exists in your workspace");
            }
            item.setTitle(title.trim());
        }
        if (description != null) {
            item.setDescription(description);
        }
        if (category != null && !category.isBlank()) {
            item.setCategory(category.trim());
        }
        if (definition != null && !definition.isBlank()) {
            // The STORED type, not one the caller supplies: an edit may change
            // a catalog item's body but never what kind of thing it is.
            validateDefinition(definition, item.getType());
            item.setDefinition(definition);
        }
        return libraryRepository.save(item);
    }

    private static void requireScript(LibraryItem.Type type) {
        if (type != LibraryItem.Type.SCRIPT) {
            throw CoreException.forbidden("script_only",
                    "Workflows and agents are designed by your provider and delivered to "
                            + "your workspace. You can author and edit scripts.");
        }
    }

    /** PROVIDER path: author a PLATFORM catalog template (tenant NULL). */
    @Transactional
    public LibraryItem createPlatform(String actor, String title, String description,
                                      String typeCode, String category, String definition,
                                      boolean premium) {
        return save(null, actor, title, description, typeCode, category, definition, premium);
    }

    private LibraryItem save(String tenantId, String actor, String title, String description,
                             String typeCode, String category, String definition,
                             boolean premium) {
        validateDefinition(definition, parseType(typeCode));
        if (tenantId != null
                && libraryRepository.existsByTenantIdAndTitleIgnoreCase(tenantId, title)) {
            throw CoreException.conflict("template_owned",
                    "A template with this title already exists in your workspace");
        }
        LibraryItem item = new LibraryItem();
        item.setTenantId(tenantId);
        item.setTitle(title);
        item.setDescription(description);
        item.setType(parseType(typeCode));
        item.setCategory(category == null || category.isBlank() ? "General" : category.trim());
        item.setPremium(premium);
        item.setDefinition(definition);
        item.setCreatedBy(actor);
        return libraryRepository.save(item);
    }

    /**
     * The definition has to be runnable AS THE TYPE IT CLAIMS TO BE.
     *
     * <p><b>This used to demand {@code steps[]} or {@code nodes[]} of every
     * catalog item, which made an AGENT impossible to publish.</b> An agent's
     * definition is a persona, a model and an allow-list — it has neither array
     * — so every attempt from the provider console was refused with "Template
     * definition must be JSON with a steps[] or nodes[] array", a message about
     * a shape an agent is not supposed to have.
     *
     * <p>Nobody noticed because the agents in the catalog did not come through
     * here. Every one was written by {@code agents/_schema/publish.py}, which
     * inserts the row directly and never sees this check — so the console
     * builder was complete, its tests passed against a mocked API, and the one
     * thing it could not do was save.
     *
     * <p>The rule is now per type, which is what it should always have been. A
     * single rule covering three unrelated shapes could only ever be satisfied
     * by the two that happened to look alike.
     */
    private void validateDefinition(String definition, LibraryItem.Type type) {
        JsonNode node;
        try {
            node = objectMapper.readTree(definition);
        } catch (Exception ex) {
            throw CoreException.badRequest("invalid_definition",
                    "Template definition must be valid JSON");
        }
        if (!node.isObject()) {
            throw CoreException.badRequest("invalid_definition",
                    "Template definition must be a JSON object");
        }
        switch (type) {
            case WORKFLOW -> require(node.path("nodes").isArray(),
                    "A workflow definition needs a nodes[] array — the graph the runtime "
                            + "executes.");
            case AGENT -> require(
                    // A JSON agent carries its allow-list; a PYTHON agent
                    // carries only a reference to a module in the runtime's
                    // image, and deliberately no persona and no tools of its
                    // own. Both are legitimate catalog rows and neither has an
                    // array this check can key on, which is how they both ended
                    // up refused.
                    node.path("tools").isArray()
                            || (node.path("ref").isTextual() && !node.path("ref").asText().isBlank()),
                    "An agent definition needs either a tools[] allow-list or, for a "
                            + "code-authored agent, a ref naming its module.");
            // A script's body is its steps. Unchanged, so nothing that
            // publishes one today changes behaviour.
            default -> require(node.path("steps").isArray(),
                    "A script definition needs a steps[] array.");
        }
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw CoreException.badRequest("invalid_definition", message);
        }
    }

    private static LibraryItem.Type parseType(String code) {
        if (code == null || code.isBlank()) {
            return LibraryItem.Type.SCRIPT;
        }
        try {
            return LibraryItem.Type.valueOf(code.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw CoreException.badRequest("unknown_template_type",
                    "Template type must be script, workflow, or agent");
        }
    }
}
