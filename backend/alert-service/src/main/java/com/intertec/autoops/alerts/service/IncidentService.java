package com.intertec.autoops.alerts.service;

import com.intertec.autoops.alerts.client.AgentServiceClient;
import com.intertec.autoops.alerts.client.HolmesClient;
import com.intertec.autoops.alerts.client.IncidentEngineClient;
import com.intertec.autoops.alerts.client.KeepApiClient;
import com.intertec.autoops.alerts.exception.AlertException;
import com.intertec.autoops.alerts.web.dto.AlertView;
import com.intertec.autoops.alerts.web.dto.IncidentDetailView;
import com.intertec.autoops.alerts.web.dto.IncidentSummaryView;
import com.intertec.autoops.alerts.web.dto.InvestigationStatusView;
import com.intertec.autoops.alerts.web.dto.InvestigationView;
import com.intertec.autoops.alerts.web.dto.ToolCallView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Incidents: what correlation decided, who owns it, and what investigation
 * concluded.
 *
 * <p>Deliberately additive — it sits beside {@code AlertQueryService} and
 * changes nothing about it. Alerts and incidents answer different questions
 * ("what is firing" versus "what is wrong") and the screens that serve them
 * already work.
 */
@Service
public class IncidentService {

    /** The enrichment key an investigation is stored under on the engine. */
    static final String INVESTIGATION_KEY = "autoops_investigation";

    /**
     * WARN, not DEBUG. Every message this logger carries is the same finding:
     * correlation has grouped alerts across a tenant boundary. That is an
     * operator's problem to fix in the engine's rules, and nobody goes looking
     * for it — a tenant only sees a short evidence list or a refused
     * investigation, neither of which points anywhere.
     */
    private static final Logger log = LoggerFactory.getLogger(IncidentService.class);

    private static final int EVIDENCE_LIMIT = 200;

    /** Where a running agent investigation is remembered between polls. */
    static final String RUN_KEY = "autoops_investigation_run";

    private final IncidentEngineClient incidents;
    private final KeepApiClient alerts;
    private final ProviderCatalogService providers;
    private final HolmesClient holmes;
    private final AgentServiceClient agents;

    public IncidentService(IncidentEngineClient incidents, KeepApiClient alerts,
                           ProviderCatalogService providers, HolmesClient holmes,
                           AgentServiceClient agents) {
        this.incidents = incidents;
        this.alerts = alerts;
        this.providers = providers;
        this.holmes = holmes;
        this.agents = agents;
    }

    /**
     * Whether the console should offer investigation at all.
     *
     * <p>Always true, and that is not laziness. The AWS analyst runs on
     * agent-service and needs no investigation engine, so gating the whole
     * panel on the engine being configured would hide the half of the product
     * that works out of the box. Which engine can serve a given incident is
     * decided per incident, by {@link InvestigationRouter}.
     */
    public boolean investigationEnabled() {
        return true;
    }

    /** Whether the cluster-facing engine specifically is configured. */
    public boolean clusterEngineEnabled() {
        return holmes.isEnabled();
    }

    // ---- ownership ------------------------------------------------------

    /**
     * Which incidents this scope may see.
     *
     * <p>An incident carries no tenant label — it is a correlation over alerts,
     * and the engine does not copy their labels onto it. But every alert DOES
     * carry one now, and each alert names the incident it belongs to. So
     * ownership is derived from the alerts a scope can already see: an incident
     * is yours when it was built from your alerts.
     *
     * <p>Fail-closed, like everything else here: an incident whose alerts are
     * all outside the scope resolves to nothing.
     */
    Set<String> visibleIds(TenantScope scope) {
        Set<String> ids = new HashSet<>();
        for (Map<String, Object> alert : alerts.alerts()) {
            if (!scope.admits(alert)) {
                continue;
            }
            for (String id : incidentIdsOf(alert)) {
                ids.add(id);
            }
        }
        return ids;
    }

