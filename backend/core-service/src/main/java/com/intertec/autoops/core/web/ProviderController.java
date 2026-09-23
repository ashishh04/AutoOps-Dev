package com.intertec.autoops.core.web;

import com.intertec.autoops.core.client.WorkflowClient;
import com.intertec.autoops.core.config.CoreProperties;
import com.intertec.autoops.core.domain.AppNotification;
import com.intertec.autoops.core.domain.CoreAuditEventType;
import com.intertec.autoops.core.domain.LibraryItem;
import com.intertec.autoops.core.exception.CoreException;
import com.intertec.autoops.core.service.AuditService;
import com.intertec.autoops.core.service.LibraryService;
import com.intertec.autoops.core.service.NotificationService;
import com.intertec.autoops.core.service.RolloutService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Platform-operator surface (PROVIDER role claim required on every call):
 * cross-tenant usage, platform health, tenant broadcasts, and authoring of
 * the platform template catalog. Data here is aggregated from what this
 * service actually stores — nothing invented.
 */
@RestController
@RequestMapping("/api/provider")
public class ProviderController {

    private static final String PROVIDER_ROLE = "PROVIDER";

    private final JdbcTemplate jdbcTemplate;
    private final NotificationService notificationService;
    private final LibraryService libraryService;
    private final AuditService auditService;
    private final CoreProperties properties;
    private final WorkflowClient workflowClient;
    private final RolloutService rolloutService;
    private final RestClient healthClient;

    public ProviderController(JdbcTemplate jdbcTemplate,
                              NotificationService notificationService,
                              LibraryService libraryService,
                              AuditService auditService,
                              CoreProperties properties,
                              WorkflowClient workflowClient,
                              RolloutService rolloutService) {
        this.jdbcTemplate = jdbcTemplate;
        this.notificationService = notificationService;
        this.libraryService = libraryService;
        this.auditService = auditService;
        this.properties = properties;
        this.workflowClient = workflowClient;
        this.rolloutService = rolloutService;
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
        requestFactory.setReadTimeout(Duration.ofSeconds(3));
        this.healthClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    // ------ usage: real aggregates over this service's own tables ------

    public record TenantUsage(String tenantId, long projects, long jobs, long workflows,
                              long runs30d, long failedRuns30d) {
    }

    /**
     * Workflows are no longer a table in this database, so the old single
     * SQL statement became one query here plus one call to workflow-service,
     * joined by tenant id. An unreachable workflow-service reports 0
     * workflows rather than failing the whole usage table.
     */
    @GetMapping("/usage")
    public List<TenantUsage> usage(@AuthenticationPrincipal Jwt jwt) {
        requireProvider(jwt);
        Map<String, Long> workflowCounts = workflowClient.countsByTenant();
        return jdbcTemplate.query("""
                SELECT p.tenant_id,
                       COUNT(DISTINCT p.id) AS projects,
                       (SELECT COUNT(*) FROM jobs j WHERE j.tenant_id = p.tenant_id) AS jobs,
                       (SELECT COUNT(*) FROM runs r WHERE r.tenant_id = p.tenant_id
                            AND r.created_at >= ?) AS runs30d,
                       (SELECT COUNT(*) FROM runs r WHERE r.tenant_id = p.tenant_id
                            AND r.created_at >= ? AND r.status = 'FAILED') AS failed30d
                FROM projects p GROUP BY p.tenant_id ORDER BY runs30d DESC
                """,
                (rs, i) -> new TenantUsage(rs.getString(1), rs.getLong(2), rs.getLong(3),
                        workflowCounts.getOrDefault(rs.getString(1), 0L),
                        rs.getLong(4), rs.getLong(5)),
                java.sql.Timestamp.from(Instant.now().minus(Duration.ofDays(30))),
                java.sql.Timestamp.from(Instant.now().minus(Duration.ofDays(30))));
    }

    // ------ health: this service's real view of the whole platform ------

    /** One probed dependency. {@code latencyMs} is null when the probe failed. */
    public record ServiceHealth(String key, String label, String status, Long latencyMs) {
    }

    private record Target(String key, String label, String baseUrl) {
    }

    /**
     * Live platform status: every service probed on its actuator endpoint, plus
     * real counters read from this service's own tables.
     *
     * <p>Probes run concurrently on virtual threads. Sequentially, eight
     * dependencies at a 2s timeout could take 16s to answer — longer than the
     * page's own refresh interval, which would queue requests forever.
     *
     * <p>The flat {@code coreDatabase} / {@code jobService} /
     * {@code subscriptionService} keys are kept alongside the richer
     * {@code services} list so an older frontend still renders during a rollout.
     */
    @GetMapping("/health")
    public Map<String, Object> health(@AuthenticationPrincipal Jwt jwt) {
        requireProvider(jwt);

        long dbStart = System.nanoTime();
        boolean db;
        try {
            db = jdbcTemplate.queryForObject("SELECT 1", Integer.class) != null;
        } catch (Exception ex) {
            db = false;
        }
        long dbMs = Duration.ofNanos(System.nanoTime() - dbStart).toMillis();

        List<Target> targets = List.of(
                new Target("apiGateway", "API gateway",
                        properties.getHealth().getGatewayBaseUrl()),
                new Target("authService", "Auth service", authBaseUrl()),
                new Target("subscriptionService", "Subscription service",
                        properties.getSubscription().getBaseUrl()),
                new Target("jobService", "Job service",
                        properties.getExecution().getJobServiceUrl()),
                new Target("workflowService", "Workflow service",
                        properties.getWorkflow().getBaseUrl()),
                new Target("agentService", "Agent service",
                        properties.getAgent().getBaseUrl()),
                new Target("voiceAgent", "Voice agent",
                        properties.getHealth().getVoiceBaseUrl()));

        List<ServiceHealth> services = new ArrayList<>();
        services.add(new ServiceHealth("coreDatabase", "Core database",
                db ? "UP" : "DOWN", db ? dbMs : null));
        Integer tenantsTotal = null;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<ServiceHealth>> probes = targets.stream()
                    .map(t -> pool.submit(() -> probe(t)))
                    .toList();
            // Rides the same fan-out so the registry lookup costs no extra
            // wall-clock on top of the probes.
            Future<Integer> tenants = pool.submit(() -> tenantCount(jwt.getTokenValue()));
            for (Future<ServiceHealth> probeResult : probes) {
                services.add(await(probeResult, null));
            }
            tenantsTotal = await(tenants, null);
        }
        services.removeIf(java.util.Objects::isNull);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("checkedAt", Instant.now().toString());
        body.put("executionMode", properties.getExecution().getMode());
        body.put("services", services);
        body.put("metrics", metrics(dbMs, tenantsTotal));
        body.put("scheduler", schedulerLease());
        // Legacy flat keys — see javadoc.
        body.put("coreDatabase", db ? "UP" : "DOWN");
        for (ServiceHealth s : services) {
            body.putIfAbsent(s.key(), s.status());
        }
        return body;
    }

