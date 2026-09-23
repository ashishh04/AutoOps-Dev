package com.intertec.autoops.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.intertec.autoops.core.domain.LibraryItem;
import com.intertec.autoops.core.exception.CoreException;
import com.intertec.autoops.core.repo.LibraryItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a customer has to grant before a catalog item can do anything.
 *
 * <h2>The gap this closes</h2>
 * Every workflow in the catalog already declares what it needs — the platform,
 * a sentence of plain English, and the exact API permissions — and none of it
 * was ever shown to anyone. It travelled in the definition, through delivery,
 * and sat there. A customer received an automation and found out what access it
 * wanted by running it and reading a permissions error.
 *
 * <h2>Why an AGENT is the case that matters</h2>
 * An agent declares nothing of its own, and it is the item a customer is most
 * likely to be given. Its real requirement is the UNION of what its tools need:
 * the FinOps analyst holds three workflows and therefore wants EC2 describes,
 * Cost Explorer and CloudTrail lookups — five permissions across three
 * services, which nobody can assemble by reading the agent. That union is
 * computed here.
 *
 * <h2>The policy document</h2>
 * A list of API names tells a customer what to grant; it does not tell them
 * HOW. So the AWS permissions are also emitted as an IAM policy document they
 * can paste. Least privilege by construction — it contains exactly the calls
 * the item declares and nothing else, so granting it cannot quietly hand over
 * more than the automation asked for.
 *
 * <h2>What it refuses to imply</h2>
 * A tool whose workflow is not in the catalog is reported by name under
 * {@code unresolved}, never skipped. Silently omitting it would produce a
 * permission list that looks complete and is short — which is worse than no
 * list, because a customer who grants it will still hit a permissions error and
 * will now distrust the next list too.
 */
@Service
public class RequirementsService {

    private final LibraryItemRepository libraryRepository;
    private final ObjectMapper objectMapper;

