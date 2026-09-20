package com.intertec.autoops.agent.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The engine's account of a failure, rewritten for the person who asked.
 *
 * <p>A failed automation comes back describing itself in the vocabulary of the
 * thing that ran it: Rundeck node ids, execution ids, the internal project slug
 * that carries the tenant id, and the Java types its data context is built
 * from. The MODEL is given all of it — it is diagnosing, and the detail is
 * occasionally what distinguishes "the credential is missing" from "the
 * credential is wrong". The CUSTOMER is given the part that concerns them.
 *
 * <p>This is not redaction for secrecy. A tenant reading its own tenant id has
 * learned nothing it did not know. It is that a report which says
 * {@code NonZeroResultCode: Result code was 1 + {dataContext=MultiDataContextImpl(...)}}
 * tells an operator nothing they can act on, buries the one line that does, and
 * makes a delivered product read like a stack trace.
 *
 * <p>What survives is what the script itself said. Everything a workflow engine
 * writes about its own disappointment is dropped.
 */
final class CustomerFacingLog {

    /** Lines that are the engine describing its own machinery, never the script's output. */
    private static final List<String> ENGINE_PREFIXES = List.of(
            "result:",
            "failed:",
            "execution failed:",
            "dispatch failed",
            "workflow result:",
            "node failures:",
            "step failures:");

    /** Type and identifier vocabulary that only means something inside the engine. */
    private static final List<String> ENGINE_TOKENS = List.of(
            "nonzeroresultcode",
            "multidatacontextimpl",
            "basedatacontext",
            "contextview",
            "datacontext=");

    /** "Step failed on 1 node: 16c360fd08d2" — the node id is an internal address. */
    private static final Pattern ON_NODES =
            Pattern.compile("\\s*(?:—|-)?\\s*(?:Step )?[Ff]ailed on \\d+ nodes?:[^(\\n]*");

    /** The leading "  | " the engine indents step output with. */
    private static final Pattern LOG_GUTTER = Pattern.compile("^\\s*\\|\\s?");

    private CustomerFacingLog() {
    }

    /**
     * @param raw the engine's log, as the model receives it
     * @return the same events with the engine's own bookkeeping removed, or an
     *         empty string when nothing survived — which the caller should
     *         treat as "no output" rather than printing a blank panel
     */
    static String clean(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        List<String> kept = new ArrayList<>();
        for (String line : raw.split("\\R")) {
            String stripped = LOG_GUTTER.matcher(line).replaceFirst("");
            if (isEngineNoise(stripped)) {
                continue;
            }
            // The node id goes even on a line worth keeping: a step header is
            // useful, the address of the runner that executed it is not.
            String cleaned = ON_NODES.matcher(stripped).replaceAll("").stripTrailing();
            if (!cleaned.isBlank()) {
                kept.add(cleaned);
            }
        }
        return String.join("\n", kept);
    }

    private static boolean isEngineNoise(String line) {
        String lower = line.strip().toLowerCase(Locale.ROOT);
        if (lower.isEmpty()) {
            return true;
        }
        for (String prefix : ENGINE_PREFIXES) {
            if (lower.startsWith(prefix)) {
                return true;
            }
        }
        for (String token : ENGINE_TOKENS) {
            if (lower.contains(token)) {
                return true;
            }
        }
        return false;
    }

    /** Convenience for a header line that may itself name a node. */
    static String cleanHeader(String line) {
        if (line == null) {
            return "";
        }
        Matcher matcher = ON_NODES.matcher(line);
        return matcher.replaceAll("").stripTrailing();
    }
}
