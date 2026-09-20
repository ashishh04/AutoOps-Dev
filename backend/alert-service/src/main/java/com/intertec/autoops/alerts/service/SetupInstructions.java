package com.intertec.autoops.alerts.service;

import java.util.Arrays;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Rewrites the engine's setup guide into AutoOps's own.
 *
 * <p>The engine ships genuinely good per-source instructions — an Airflow
 * callback, a Grafana contact point, the exact shape each tool wants. Throwing
 * that away and writing 124 guides by hand is not realistic, and a customer
 * with no instructions is a customer who cannot connect anything.
 *
 * <p>But that text is saturated with the engine: its product name, its URL, a
 * live API key, and a link to its public docs. Rendering it as-is would end the
 * white-label in a single screen — the customer learns what the engine is, gets
 * its address, and gets a working credential for it.
 *
 * <p>So it is rewritten, and the rewrite is <b>deny-by-default</b>: after the
 * substitutions, any line still mentioning the engine is dropped. A missed
 * pattern costs a missing line, never a leak. {@link #leaks(String)} is the
 * assertion that keeps that true, and it is checked here at runtime rather than
 * only in a test, because the input is a third party's release notes.
 */
public final class SetupInstructions {

    /** The engine's name, in the one place it is allowed to appear in this codebase. */
    private static final String ENGINE = "keep";

    /** Its ingest endpoint, in any host form the engine reports. */
    private static final Pattern ENGINE_URL =
            Pattern.compile("https?://[^\\s\"'`)\\]]*?/alerts/event/[A-Za-z0-9_-]*");

    /** A UUID — the engine embeds a live API key as one. */
    private static final Pattern UUID = Pattern.compile(
            "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b");

    /** Markdown links to the engine's docs. */
    private static final Pattern ENGINE_LINK =
            Pattern.compile("\\[[^\\]]*\\]\\(https?://[^)]*" + ENGINE + "[^)]*\\)",
                    Pattern.CASE_INSENSITIVE);

    private SetupInstructions() {
    }

    /**
     * @param raw       the engine's markdown for this source type
     * @param ingestUrl the AutoOps endpoint to send alerts to
     * @param ingestKey the signed token that authorises it
     */
    public static String rewrite(String raw, String ingestUrl, String ingestKey) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String text = raw;

        // Order matters: kill links before bare names, or the link TEXT gets
        // renamed and the URL inside it survives the later filter.
        text = ENGINE_LINK.matcher(text).replaceAll("the documentation for this tool");
        text = ENGINE_URL.matcher(text).replaceAll(java.util.regex.Matcher.quoteReplacement(ingestUrl));
        text = UUID.matcher(text).replaceAll(java.util.regex.Matcher.quoteReplacement(ingestKey));
        text = text.replaceAll("(?i)\\b" + ENGINE + "'s\\b", "AutoOps's");
        text = text.replaceAll("(?i)\\b" + ENGINE + "\\b", "AutoOps");
        // keep_webhook_url, KEEP_API_KEY, X-KEEP-... inside code samples.
        text = text.replaceAll("(?i)" + ENGINE + "([_-])", "autoops$1");

        // Deny-by-default. Anything still naming the engine did not match a
        // pattern above, which means it is a shape nobody anticipated - drop
        // the line rather than ship it.
        return Arrays.stream(text.split("\n", -1))
                .filter(line -> !line.toLowerCase().contains(ENGINE))
                .collect(Collectors.joining("\n"))
                .strip();
    }

    /** Whether text still names the engine. The guard, used on the way out. */
    public static boolean leaks(String text) {
        return text != null && text.toLowerCase().contains(ENGINE);
    }
}