    /**
     * auth-service's base URL, taken from the JWKS endpoint this service
     * already validates tokens against, so probing it needs no new config.
     */
    private String authBaseUrl() {
        String jwks = properties.getJwksUri();
        int path = jwks.indexOf("/oauth2/");
        return path > 0 ? jwks.substring(0, path) : jwks;
    }

    /** Result of a probe, or {@code fallback} if it failed — never throws. */
    private static <T> T await(Future<T> future, T fallback) {
        try {
            return future.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return fallback;
        } catch (Exception ex) {
            return fallback;
        }
    }

    /**
     * Total registered tenants, from auth-service — the registry of record.
     * This service only sees tenants that own a project, which undercounts
     * anyone who has signed up but not created one yet.
     *
     * <p>Relays the caller's own provider token rather than introducing a
     * shared secret: the endpoint already requires the PROVIDER claim, and
     * forwarding it keeps the authorization decision exactly where it was.
     * Returns null when auth-service is unreachable, so the page degrades to
     * "—" instead of failing the whole health call.
     */
    private Integer tenantCount(String bearerToken) {
        try {
            List<?> rows = healthClient.get()
                    .uri(authBaseUrl() + "/api/auth/provider/tenants")
                    .header("Authorization", "Bearer " + bearerToken)
                    .retrieve().body(List.class);
            return rows == null ? null : rows.size();
        } catch (Exception ex) {
            return null;
        }
    }

    private ServiceHealth probe(Target target) {
        long start = System.nanoTime();
        try {
            String body = healthClient.get().uri(target.baseUrl() + "/actuator/health")
                    .retrieve().body(String.class);
            long ms = Duration.ofNanos(System.nanoTime() - start).toMillis();
            boolean up = body != null && body.contains("UP");
            return new ServiceHealth(target.key(), target.label(),
                    up ? "UP" : "DOWN", up ? ms : null);
        } catch (Exception ex) {
            return new ServiceHealth(target.key(), target.label(), "DOWN", null);
        }
    }

