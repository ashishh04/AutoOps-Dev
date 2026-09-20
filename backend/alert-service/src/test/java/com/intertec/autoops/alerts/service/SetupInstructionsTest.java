package com.intertec.autoops.alerts.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The white-label boundary, at the one place a third party's prose reaches a
 * customer's screen.
 *
 * <p>The fixture is the engine's REAL Airflow markdown, copied verbatim, because
 * a sanitised fixture would prove nothing about the text that actually ships.
 */
class SetupInstructionsTest {

    private static final String URL = "https://app.autoops.example/api/alerts/ingest/airflow";
    private static final String KEY = "tok.sig";

    private static final String REAL_AIRFLOW = """
            💡 For more details on configuring Airflow to send alerts to Keep, refer to the [Keep documentation](https://docs.keephq.dev/providers/documentation/airflow-provider).

            ### 1. Configure Keep's Webhook Credentials
            To send alerts to Keep, set up the webhook URL and API key:

            - **Keep Webhook URL**: http://0.0.0.0:8080/alerts/event/airflow
            - **Keep API Key**: 6c2c1e61-b71c-4e77-9f3a-55be7d816f26

            ### 2. Configure Airflow to Send Alerts to Keep
            ```python
            keep_webhook_url = "http://0.0.0.0:8080/alerts/event/airflow"
            api_key = "6c2c1e61-b71c-4e77-9f3a-55be7d816f26"
            headers = {"X-API-KEY": api_key}
            ```
            """;

    @Test
    @DisplayName("the engine is never named in what a customer sees")
    void engineNeverNamed() {
        String out = SetupInstructions.rewrite(REAL_AIRFLOW, URL, KEY);
        assertThat(SetupInstructions.leaks(out)).isFalse();
        assertThat(out.toLowerCase()).doesNotContain("keephq");
    }

    @Test
    @DisplayName("the engine's URL and live API key are replaced with ours")
    void credentialsReplaced() {
        String out = SetupInstructions.rewrite(REAL_AIRFLOW, URL, KEY);

        assertThat(out).doesNotContain("0.0.0.0:8080");
        assertThat(out).doesNotContain("6c2c1e61-b71c-4e77-9f3a-55be7d816f26");
        assertThat(out).contains(URL).contains(KEY);
    }

    @Test
    @DisplayName("the code sample survives and stays usable")
    void codeSampleSurvives() {
        // Scrubbing must not gut the thing the page exists to show. The
        // variable is renamed rather than dropped.
        String out = SetupInstructions.rewrite(REAL_AIRFLOW, URL, KEY);

        assertThat(out).contains("```python");
        assertThat(out).contains("autoops_webhook_url");
        assertThat(out).contains("X-API-KEY");
    }

    @Test
    @DisplayName("a line naming the engine in an UNANTICIPATED way is dropped, not shipped")
    void unanticipatedMentionsAreDropped() {
        // Deny-by-default: a shape no pattern matches costs a missing line,
        // never a leak. This is the property that survives an upstream rewrite.
        String weird = "Step 1\nSend it to the KeepHQ Cloud relay at odd-host/ingest\nStep 2";
        String out = SetupInstructions.rewrite(weird, URL, KEY);

        assertThat(out).contains("Step 1").contains("Step 2");
        assertThat(SetupInstructions.leaks(out)).isFalse();
    }

    @Test
    @DisplayName("empty or missing markdown is empty output, not a crash")
    void emptyIsSafe() {
        assertThat(SetupInstructions.rewrite(null, URL, KEY)).isEmpty();
        assertThat(SetupInstructions.rewrite("   ", URL, KEY)).isEmpty();
    }

    @Test
    @DisplayName("leaks() is honest about text that still names the engine")
    void leakDetectorWorks() {
        assertThat(SetupInstructions.leaks("send to Keep")).isTrue();
        assertThat(SetupInstructions.leaks("send to KEEP")).isTrue();
        assertThat(SetupInstructions.leaks("send to AutoOps")).isFalse();
    }
}
