package com.intertec.autoops.alerts.service;

import com.intertec.autoops.alerts.client.IncidentEngineClient;
import com.intertec.autoops.alerts.exception.AlertException;
import com.intertec.autoops.alerts.web.dto.CorrelationRuleView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Correlation rules a customer owns.
 *
 * <h2>Why this is a tenant feature and not an operator knob</h2>
 * Correlation is what turns a feed of alerts into incidents, and nothing groups
 * without a rule — an engine with no rules produces no incidents at all, no
 * matter how many alerts arrive. Making that an operator task means every
 * customer who wants their alerts grouped has to ask their provider, wait, and
 * ask again when their estate changes. They will not; they will read the raw
 * feed and conclude the incident half of the product does not work.
 *
 * <h2>The boundary survives because the customer does not write the rule</h2>
 * The engine holds every tenant's rules in one place and has no tenant concept
 * of its own — the same situation as providers, solved the same way:
 *
 * <ul>
 *   <li>the rule's NAME is computed, never accepted. It carries
 *       {@code autoops--{tenant}--{project}--{label}} exactly as a connected
 *       source does, so ownership is a prefix comparison and a rule cannot be
 *       listed, edited or deleted from outside the scope that made it;</li>
 *   <li>the rule's EXPRESSION is computed too. A customer picks values from a
 *       fixed vocabulary and {@link CorrelationQuery} writes the CEL, with
 *       their own tenant ANDed in. A caller never supplies an expression, so
 *       there is no expression to get wrong.</li>
 * </ul>
 *
 * <p>Those two together are what make a rule safe to expose. Either alone is
 * not: a computed name with a caller-supplied expression would let a tenant
 * write a rule that correlated someone else's alerts under their own name.
 *
 * <h2>The gap, stated plainly</h2>
 * The tenant predicate matches on the {@code autoops_tenant} label, which is
 * stamped at ingest from a signed token. Alerts the engine PULLS for itself —
 * a credentialed Datadog or New Relic connection — never pass through that door
 * and carry no label, so they will not correlate. They still appear in the
 * alert feed, recognised by the source they arrived through. Closing that means
 * back-filling the label onto pulled alerts before correlation runs; until then
 * this is honest about covering the push path only.
 */
@Service
public class CorrelationService {

    private static final Logger log = LoggerFactory.getLogger(CorrelationService.class);

    /**
     * A bound, not a product limit. Every rule is evaluated against every alert
     * that arrives, so an unbounded list is a way for one workspace to make the
     * engine slow for all of them.
     */
    private static final int MAX_RULES_PER_TENANT = 25;

    /** Below this an incident would be declared before its siblings arrived. */
    private static final int MIN_WINDOW_SECONDS = 60;

    /** Above a day, "these are the same problem" stops being a claim anyone believes. */
    private static final int MAX_WINDOW_SECONDS = 86_400;

    private final IncidentEngineClient incidents;

    public CorrelationService(IncidentEngineClient incidents) {
        this.incidents = incidents;
    }

    /**
     * What a customer asked for, before any of it is trusted.
     *
     * @param label       their own name for the rule; sanitised into the
     *                    computed engine name and never used as one
     * @param conditions  which alerts this rule looks at, on top of the tenant
     *                    predicate that is always applied
     * @param groupBy     what makes two matching alerts the same incident
     * @param windowSeconds how long the grouping window is
     */
    public record Draft(String label, List<CorrelationQuery.Condition> conditions,
                        List<String> groupBy, int windowSeconds) {
    }

    /** Every rule this scope owns. Scoped by computed name, never by input. */
    public List<CorrelationRuleView> list(String tenantId, String projectId) {
        List<CorrelationRuleView> out = new ArrayList<>();
        for (Map<String, Object> raw : incidents.rules()) {
            String name = str(raw.get("name"));
            if (!ProviderNaming.belongsTo(name, tenantId, projectId)) {
                continue;
            }
            out.add(toView(raw, tenantId, projectId));
        }
        return out;
    }