    /**
     * Platform-wide counters, straight off this service's tables.
     *
     * <p>{@code tenantsTotal} is the registry count from auth-service; the
     * {@code tenants} figure below is the subset that owns a project. Both are
     * reported because the gap between them is itself a signal — tenants who
     * signed up and never started.
     */
    private Map<String, Object> metrics(long dbLatencyMs, Integer tenantsTotal) {
        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("dbLatencyMs", dbLatencyMs);
        empty.put("tenantsTotal", tenantsTotal);
        try {
            java.sql.Timestamp since =
                    java.sql.Timestamp.from(Instant.now().minus(Duration.ofHours(24)));
            Map<String, Object> row = jdbcTemplate.queryForMap("""
                    SELECT (SELECT COUNT(DISTINCT tenant_id) FROM projects)         AS tenants,
                           (SELECT COUNT(*) FROM projects)                          AS projects,
                           (SELECT COUNT(*) FROM jobs)                              AS jobs,
                           (SELECT COUNT(*) FROM jobs WHERE enabled = 1)            AS jobsEnabled,
                           (SELECT COUNT(*) FROM runs WHERE created_at >= ?)        AS runs24h,
                           (SELECT COUNT(*) FROM runs WHERE created_at >= ?
                                AND status = 'FAILED')                              AS failed24h,
                           (SELECT COUNT(*) FROM runs WHERE status = 'RUNNING')     AS running
                    """, since, since);
            Map<String, Object> out = new LinkedHashMap<>(row);
            out.put("dbLatencyMs", dbLatencyMs);
            out.put("tenantsTotal", tenantsTotal);
            return out;
        } catch (Exception ex) {
            return empty;
        }
    }

