package com.intertec.autoops.alerts.web.dto;

import java.util.List;

/**
 * One correlation rule, as its owner sees it.
 *
 * @param label      the customer's own name, with the internal scoping prefix
 *                   stripped — they typed "payments outage", not
 *                   "autoops--acme--7--payments-outage"
 * @param groupBy    what makes two matching alerts the same incident
 * @param windowSeconds how long the grouping window is, as the engine clamped it
 * @param expression the CEL the engine matches on, read-only. Shown rather than
 *                   hidden because "why are these grouped?" is the first
 *                   question anyone asks of a correlated view, and a rule
 *                   nobody can inspect is a grouping nobody trusts. It is not
 *                   editable: a customer supplies values, never an expression.
 */
public record CorrelationRuleView(String id,
                                  String label,
                                  String projectId,
                                  List<String> groupBy,
                                  int windowSeconds,
                                  String expression,
                                  String createdAt) {
}