    public CorrelationRuleView create(String tenantId, String projectId, Draft draft) {
        if (projectId == null || projectId.isBlank()) {
            // A rule is named into a project the same way a source is, and the
            // name is the only record of ownership. There is nowhere to put a
            // workspace-wide rule that would still be recognisable as one.
            throw AlertException.badRequest("missing_project",
                    "Choose which project this correlation rule belongs to.");
        }
        if (list(tenantId, null).size() >= MAX_RULES_PER_TENANT) {
            throw AlertException.badRequest("too_many_rules",
                    "A workspace can have at most " + MAX_RULES_PER_TENANT
                            + " correlation rules.");
        }

        String name = ProviderNaming.qualify(tenantId, projectId, draft.label());
        String cel = CorrelationQuery.compile(tenantId, draft.conditions());
        List<String> groupBy = CorrelationQuery.groupingCriteria(draft.groupBy());
        if (groupBy.isEmpty()) {
            throw AlertException.badRequest("missing_grouping",
                    "Choose what makes two alerts the same incident — "
                            + "service, source or severity.");
        }

        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("ruleName", name);
        rule.put("celQuery", cel);
        rule.put("sqlQuery", CorrelationQuery.storedDefinition(cel));
        rule.put("timeframeInSeconds", window(draft.windowSeconds()));
        rule.put("timeUnit", "seconds");
        rule.put("groupingCriteria", groupBy);
        // "any" — one matching alert opens the incident and later ones join it.
        // "all" would hold an outage invisible until every grouping value had
        // been seen, which is the opposite of what an incident is for.
        rule.put("createOn", "any");
        rule.put("resolveOn", "never");

        Map<String, Object> created = incidents.createRule(rule);
        log.info("Tenant {} created correlation rule {} in project {}",
                tenantId, str(created == null ? null : created.get("id")), projectId);
        return toView(created, tenantId, projectId);
    }

    /**
     * Removes one.
     *
     * <p>Ownership is proved by finding the id among the rules this SCOPE owns,
     * not by reading the id's own row — an id alone must never select anything
     * here. 404 rather than 403 for the same reason the rest of this service
     * does it: a 403 confirms the rule exists.
     */
    public void delete(String tenantId, String projectId, String id) {
        boolean owned = list(tenantId, projectId).stream()
                .anyMatch(r -> id.equals(r.id()));
        if (!owned) {
            throw AlertException.notFound("rule_not_found", "No such correlation rule.");
        }
        incidents.deleteRule(id);
        log.info("Tenant {} deleted correlation rule {}", tenantId, id);
    }

    /**
     * Clamped rather than rejected: a silly window is a bad request to answer
     * sensibly, not one to refuse. The customer sees what was applied.
     */
    private static int window(int requested) {
        return Math.max(MIN_WINDOW_SECONDS, Math.min(requested, MAX_WINDOW_SECONDS));
    }

    private static CorrelationRuleView toView(Map<String, Object> raw, String tenantId,
                                              String projectId) {
        if (raw == null) {
            throw AlertException.upstream("rule_not_created",
                    "The alert engine did not confirm the rule");
        }
        String name = str(raw.get("name"));
        String owner = ProviderNaming.projectOf(name, tenantId);
        return new CorrelationRuleView(
                str(raw.get("id")),
                // The customer's own label, with the scoping prefix stripped.
                // They named it "payments outage", not
                // "autoops--acme--7--payments-outage".
                ProviderNaming.label(name, tenantId, projectId),
                owner == null ? projectId : owner,
                strings(raw.get("grouping_criteria")),
                raw.get("timeframe") instanceof Number n ? n.intValue() : 0,
                // The expression is shown READ-ONLY. "Why are these grouped?"
                // is the first question anyone asks of a correlated view, and a
                // rule nobody can inspect is a grouping nobody trusts.
                str(raw.get("definition_cel")),
                str(raw.get("creation_time")));
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object o : list) {
            if (o != null) {
                out.add(String.valueOf(o).toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }
}