    /**
     * Which instance currently owns the cron lease. A stale or missing lease
     * means nothing is firing scheduled jobs — invisible without this.
     */
    private Map<String, Object> schedulerLease() {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT holder, expires_at FROM scheduler_lease WHERE name = ?",
                    "job-scheduler");
            if (rows.isEmpty()) {
                out.put("status", "IDLE");
                return out;
            }
            Map<String, Object> row = rows.get(0);
            Instant expires = ((java.sql.Timestamp) row.get("expires_at")).toInstant();
            out.put("holder", String.valueOf(row.get("holder")));
            out.put("expiresAt", expires.toString());
            out.put("status", expires.isAfter(Instant.now()) ? "UP" : "STALE");
        } catch (Exception ex) {
            out.put("status", "UNKNOWN");
        }
        return out;
    }

    // ------ broadcasts: PROVIDER notifications fanned out to tenants ------

    public record BroadcastRequest(@NotBlank @Size(max = 255) String title,
                                   @Size(max = 1024) String body,
                                   @Size(max = 255) String link,
                                   List<String> tenantIds) {
    }

    @PostMapping("/broadcasts")
    public Map<String, Object> broadcast(@Valid @RequestBody BroadcastRequest request,
                                         @AuthenticationPrincipal Jwt jwt) {
        requireProvider(jwt);
        List<String> tenants = request.tenantIds() != null && !request.tenantIds().isEmpty()
                ? request.tenantIds()
                : jdbcTemplate.queryForList(
                        "SELECT DISTINCT tenant_id FROM projects", String.class);
        for (String tenant : tenants) {
            notificationService.publish(tenant, AppNotification.Kind.PROVIDER,
                    request.title(), request.body(), request.link());
        }
        auditService.record(CoreAuditEventType.BROADCAST_SENT,
                jwt.getClaimAsString("tenantId"), jwt.getSubject(), null, "BROADCAST",
                null, request.title(), "to " + tenants.size() + " tenant(s)");
        return Map.of("sent", tenants.size());
    }

    // ------ platform template catalog authoring ------

    public record PlatformTemplateRequest(@NotBlank @Size(max = 128) String title,
                                          @Size(max = 512) String description,
                                          @Size(max = 16) String type,
                                          @Size(max = 64) String category,
                                          boolean premium,
                                          @NotBlank String definition) {
    }

    @PostMapping("/library")
    public Map<String, Object> createTemplate(@Valid @RequestBody PlatformTemplateRequest request,
                                              @AuthenticationPrincipal Jwt jwt) {
        requireProvider(jwt);
        LibraryItem item = libraryService.createPlatform(jwt.getSubject(), request.title(),
                request.description(), request.type(), request.category(),
                request.definition(), request.premium());
        return Map.of("id", item.getId(), "title", item.getTitle(),
                "type", item.getType().name().toLowerCase(Locale.ROOT));
    }

    /**
     * Edit a catalog item. Every field is optional, so the console can rename
     * without resending the script body — and {@code premium} being boxed is
     * what lets "leave the pricing alone" differ from "make it free".
     */
    public record PlatformTemplateUpdate(@Size(max = 128) String title,
                                         @Size(max = 512) String description,
                                         @Size(max = 64) String category,
                                         Boolean premium,
                                         String definition) {
    }

    @PutMapping("/library/{id}")
    public Map<String, Object> updateTemplate(@PathVariable Long id,
                                              @Valid @RequestBody PlatformTemplateUpdate request,
                                              @AuthenticationPrincipal Jwt jwt) {
        requireProvider(jwt);
        LibraryItem item = libraryService.updatePlatform(id, request.title(),
                request.description(), request.category(), request.definition(),
                request.premium());
        // The catalog belongs to no customer, so the row is filed against the
        // PROVIDER's own tenant — same as a broadcast. core_audit_log.tenant_id
        // is NOT NULL, so "no tenant" is not available even if it read better.
        auditService.record(CoreAuditEventType.LIBRARY_UPDATED,
                jwt.getClaimAsString("tenantId"), jwt.getSubject(), null, "TEMPLATE",
                item.getId(), item.getTitle(), "catalog item edited");
        return Map.of("id", item.getId(), "title", item.getTitle(),
                "type", item.getType().name().toLowerCase(Locale.ROOT));
    }

    // ------ rollout: delivering catalog items to customers ------

    /**
     * A customer's projects, so the console can ask WHERE a workflow or agent
     * should land. Names and ids only — a provider picking a delivery target
     * has no reason to see the project's contents.
     */
    @GetMapping("/tenants/{tenantId}/projects")
    public List<Map<String, Object>> tenantProjects(@PathVariable String tenantId,
                                                    @AuthenticationPrincipal Jwt jwt) {
        requireProvider(jwt);
        return jdbcTemplate.query(
                "SELECT id, name, status FROM projects WHERE tenant_id = ? ORDER BY name",
                (rs, i) -> Map.of("id", rs.getLong(1), "name", rs.getString(2),
                        "status", rs.getString(3)),
                tenantId);
    }

    public record RolloutRequest(@NotNull Long catalogId,
                                 @NotEmpty List<RolloutTarget> targets) {
    }

    public record RolloutTarget(@NotBlank String tenantId, @NotNull Long projectId) {
    }

    @PostMapping("/rollout")
    public RolloutService.RolloutResult rollOut(@Valid @RequestBody RolloutRequest request,
                                                @AuthenticationPrincipal Jwt jwt) {
        requireProvider(jwt);
        List<RolloutService.Target> targets = request.targets().stream()
                .map(t -> new RolloutService.Target(t.tenantId(), t.projectId()))
                .toList();
        return rolloutService.rollOut(jwt.getSubject(), jwt.getTokenValue(),
                request.catalogId(), targets);
    }

    /**
     * Try a catalog item in the provider's OWN workspace before any customer
     * sees it.
     *
     * <p>Delivers into a sandbox project in the provider's tenant — created on
     * first use — using the same rollout path a customer delivery takes. Not a
     * simulation: a dry run down a different code path would only prove the dry
     * run works, and the failures worth catching here (a persona that loops, a
     * tool that resolves to nothing, a model the workspace cannot reach) are
     * exactly the ones a simulation would skip.
     *
     * <p>Repeatable: re-delivering updates the copy, so fixing the agent and
     * testing again needs no cleanup.
     */
    @PostMapping("/rollout/{catalogId}/test")
    public RolloutService.RolloutResult test(@PathVariable Long catalogId,
                                             @AuthenticationPrincipal Jwt jwt) {
        requireProvider(jwt);
        return rolloutService.test(jwt.getSubject(), jwt.getTokenValue(),
                jwt.getClaimAsString("tenantId"), catalogId);
    }

    /**
     * Which customers hold this catalog item right now.
     *
     * <p>Read live from the services that hold the copies, so a revoked
     * delivery disappears from the list rather than lingering in a ledger.
     */
    @GetMapping("/rollout/{catalogId}/deliveries")
    public List<RolloutService.Holder> deliveries(@PathVariable Long catalogId,
                                                    @AuthenticationPrincipal Jwt jwt) {
        requireProvider(jwt);
        return rolloutService.deliveries(catalogId);
    }

    /**
     * Take one delivered copy back out of a customer's workspace.
     *
     * <p>The counterpart to rollout, and the reason it did not exist before is
     * worth recording: both revoke clients have been written since rollout
     * shipped, and neither had a caller — so an agent could be delivered from
     * the console and only removed with SQL.
     *
     * <p>{@code tenantId} is required rather than derived from the delivery id.
     * A delivery is identified by the pair, and a revoke that trusted an id
     * alone could remove a copy from a customer the provider did not name.
     */
    @DeleteMapping("/rollout/{catalogId}/deliveries/{deliveredId}")
    public Map<String, Object> revoke(@PathVariable Long catalogId,
                                      @PathVariable Long deliveredId,
                                      @RequestParam String tenantId,
                                      @AuthenticationPrincipal Jwt jwt) {
        requireProvider(jwt);
        rolloutService.revoke(jwt.getSubject(), jwt.getTokenValue(), catalogId, tenantId,
                deliveredId);
        return Map.of("revoked", true, "deliveredId", deliveredId, "tenantId", tenantId);
    }

    private void requireProvider(Jwt jwt) {
        if (!PROVIDER_ROLE.equals(jwt.getClaimAsString("role"))) {
            throw CoreException.forbidden("provider_only",
                    "This endpoint is for platform operators");
        }
    }
}
