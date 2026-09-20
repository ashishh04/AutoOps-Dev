package com.intertec.autoops.alerts.web.dto;

import java.util.List;

/**
 * One kind of monitoring source a customer can connect — Datadog, Grafana,
 * CloudWatch — as AutoOps describes it.
 *
 * <p>The engine's catalog entry carries far more than this: internal ids,
 * OAuth endpoints pointing back at the engine, pull bookkeeping, alert
 * distribution histograms and `provider_metadata`. Only what a connect screen
 * genuinely needs crosses this boundary, for the same reason {@code AlertView}
 * is an allow-list — a field that reaches the browser becomes a contract, and
 * an engine URL in a customer's dev tools ends the white-label.
 *
 * @param fields what the customer must fill in, in the order they should be shown
 * @param scopes what each permission is for, so "why does it need this?" has an
 *               answer on the page rather than in a support thread
 */
public record ProviderTypeView(String type,
                               String displayName,
                               String description,
                               List<String> categories,
                               List<String> tags,
                               boolean supportsWebhook,
                               boolean webhookRequired,
                               boolean comingSoon,
                               List<ProviderFieldView> fields,
                               List<ProviderScopeView> scopes) {
}
