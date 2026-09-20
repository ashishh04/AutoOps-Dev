package com.intertec.autoops.core.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a workflow's outputs into something a person can read.
 *
 * <p>A workflow returns its outputs as a JSON object keyed by the end node's
 * output variables. Writing that object into the run log verbatim is what
 * produced the screen this class exists to fix: a wall of
 * {@code {"final_report":"<h2>4. Technical...\n  <p><em>This section..."} —
 * JSON escaping, HTML tags and literal {@code \n} all rendered as text, with
 * the actual report buried inside it.
 *
 * <p>Two decisions do most of the work:
 *
 * <ul>
 *   <li><b>Pick one output, not all of them.</b> A report workflow commonly
 *       emits the same content three times — markdown, PDF-ready markdown,
 *       Word-ready HTML. Showing all three triples the noise and buries the
 *       one a human wants. The prose variant wins and the export formats are
 *       dropped; they exist for a downstream converter, not for a reader.</li>
 *   <li><b>Strip HTML when that is all there is.</b> Falling back to a tag
 *       soup is better than falling back to nothing, but only just, so the
 *       tags come out and the entities are decoded.</li>
 * </ul>
 */
public final class RunOutputs {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Output names that are export artefacts rather than the thing to read.
     * Matched as substrings because naming is inconsistent across workflows —
     * {@code pdf_ready_markdown}, {@code word_ready_html}, {@code docx} all
     * mean "for a converter, not for a person".
     */
    private static final List<String> EXPORT_HINTS =
            List.of("pdf", "docx", "word", "html", "base64", "file", "attachment");

    /** Checked in order; the first present, non-blank one is used. */
    private static final List<String> PREFERRED =
            List.of("final_report", "report", "answer", "result", "output", "text", "summary");

    private RunOutputs() {
    }

    /**
     * @param outputsJson the run's declared outputs as JSON, or null
     * @return readable text, or null when there is genuinely nothing to show
     */
    public static String readable(String outputsJson) {
        if (outputsJson == null || outputsJson.isBlank()) {
            return null;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(outputsJson);
        } catch (Exception ex) {
            // Not JSON at all. It is still probably the answer, so show it
            // rather than discarding it over a parse failure.
            return outputsJson.trim();
        }
        if (root.isTextual()) {
            return clean(root.asText());
        }
        if (!root.isObject()) {
            return clean(root.toString());
        }

        Map<String, String> texts = new LinkedHashMap<>();
        root.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            String text = value.isTextual() ? value.asText()
                    : value.isNull() || value.isMissingNode() ? null : value.toString();
            if (text != null && !text.isBlank()) {
                texts.put(entry.getKey(), text);
            }
        });
        if (texts.isEmpty()) {
            return null;
        }

        // A named favourite wins outright.
        for (String key : PREFERRED) {
            String hit = texts.get(key);
            if (hit != null && !isExport(key)) {
                return clean(hit);
            }
        }

        // Otherwise the longest non-export field: on a report workflow the
        // substantive output is invariably the biggest one.
        List<Map.Entry<String, String>> readable = new ArrayList<>(texts.entrySet().stream()
                .filter(entry -> !isExport(entry.getKey()))
                .toList());
        if (!readable.isEmpty()) {
            readable.sort((a, b) -> Integer.compare(b.getValue().length(), a.getValue().length()));
            if (readable.size() == 1) {
                return clean(readable.get(0).getValue());
            }
            StringBuilder out = new StringBuilder();
            for (Map.Entry<String, String> entry : readable) {
                out.append("## ").append(title(entry.getKey())).append("\n\n")
                        .append(clean(entry.getValue())).append("\n\n");
            }
            return out.toString().trim();
        }

        // Everything was an export format. Better a stripped-down version of
        // one than an empty result.
        return clean(texts.values().iterator().next());
    }

    private static boolean isExport(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        return EXPORT_HINTS.stream().anyMatch(lower::contains);
    }

    private static String title(String key) {
        return key.replace('_', ' ').trim();
    }

    /** Unescapes, de-tags if needed, and normalises the blank lines. */
    static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw;

        // A JSON string that arrived still escaped — \n as two characters.
        if (text.contains("\\n") && !text.contains("\n")) {
            text = text.replace("\\n", "\n").replace("\\t", "\t").replace("\\\"", "\"");
        }

        if (looksLikeHtml(text)) {
            text = text.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", "");
            // Block-level tags become line breaks so paragraphs survive as
            // paragraphs instead of collapsing into one run-on line.
            text = text.replaceAll("(?i)<br\\s*/?>", "\n");
            text = text.replaceAll("(?i)</(p|div|li|tr|h[1-6]|table|ul|ol)>", "\n");
            text = text.replaceAll("(?i)<li[^>]*>", "- ");
            text = text.replaceAll("<[^>]+>", "");
        }

        text = text.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'");

        // Three or more blank lines read as a gap, not as structure.
        text = text.replaceAll("\n{3,}", "\n\n");
        return text.strip();
    }

    private static boolean looksLikeHtml(String text) {
        return text.matches("(?s).*<(p|div|h[1-6]|table|ul|ol|li|br|span|strong|em)\\b[^>]*>.*");
    }
}
