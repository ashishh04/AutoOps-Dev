package com.intertec.autoops.alerts.web.dto;

/**
 * How to make one monitoring source actually send alerts here.
 *
 * @param ingestUrl    the AutoOps endpoint the customer's tool posts to
 * @param ingestKey    the signed token that authorises it, sent as {@code X-API-KEY}
 * @param instructions per-source markdown, rewritten so it names AutoOps and
 *                     points at AutoOps. It is never the engine's text verbatim
 * @param pushOnly     true when the source cannot be polled — it pushes, or
 *                     nothing arrives. For these the instructions ARE the
 *                     integration, so the console must not let someone click
 *                     past them into a success message
 */
public record ProviderSetupView(String ingestUrl,
                                String ingestKey,
                                String instructions,
                                boolean pushOnly) {
}