    /**
     * The engine reports an alert's incident membership inconsistently — a bare
     * id on some paths, a list on others, an object with an id on a third. All
     * three are read rather than assuming the documented one, because the cost
     * of guessing wrong is a tenant seeing an empty incident list while alerts
     * are clearly firing.
     */
    @SuppressWarnings("unchecked")
    static List<String> incidentIdsOf(Map<String, Object> alert) {
        Object raw = alert.get("incident");
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                String id = idOf(o);
                if (id != null) {
                    out.add(id);
                }
            }
            return out;
        }
        String id = idOf(raw);
        if (id != null) {
            out.add(id);
        }
        return out;
    }

    private static String idOf(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Map<?, ?> m) {
            Object id = m.get("id");
            return id == null ? null : String.valueOf(id);
        }
        String s = String.valueOf(o);
        return s.isBlank() || "null".equals(s) ? null : s;
    }

    private TenantScope enrich(TenantScope scope) {
        if (scope.isProvider() || scope.tenantId() == null) {
            return scope;
        }
        try {
            // No project means the whole tenant. See AlertQueryService.enrich.
            return scope.owning(providers.connectedIds(scope.tenantId(),
                    scope.projectId() == null ? null : String.valueOf(scope.projectId())));
        } catch (RuntimeException ex) {
            return scope;
        }
    }

    // ---- reads ----------------------------------------------------------

    public List<IncidentSummaryView> list(TenantScope rawScope, String status, int limit) {
        TenantScope scope = enrich(rawScope);
        Set<String> visible = scope.isProvider() ? null : visibleIds(scope);
        Map<String, String> ruleNames = ruleNames();

        return incidents.incidents(limit).stream()
                .filter(i -> visible == null || visible.contains(str(i.get("id"))))
                .filter(i -> status == null || status.isBlank()
                        || status.equalsIgnoreCase(str(i.get("status"))))
                .map(i -> summary(i, ruleNames))
                .toList();
    }

    public IncidentDetailView get(TenantScope rawScope, String id) {
        TenantScope scope = enrich(rawScope);
        if (!scope.isProvider() && !visibleIds(scope).contains(id)) {
            // 404 rather than 403, for the same reason an alert outside the
            // scope is: a 403 confirms the incident exists.
            throw AlertException.notFound("incident_not_found", "No such incident");
        }
        Map<String, Object> raw = incidents.incident(id);
        if (raw == null) {
            throw AlertException.notFound("incident_not_found", "No such incident");
        }
        // FILTERED, and this is the reason the filter exists at all.
        //
        // An incident carries no tenant label — it is a correlation over alerts
        // — so visibility is INFERRED: it is yours if at least one of its alerts
        // is. That inference is all-or-nothing, and without this line it let the
        // whole group through once any single alert qualified. If correlation
        // ever grouped two tenants' alerts together, each tenant read the
        // other's alert names, descriptions, services and timings.
        //
        // Ownership of one alert is not ownership of the group.
        List<Map<String, Object>> all = incidents.incidentAlerts(id, EVIDENCE_LIMIT);
        List<Map<String, Object>> mine = admitted(scope, all);
        int withheld = all.size() - mine.size();
        if (withheld > 0) {
            // The operator's signal. A tenant seeing a short list is the
            // symptom; correlation crossing a tenant boundary is the cause, and
            // it is fixed in the engine's rules rather than here.
            log.warn("Incident {} groups {} alert(s) outside tenant {} — correlation is "
                    + "crossing a tenant boundary", id, withheld, scope.tenantId());
        }
        return new IncidentDetailView(summary(raw, ruleNames()),
                mine.stream().map(AlertMapper::alert).toList(),
                withheld,
                investigationOf(raw));
    }

    private Map<String, String> ruleNames() {
        try {
            Map<String, String> names = new HashMap<>();
            for (Map<String, Object> rule : incidents.rules()) {
                String id = str(rule.get("id"));
                if (id != null) {
                    names.put(id, str(rule.get("name")));
                }
            }
            return names;
        } catch (RuntimeException ex) {
            // Losing the rule NAME must not lose the incident. The list still
            // renders; "Correlated by" just reads as unknown.
            return Map.of();
        }
    }

    // ---- writes ---------------------------------------------------------

    public void setStatus(TenantScope scope, String id, String status, String comment) {
        requireVisible(scope, id);
        String wanted = status == null ? "" : status.toLowerCase();
        if (!List.of("firing", "acknowledged", "resolved").contains(wanted)) {
            // merged and deleted are correlation's own business. Letting a
            // console set them would mutate grouping from outside the thing
            // that owns it.
            throw AlertException.badRequest("invalid_status",
                    "An incident can be firing, acknowledged or resolved.");
        }
        incidents.setStatus(id, wanted, comment);
    }

    public void comment(TenantScope scope, String id, String comment) {
        requireVisible(scope, id);
        if (comment == null || comment.isBlank()) {
            throw AlertException.badRequest("empty_comment", "Write something first.");
        }
        incidents.comment(id, comment);
    }

    public void assign(TenantScope scope, String id, String user) {
        requireVisible(scope, id);
        if (user == null || user.isBlank()) {
            throw AlertException.badRequest("missing_assignee", "Name someone to assign this to.");
        }
        incidents.assign(id, user);
    }

    /**
     * The alerts under an incident that this scope may actually see.
     *
     * <p>A provider scope admits everything, which is the same rule the rest of
     * this service applies: it is the operator role and the alert plane is
     * infrastructure it runs.
     */
    private static List<Map<String, Object>> admitted(TenantScope scope,
                                                      List<Map<String, Object>> alerts) {
        if (scope.isProvider()) {
            return alerts;
        }
        return alerts.stream().filter(scope::admits).toList();
    }

    /**
     * The evidence, or a refusal — never a redaction.
     *
     * <p>Investigating is not reading. A filtered detail page shows a tenant
     * less than the whole truth, which is the safe direction. Quietly dropping
     * the same alerts from an investigation PROMPT is not the safe direction at
     * all: the engine would then reason about a production incident from
     * deliberately incomplete evidence and present the conclusion with no hint
     * that half the signal was removed. An RCA built on a redacted timeline is
     * worse than no RCA, because somebody acts on it.
     *
     * <p>So a mixed incident is refused. The message says what is wrong and who
     * can fix it, without naming the other workspace — the caller is entitled
     * to know the answer would be unsound, not to know whose data made it so.
     */
    private List<Map<String, Object>> requireOwnEvidence(TenantScope scope, String id,
                                                         List<Map<String, Object>> alerts) {
        List<Map<String, Object>> mine = admitted(scope, alerts);
        if (mine.size() == alerts.size()) {
            return mine;
        }
        log.warn("Refused to investigate incident {}: {} of {} alert(s) are outside tenant {}",
                id, alerts.size() - mine.size(), alerts.size(), scope.tenantId());
        throw AlertException.forbidden("incident_not_isolated",
                "This incident groups alerts this workspace cannot see, so an investigation "
                        + "would be built on evidence that is not yours to use. Ask your "
                        + "provider to review how these alerts were grouped.");
    }

    private void requireVisible(TenantScope rawScope, String id) {
        TenantScope scope = enrich(rawScope);
        if (!scope.isProvider() && !visibleIds(scope).contains(id)) {
            throw AlertException.notFound("incident_not_found", "No such incident");
        }
    }

    // ---- investigation --------------------------------------------------

    /**
     * Runs an investigation and stores it against the incident.
     *
     * <p>The question is built from the evidence this platform can see, not
     * handed over as free text. A caller-authored prompt reaching an engine
     * that can run commands is a different product with a different threat
     * model; {@code question} is only ever appended as a follow-up, after the
     * evidence.
     */
    public InvestigationStatusView investigate(TenantScope scope, String id, String model,
                                               String question, String bearer, Long projectId) {
        requireVisible(scope, id);
        Map<String, Object> raw = incidents.incident(id);
        if (raw == null) {
            throw AlertException.notFound("incident_not_found", "No such incident");
        }
        List<Map<String, Object>> evidence = requireOwnEvidence(scope, id,
                incidents.incidentAlerts(id, EVIDENCE_LIMIT));
        String ask = prompt(raw, evidence, question);

        InvestigationRouter.Engine engine = InvestigationRouter.route(
                InvestigationRouter.sourcesOf(raw), holmes.isEnabled());

        if (engine == InvestigationRouter.Engine.AWS_AGENT) {
            // An investigation RUNS somewhere, so unlike every read on this
            // service it cannot be answered workspace-wide. When the caller
            // named no project — the workspace-level incident screen never can
            // — the incident's own evidence is asked instead. That is not a
            // guess: the alert was stamped with a project at ingest, or it
            // arrived through a source exactly one project connected.
            return startAgentInvestigation(id, ask, bearer,
                    projectId != null ? projectId : projectFrom(scope, evidence));
        }
        return runHolmes(id, ask, model);
    }

    /**
     * The project an incident belongs to, read off the alerts under it.
     *
     * <p>Two routes, in the order they can be trusted. The label was written by
     * this platform at ingest from a signed token, so it is authoritative. The
     * source is next best: it was connected by one project, under a name only
     * that project's scope produces. Anything else returns null, and the caller
     * says so plainly rather than running somewhere arbitrary.
     */
    private Long projectFrom(TenantScope scope, List<Map<String, Object>> evidence) {
        for (Map<String, Object> alert : evidence) {
            Long labelled = asId(TenantScope.label(alert, TenantScope.PROJECT_LABEL));
            if (labelled != null) {
                return labelled;
            }
        }
        for (Map<String, Object> alert : evidence) {
            Object source = alert.get("providerId");
            if (source == null) {
                continue;
            }
            try {
                Long owner = asId(providers.projectOfSource(
                        scope.tenantId(), String.valueOf(source)));
                if (owner != null) {
                    return owner;
                }
            } catch (RuntimeException ex) {
                // The engine being unreadable costs the shortcut, not the
                // request — the caller gets the same honest "no analyst here"
                // message as a project that genuinely has none.
                return null;
            }
        }
        return null;
    }

    private static Long asId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private InvestigationStatusView runHolmes(String id, String ask, String model) {
        long started = System.currentTimeMillis();
        Map<String, Object> answer = holmes.investigate(ask, model, List.of());
        InvestigationView view = toView(answer, model, System.currentTimeMillis() - started);
        remember(id, view, "engine");
        return InvestigationStatusView.complete("engine", view);
    }

    /**
     * Hands the incident to the AWS RCA agent and returns immediately.
     *
     * <p>The run id is stored ON THE INCIDENT rather than held in memory: an
     * investigation outlives the request that started it, and whoever opens the
     * incident next should see it running rather than a button that starts a
     * second one.
     */
    private InvestigationStatusView startAgentInvestigation(String id, String ask,
                                                            String bearer, Long projectId) {
        Long agentId = agents.findRcaAgentId(bearer, projectId);
        if (agentId == null) {
            // Named plainly, and the two reasons are told apart. The
            // alternative is falling through to an engine with no AWS tools,
            // which produces a confident page of "possible causes" with nothing
            // behind it.
            return InvestigationStatusView.failed("agent", projectId == null
                    ? "This incident could not be traced back to a project, so there is no "
                            + "estate to investigate it against. Open it from its project."
                    : "This project has no AWS incident analyst. "
                            + "Ask your provider to roll it out.");
        }
        Long runId = agents.startRun(bearer, agentId, ask);
        if (runId == null) {
            return InvestigationStatusView.failed("agent",
                    "The investigation could not be started");
        }
        try {
            incidents.enrich(id, Map.of(RUN_KEY, Map.of("runId", runId, "engine", "agent")));
        } catch (RuntimeException ex) {
            // Losing the pointer costs a duplicate run, never the answer.
            return InvestigationStatusView.running("agent", runId);
        }
        return InvestigationStatusView.running("agent", runId);
    }

    /**
     * Where an investigation has got to.
     *
     * <p>Answers from what is stored first: a finished investigation is read
     * back rather than re-run, and only a run still in flight is polled.
     */
    public InvestigationStatusView investigationStatus(TenantScope scope, String id,
                                                       String bearer) {
        requireVisible(scope, id);
        Map<String, Object> raw = incidents.incident(id);
        if (raw == null) {
            throw AlertException.notFound("incident_not_found", "No such incident");
        }
        InvestigationView stored = investigationOf(raw);
        if (stored != null) {
            return InvestigationStatusView.complete(engineOf(raw), stored);
        }
        Long runId = runIdOf(raw);
        if (runId == null) {
            return InvestigationStatusView.none();
        }

        Map<String, Object> run = agents.getRun(bearer, runId);
        String status = run == null ? null : str(run.get("status"));
        if (status == null) {
            return InvestigationStatusView.running("agent", runId);
        }
        String s = status.toUpperCase();
        if (s.contains("FAIL") || s.contains("ERROR") || s.contains("CANCEL")) {
            String message = str(run.get("error"));
            return InvestigationStatusView.failed("agent",
                    message == null || message.isBlank()
                            ? "The investigation did not complete." : message);
        }
        if (!s.contains("SUCCE") && !s.contains("COMPLETE") && !s.contains("DONE")) {
            return InvestigationStatusView.running("agent", runId);
        }

        InvestigationView view = fromAgentRun(run);
        remember(id, view, "agent");
        return InvestigationStatusView.complete("agent", view);
    }

    /**
     * An agent run, read as an investigation.
     *
     * <p>The shapes line up almost exactly, which is the point: the agent's
     * output IS the analysis, and its steps ARE what it looked at. A step that
     * errored stays marked as one, so the same rule holds for both engines —
     * a conclusion built on failed tools is visibly that.
     */
    @SuppressWarnings("unchecked")
    static InvestigationView fromAgentRun(Map<String, Object> run) {
        List<ToolCallView> calls = new ArrayList<>();
        Object steps = run.get("steps");
        if (steps instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) {
                    continue;
                }
                Map<String, Object> step = (Map<String, Object>) m;
                String tool = str(step.get("toolName"));
                if (tool == null || tool.isBlank()) {
                    // Thinking steps carry no tool. They are the agent's
                    // reasoning, not something it looked at.
                    continue;
                }
                calls.add(new ToolCallView(tool, str(step.get("request")),
                        str(step.get("response")), !Boolean.TRUE.equals(step.get("isError"))));
            }
        }
        Long took = null;
        Object started = run.get("startedAt");
        Object finished = run.get("finishedAt");
        if (started != null && finished != null) {
            try {
                took = java.time.Duration.between(Instant.parse(String.valueOf(started)),
                        Instant.parse(String.valueOf(finished))).toMillis();
            } catch (RuntimeException ignored) {
                took = null;
            }
        }
        return new InvestigationView(str(run.get("output")), calls, List.of(),
                str(run.get("model")), Instant.now().toString(), took, null);
    }

    private void remember(String id, InvestigationView view, String engine) {
        try {
            Map<String, Object> stored = new LinkedHashMap<>(store(view));
            stored.put("engine", engine);
            incidents.enrich(id, Map.of(INVESTIGATION_KEY, stored));
        } catch (RuntimeException ex) {
            // The investigation succeeded and the customer paid for it. Failing
            // to cache it is worth a re-run later, never worth discarding now.
        }
    }

    @SuppressWarnings("unchecked")
    static Long runIdOf(Map<String, Object> incident) {
        Object enrichments = incident.get("enrichments");
        if (!(enrichments instanceof Map<?, ?> e)) {
            return null;
        }
        Object run = ((Map<String, Object>) e).get(RUN_KEY);
        if (run instanceof Map<?, ?> m && m.get("runId") instanceof Number n) {
            return n.longValue();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    static String engineOf(Map<String, Object> incident) {
        Object enrichments = incident.get("enrichments");
        if (enrichments instanceof Map<?, ?> e) {
            Object stored = ((Map<String, Object>) e).get(INVESTIGATION_KEY);
            if (stored instanceof Map<?, ?> m && m.get("engine") != null) {
                return String.valueOf(m.get("engine"));
            }
        }
        return "engine";
    }

    static String prompt(Map<String, Object> incident, List<Map<String, Object>> evidence,
                         String question) {
        StringBuilder sb = new StringBuilder();
        sb.append("Investigate this production incident and identify the root cause.\n\n");
        sb.append("Incident: ").append(nameOf(incident)).append('\n');
        sb.append("Severity: ").append(str(incident.get("severity"))).append('\n');
        sb.append("Status: ").append(str(incident.get("status"))).append('\n');
        Object services = incident.get("services");
        if (services instanceof List<?> l && !l.isEmpty()) {
            sb.append("Affected services: ").append(String.join(", ",
                    l.stream().map(String::valueOf).toList())).append('\n');
        }
        sb.append("\nThe following alerts were correlated into this incident:\n");
        for (Map<String, Object> a : evidence) {
            sb.append("- [").append(str(a.get("severity"))).append("] ")
                    .append(str(a.get("name")));
            String desc = str(a.get("description"));
            if (desc != null && !desc.isBlank()) {
                sb.append(" — ").append(desc);
            }
            String svc = str(a.get("service"));
            if (svc != null && !svc.isBlank()) {
                sb.append(" (service: ").append(svc).append(')');
            }
            String at = str(a.get("lastReceived"));
            if (at != null && !at.isBlank()) {
                sb.append(" at ").append(at);
            }
            sb.append('\n');
        }
        if (question != null && !question.isBlank()) {
            sb.append("\nSpecifically: ").append(question).append('\n');
        }
        sb.append("\nState what you verified and what you could not. ")
                .append("Do not present an unverified guess as a conclusion.");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    static InvestigationView toView(Map<String, Object> answer, String model, long tookMs) {
        if (answer == null) {
            throw AlertException.upstream("investigation_failed",
                    "The investigation returned nothing");
        }
        List<ToolCallView> calls = new ArrayList<>();
        Object rawCalls = answer.get("tool_calls");
        if (rawCalls instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    Map<String, Object> c = (Map<String, Object>) m;
                    calls.add(new ToolCallView(
                            str(c.get("tool_name")) != null ? str(c.get("tool_name"))
                                    : str(c.get("tool")),
                            str(c.get("description")),
                            str(c.get("result")) != null ? str(c.get("result"))
                                    : str(c.get("output")),
                            succeeded(c)));
                }
            }
        }
        List<String> follow = new ArrayList<>();
        Object rawFollow = answer.get("follow_up_actions");
        if (rawFollow instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    String p = str(m.get("prompt"));
                    follow.add(p != null ? p : str(m.get("action_label")));
                } else if (o != null) {
                    follow.add(String.valueOf(o));
                }
            }
        }
        Double cost = null;
        Object meta = answer.get("metadata");
        if (meta instanceof Map<?, ?> m && m.get("usage") instanceof Map<?, ?> u
                && u.get("total_cost") instanceof Number n) {
            cost = n.doubleValue();
        }
        return new InvestigationView(str(answer.get("analysis")), calls, follow,
                model, Instant.now().toString(), tookMs, cost);
    }

    /**
     * The engine reports a tool result's success under more than one shape
     * across versions. Anything that is not clearly a failure is treated as a
     * success, so a renamed field does not paint every working command red.
     */
    private static boolean succeeded(Map<String, Object> call) {
        Object status = call.get("status");
        if (status != null) {
            String s = String.valueOf(status).toLowerCase();
            return !(s.contains("error") || s.contains("fail"));
        }
        Object ok = call.get("succeeded");
        if (ok instanceof Boolean b) {
            return b;
        }
        return true;
    }

    private static Map<String, Object> store(InvestigationView v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("analysis", v.analysis());
        m.put("model", v.model());
        m.put("askedAt", v.askedAt());
        m.put("tookMs", v.tookMs());
        m.put("costUsd", v.costUsd());
        List<Map<String, Object>> calls = new ArrayList<>();
        for (ToolCallView c : v.toolCalls()) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("tool", c.tool());
            cm.put("description", c.description());
            // The OUTPUT is deliberately not stored. Tool output is unbounded -
            // a log dump is megabytes - and it would be written into the
            // engine's enrichment on every run. What it looked at and whether
            // it worked is the part that survives.
            cm.put("succeeded", c.succeeded());
            calls.add(cm);
        }
        m.put("toolCalls", calls);
        m.put("followUps", v.followUps());
        return m;
    }

    @SuppressWarnings("unchecked")
    static InvestigationView investigationOf(Map<String, Object> incident) {
        Object enrichments = incident.get("enrichments");
        if (!(enrichments instanceof Map<?, ?> e)) {
            return null;
        }
        Object stored = ((Map<String, Object>) e).get(INVESTIGATION_KEY);
        if (!(stored instanceof Map<?, ?> s)) {
            return null;
        }
        Map<String, Object> m = (Map<String, Object>) s;
        List<ToolCallView> calls = new ArrayList<>();
        if (m.get("toolCalls") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> c) {
                    calls.add(new ToolCallView(str(c.get("tool")), str(c.get("description")),
                            null, !Boolean.FALSE.equals(c.get("succeeded"))));
                }
            }
        }
        List<String> follow = new ArrayList<>();
        if (m.get("followUps") instanceof List<?> list) {
            list.forEach(o -> follow.add(String.valueOf(o)));
        }
        return new InvestigationView(str(m.get("analysis")), calls, follow,
                str(m.get("model")), str(m.get("askedAt")),
                m.get("tookMs") instanceof Number n ? n.longValue() : null,
                m.get("costUsd") instanceof Number c ? c.doubleValue() : null);
    }

    // ---- mapping --------------------------------------------------------

    private IncidentSummaryView summary(Map<String, Object> raw, Map<String, String> ruleNames) {
        String ruleId = str(raw.get("rule_id"));
        String ruleName = str(raw.get("rule_name"));
        if ((ruleName == null || ruleName.isBlank()) && ruleId != null) {
            ruleName = ruleNames.get(ruleId);
        }
        return new IncidentSummaryView(
                str(raw.get("id")),
                nameOf(raw),
                summaryOf(raw),
                str(raw.get("severity")),
                str(raw.get("status")),
                str(raw.get("assignee")),
                raw.get("alerts_count") instanceof Number n ? n.intValue() : 0,
                strings(raw.get("services")),
                strings(raw.get("alert_sources")),
                str(raw.get("start_time")),
                str(raw.get("last_seen_time")),
                ruleName,
                investigationOf(raw) != null);
    }

    private static String nameOf(Map<String, Object> raw) {
        String name = str(raw.get("user_generated_name"));
        if (name == null || name.isBlank()) {
            name = str(raw.get("ai_generated_name"));
        }
        return name;
    }

    private static String summaryOf(Map<String, Object> raw) {
        String s = str(raw.get("user_summary"));
        if (s == null || s.isBlank()) {
            s = str(raw.get("generated_summary"));
        }
        return s;
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static List<String> strings(Object v) {
        if (v instanceof List<?> l) {
            return l.stream().filter(java.util.Objects::nonNull).map(String::valueOf).toList();
        }
        return List.of();
    }
}
