package com.intertec.autoops.alerts.web.dto;

import java.util.List;

/**
 * One incident — a set of alerts the engine decided are the same problem.
 *
 * <p>Same rule as {@link AlertView}: the engine's own shape stops at this
 * service. Its rule ids, merge bookkeeping and candidate/predicted flags are
 * correlation internals and say nothing a console should render.
 */
public record IncidentView(String id,
                           String name,
                           String summary,
                           String severity,
                           String status,
                           int alertCount,
                           List<String> services,
                           String startedAt,
                           String lastSeenAt) {
}
