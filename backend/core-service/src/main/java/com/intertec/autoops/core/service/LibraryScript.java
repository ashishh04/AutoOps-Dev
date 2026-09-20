package com.intertec.autoops.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intertec.autoops.core.domain.LibraryItem;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Reads a SCRIPT library item's definition and answers the only two questions
 * execution actually has of it: <em>what interpreter runs this</em>, and
 * <em>what is the body</em>.
 *
 * <h2>Why inference is needed at all</h2>
 *
 * The seeded catalog rows carry
 * {@code {"steps":[{"label":"…","value":"<script body>"}]}} — a label and a
 * body, and <b>no step type</b>. {@code ExecutionEngine.parseSteps} therefore
 * resolved their type to the literal {@code "step"}, job-service had no runner
 * registered under that name, and every one of the catalog's scripts failed
 * with "No executor for step type 'step'". The scripts were never broken; the
 * definitions simply never said what they were.
 *
 * <p>Backfilling a {@code type} into those rows was the other option, and it
 * was rejected: the rows arrived by direct SQL from a PowerShell library, they
 * will be reseeded, and a migration that guesses is the same guess as this one
 * — only frozen into data where it cannot be corrected without another
 * migration. Inferring at read time keeps the guess in one testable place, and
 * an explicit {@code type} in the definition always wins over it.
 *
 * <h2>What the inference looks at</h2>
 *
 * A shebang first, because a script that states its interpreter has settled the
 * question. Otherwise the syntactic markers that are near-unambiguous per
 * language. The fallback is {@code script} (bash), which is what an
 * unrecognised body would have been treated as before any of this existed.
 */
public final class LibraryScript {

    private LibraryScript() {
    }

    /** What a library item resolves to: an executable step type and its body. */
    public record Resolved(String stepType, String body) {
    }

    /**
     * PowerShell's give-aways. {@code #Requires} and {@code [CmdletBinding()]}
     * are PowerShell-only; a {@code param(} block at the start of a line is
     * shared with nothing else here.
     */
    private static final Pattern POWERSHELL = Pattern.compile(
            "(?m)^\\s*(#Requires\\b|\\[CmdletBinding\\(|\\[OutputType\\(|param\\s*\\()"
                    + "|\\$ErrorActionPreference\\b|\\bWrite-(Host|Output|Error|Verbose)\\b");

    /** Python's. An {@code import x} / {@code from x import} line, or a def. */
    private static final Pattern PYTHON = Pattern.compile(
            "(?m)^\\s*(import\\s+\\w|from\\s+\\w[\\w.]*\\s+import\\s|def\\s+\\w+\\s*\\(|"
                    + "if\\s+__name__\\s*==)");

    /**
     * The interpreter for a body, ignoring any declared type.
     *
     * <p>Public because the provider script editor wants the same answer when
     * it previews what a body would run as, and two copies of this decision
     * would drift.
     */
    public static String inferType(String body) {
        if (body == null || body.isBlank()) {
            return "script";
        }
        String head = body.stripLeading();
        if (head.startsWith("#!")) {
            String shebang = head.substring(0, head.indexOf('\n') < 0
                    ? head.length() : head.indexOf('\n')).toLowerCase(Locale.ROOT);
            if (shebang.contains("pwsh") || shebang.contains("powershell")) {
                return "powershell";
            }
            if (shebang.contains("python")) {
                return "pyscript";
            }
            return "script";
        }
        if (POWERSHELL.matcher(body).find()) {
            return "powershell";
        }
        if (PYTHON.matcher(body).find()) {
            return "pyscript";
        }
        return "script";
    }

    /**
     * The runnable form of a library item, or empty when its definition holds
     * nothing to run.
     *
     * <p>Only the FIRST step is read. A library SCRIPT is one script — that is
     * what the type means, and every seeded row is shaped that way. A
     * multi-step definition is a job, and a job is built in the designer out of
     * steps that may reference several library scripts.
     */
    public static Optional<Resolved> resolve(LibraryItem item, ObjectMapper objectMapper) {
        if (item == null || item.getDefinition() == null || item.getDefinition().isBlank()) {
            return Optional.empty();
        }
        JsonNode step;
        try {
            JsonNode root = objectMapper.readTree(item.getDefinition());
            JsonNode steps = root.path("steps");
            if (!steps.isArray() || steps.isEmpty()) {
                return Optional.empty();
            }
            step = steps.get(0);
        } catch (Exception ex) {
            return Optional.empty();
        }
        String body = step.path("value").asText(null);
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        // A type the definition states wins outright. Inference exists for the
        // rows that never carried one, not to second-guess the ones that do.
        String declared = step.path("type").asText(step.path("id").asText(""));
        String stepType = declared.isBlank() || "step".equals(declared)
                ? inferType(body)
                : declared.toLowerCase(Locale.ROOT);
        return Optional.of(new Resolved(stepType, body));
    }
}
