package com.intertec.autoops.core.execution;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a person sees when a Dify workflow finishes.
 *
 * <p>These cases come from a real run: a research workflow whose outputs were
 * written to the log as raw JSON, so the screen showed
 * {@code {"final_report":"<h2>4. Technical...\\n <p><em>...} with the report
 * buried inside its own escaping.
 */
class RunOutputsTest {

    @Test
    void picksTheProseReportOverTheExportFormats() {
        String outputs = """
                {"final_report":"# Market Report\\n\\nThe market grew 11%.",
                 "pdf_ready_markdown":"# Market Report (pdf)",
                 "word_ready_html":"<h1>Market Report</h1>"}
                """;

        String readable = RunOutputs.readable(outputs);

        assertThat(readable).isEqualTo("# Market Report\n\nThe market grew 11%.");
        // The export variants are the same content for a converter, not for a
        // reader — showing all three triples the noise.
        assertThat(readable).doesNotContain("pdf").doesNotContain("<h1>");
    }

    @Test
    void stripsHtmlWhenHtmlIsAllThereIs() {
        String outputs = """
                {"word_ready_html":"<h2>Findings</h2><p>Adoption is <strong>rising</strong>.</p>\
                <ul><li>Vendor A</li><li>Vendor B</li></ul>"}
                """;

        String readable = RunOutputs.readable(outputs);

        assertThat(readable).contains("Findings").contains("Adoption is rising.");
        assertThat(readable).contains("- Vendor A").contains("- Vendor B");
        assertThat(readable).doesNotContain("<").doesNotContain(">");
    }

    @Test
    void decodesTheEntitiesThatMadeItOnScreen() {
        assertThat(RunOutputs.readable("{\"report\":\"Competitor &amp; Vendor &lt;Landscape&gt;\"}"))
                .isEqualTo("Competitor & Vendor <Landscape>");
    }

    @Test
    void unescapesLiteralNewlinesLeftInTheString() {
        // The screen showed "\\n" as two characters, mid-sentence, throughout.
        String readable = RunOutputs.clean("Section one.\\n\\nSection two.");

        assertThat(readable).isEqualTo("Section one.\n\nSection two.");
    }

    /**
     * One unfamiliar field is shown bare — there is nothing to disambiguate.
     * Several ARE labelled (see below): two differently-named outputs are two
     * different things, and silently dropping one would lose content.
     */
    @Test
    void asingleUnfamiliarFieldIsShownWithoutALabel() {
        String outputs = """
                {"analysis":"a much longer piece of substantive prose"}
                """;

        assertThat(RunOutputs.readable(outputs))
                .isEqualTo("a much longer piece of substantive prose");
    }

    /** The export variants are dropped first, so one prose field can remain. */
    @Test
    void dropsExportsThenShowsTheSingleRemainingField() {
        String outputs = """
                {"analysis":"the substantive prose","pdf_ready_markdown":"# same, for a converter"}
                """;

        assertThat(RunOutputs.readable(outputs)).isEqualTo("the substantive prose");
    }

    @Test
    void labelsEachFieldWhenSeveralAreGenuinelyDifferent() {
        String outputs = """
                {"market_summary":"Growing steadily across regions.",
                 "risk_summary":"Concentration risk in two vendors."}
                """;

        String readable = RunOutputs.readable(outputs);

        assertThat(readable).contains("## risk summary").contains("## market summary");
        assertThat(readable).contains("Concentration risk in two vendors.");
    }

    @Test
    void aNonJsonBodyIsShownRatherThanDiscarded() {
        // A parse failure must not lose the answer.
        assertThat(RunOutputs.readable("just some text")).isEqualTo("just some text");
    }

    @Test
    void nothingToShowIsNull() {
        assertThat(RunOutputs.readable(null)).isNull();
        assertThat(RunOutputs.readable("   ")).isNull();
        assertThat(RunOutputs.readable("{}")).isNull();
        assertThat(RunOutputs.readable("{\"final_report\":\"\"}")).isNull();
    }

    @Test
    void collapsesRunsOfBlankLines() {
        assertThat(RunOutputs.clean("A\n\n\n\n\nB")).isEqualTo("A\n\nB");
    }
}
