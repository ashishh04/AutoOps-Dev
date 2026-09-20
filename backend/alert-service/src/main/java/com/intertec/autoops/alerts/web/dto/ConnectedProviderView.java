package com.intertec.autoops.alerts.web.dto;

/**
 * A monitoring source this project has already connected.
 *
 * <p>{@code label} is the customer's name for it with the internal scoping
 * prefix stripped — they typed "Production Datadog", not
 * "autoops-acme-7-Production Datadog".
 *
 * @param lastAlertAt the only honest health signal a connect screen can show.
 *                    "Connected" means credentials were accepted once; whether
 *                    anything is actually arriving is a different question, and
 *                    it is the one an operator is really asking.
 */
public record ConnectedProviderView(String id,
                                    String type,
                                    String label,
                                    String projectId,
                                    String lastAlertAt) {
}