    public RequirementsService(LibraryItemRepository libraryRepository,
                               ObjectMapper objectMapper) {
        this.libraryRepository = libraryRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * One connection a customer must provide, and everything they need to do it.
     *
     * @param platform     AWS, AZURE, M365 — which credential this is
     * @param customerText the sentence written FOR a customer, taken verbatim
     *                     from the tool's own declaration rather than generated
     *                     here. Whoever wrote the automation knows why it needs
     *                     the access; a generated sentence would be a guess
     *                     dressed as documentation.
     * @param permissions  the exact API calls, deduplicated and sorted
     * @param neededBy     which tools in this item want them, so a customer can
     *                     see what they lose by withholding one
     * @param policyDocument a pasteable IAM policy, or null for platforms that
     *                     do not use one (Microsoft Graph is consented, not
     *                     written as JSON)
     */
    public record Requirement(String platform, String customerText, List<String> permissions,
                              List<String> neededBy, String policyDocument) {
    }

    /**
     * @param unresolved tool refs this catalog does not contain. Named rather
     *                   than dropped: a short list that looks complete is worse
     *                   than an honest gap.
     */
    public record Requirements(Long itemId, String title, String type,
                               List<Requirement> connections, List<String> unresolved) {
    }

    @Transactional(readOnly = true)
    public Requirements forCatalogItem(Long itemId) {
        LibraryItem item = libraryRepository.findByIdAndTenantIdIsNull(itemId)
                .orElseThrow(() -> CoreException.notFound("template_not_found",
                        "No such catalog item"));

        JsonNode spec = parse(item.getDefinition());
        List<String> unresolved = new ArrayList<>();

        // platform -> accumulated requirement
        Map<String, Accumulated> byPlatform = new LinkedHashMap<>();

        if (item.getType() == LibraryItem.Type.AGENT) {
            // An agent's own definition declares nothing. Its requirement is
            // whatever its tools need, which is the only place this can come
            // from and the reason this service exists.
            Map<String, LibraryItem> workflowsByRef = catalogWorkflowsByRef();
            for (JsonNode tool : spec.path("tools")) {
                String ref = tool.path("ref").asText(null);
                if (ref == null || ref.isBlank()) {
                    continue;
                }
                LibraryItem workflow = workflowsByRef.get(ref);
                if (workflow == null) {
                    unresolved.add(ref);
                    continue;
                }
                absorb(byPlatform, parse(workflow.getDefinition()), workflow.getTitle());
            }
        } else {
            absorb(byPlatform, spec, item.getTitle());
        }

        List<Requirement> connections = new ArrayList<>();
        byPlatform.forEach((platform, acc) -> connections.add(new Requirement(
                platform, acc.customerText,
                acc.permissions.stream().sorted().toList(),
                List.copyOf(acc.neededBy),
                policyFor(platform, acc.permissions))));

        return new Requirements(item.getId(), item.getTitle(),
                item.getType().name().toLowerCase(java.util.Locale.ROOT),
                connections, unresolved);
    }

    /** Mutable accumulator while merging several tools onto one connection. */
    private static final class Accumulated {
        private String customerText;
        private final Set<String> permissions = new LinkedHashSet<>();
        private final Set<String> neededBy = new LinkedHashSet<>();
    }

    private void absorb(Map<String, Accumulated> byPlatform, JsonNode definition,
                        String toolTitle) {
        for (JsonNode requirement : definition.path("requires")) {
            if (!"cloud_connection".equals(requirement.path("kind").asText())) {
                continue;
            }
            String platform = requirement.path("platform").asText("");
            if (platform.isBlank()) {
                continue;
            }
            Accumulated acc = byPlatform.computeIfAbsent(platform, key -> new Accumulated());
            // FIRST wins. Two tools on one platform each carry their own
            // sentence, and concatenating them produces a paragraph that reads
            // like neither. The permission list below is the precise answer;
            // the sentence only has to convey why an account is wanted at all.
            if (acc.customerText == null) {
                String text = requirement.path("customerText").asText(null);
                if (text != null && !text.isBlank()) {
                    acc.customerText = text;
                }
            }
            requirement.path("permissions").forEach(p -> acc.permissions.add(p.asText()));
            acc.neededBy.add(toolTitle);
        }
    }

    /** Every catalog workflow, keyed by the stable ref inside its definition. */
    private Map<String, LibraryItem> catalogWorkflowsByRef() {
        Map<String, LibraryItem> byRef = new LinkedHashMap<>();
        for (LibraryItem workflow : libraryRepository.findByTenantIdIsNullOrderByCreatedAtDesc()) {
            if (workflow.getType() != LibraryItem.Type.WORKFLOW) {
                continue;
            }
            String ref = parse(workflow.getDefinition()).path("ref").asText(null);
            if (ref != null && !ref.isBlank()) {
                byRef.put(ref, workflow);
            }
        }
        return byRef;
    }

    /**
     * An IAM policy the customer can paste, or null where one makes no sense.
     *
     * <p>Only AWS. Microsoft Graph permissions are granted by consenting to an
     * application registration, not by writing a document — emitting JSON for
     * them would be an instruction that cannot be followed.
     *
     * <p>{@code Resource: "*"} because every call declared in this catalog is a
     * Describe/List/Lookup that AWS does not scope to an ARN. Narrowing it to a
     * resource pattern would produce a policy that silently denies the calls it
     * claims to allow, which is the worst outcome available here: the customer
     * grants access, the automation still fails, and the policy looks correct.
     */
    private String policyFor(String platform, Set<String> permissions) {
        if (!"AWS".equalsIgnoreCase(platform) || permissions.isEmpty()) {
            return null;
        }
        ObjectNode policy = objectMapper.createObjectNode();
        policy.put("Version", "2012-10-17");
        ArrayNode statements = policy.putArray("Statement");
        ObjectNode statement = statements.addObject();
        statement.put("Sid", "AutoOpsReadOnly");
        statement.put("Effect", "Allow");
        ArrayNode actions = statement.putArray("Action");
        permissions.stream().sorted().forEach(actions::add);
        statement.put("Resource", "*");
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(policy);
        } catch (Exception ex) {
            return null;
        }
    }

    private JsonNode parse(String definition) {
        try {
            return objectMapper.readTree(definition == null ? "{}" : definition);
        } catch (Exception ex) {
            // An unreadable definition yields no requirements rather than an
            // error: the screen asking for them is informational, and failing
            // it would block a page over a row nobody can fix from there.
            return objectMapper.createObjectNode();
        }
    }
}
