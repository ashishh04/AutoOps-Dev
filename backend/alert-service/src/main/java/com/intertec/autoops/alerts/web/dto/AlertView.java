package com.intertec.autoops.alerts.web.dto;

import java.util.List;

/**
 * One alert, as AutoOps says it.
 *
 * <p>This type exists so that the engine's JSON never reaches a browser. That
 * payload carries {@code providerId}, {@code providerType}, {@code apiKeyRef},
 * {@code enriched_fields}, dedup bookkeeping and internal ids — vocabulary that
 * would name the engine in the network tab of anyone who opened dev tools, and
 * would make its schema our public contract by accident. Mapping is cheap;
 * un-shipping a leaked field is not.
 *
 * @param source     where the alert came FROM (the monitoring tool), not what
 *                   is holding it
 * @param projectId  null until ingest stamps it — see {@code TenantScope}
 */
public record AlertView(String fingerprint,
                        String name,
                        String description,
                        String severity,
                        String status,
                        List<String> source,
                        String service,
                        String environment,
                        String receivedAt,
                        String startedAt,
                        String url,
                        String projectId) {
}
