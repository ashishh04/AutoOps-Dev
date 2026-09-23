package com.intertec.autoops.alerts.web.dto;

import java.util.List;

/**
 * One incident in the list.
 *
 * @param correlatedBy the rule that grouped these alerts. Surfaced because
 *                     "why are these one thing?" is the first question anyone
 *                     asks of a correlated view, and a grouping nobody can
 *                     explain is one nobody trusts
 * @param investigated whether an investigation has already been run and stored,
 *                     so the list can show it without re-running anything
 */
public record IncidentSummaryView(String id,
                                  String name,
                                  String summary,
                                  String severity,
                                  String status,
                                  String assignee,
                                  int alertCount,
                                  List<String> services,
                                  List<String> sources,
                                  String startedAt,
                                  String lastSeenAt,
                                  String correlatedBy,
                                  boolean investigated) {
}
