package com.intertec.autoops.plugin.provider;

import com.intertec.autoops.plugin.domain.LifecycleEvent;
import com.intertec.autoops.plugin.domain.TargetType;
import com.intertec.autoops.plugin.provider.gmail.GmailPlugin;
import com.intertec.autoops.plugin.provider.outlook.OutlookPlugin;
import com.intertec.autoops.plugin.provider.support.EmailRenderer;
import com.intertec.autoops.plugin.provider.support.SmtpSender;
import com.intertec.autoops.plugin.spi.DeliveryResult;
import com.intertec.autoops.plugin.spi.NotificationMessage;
import com.intertec.autoops.plugin.spi.PluginContext;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The email half — Gmail and Outlook — which never touches HTTP.
 *
 * <p>Only a live mailbox can prove a sign-in, so what is pinned here is
 * everything that does not need one: the rendered mail, and the two
 * classifications that decide whether a channel gets parked.
 */
class EmailProviderTest {

    private final EmailRenderer renderer = new EmailRenderer();

    private PluginContext context(Map<String, String> config) {
        return new PluginContext("tenant-1", 7L, "Ops email", config);
    }

    private NotificationMessage message(LifecycleEvent event, String targetName, String detail) {
        return new NotificationMessage(
                "tenant-1", TargetType.JOB, 42L, targetName, event,
                event == LifecycleEvent.MISSED ? null : 1234L,
                9L, "Platform", "schedule", detail,
                Instant.parse("2026-08-08T09:00:00Z"),
                event == LifecycleEvent.MISSED ? null : Duration.ofSeconds(95),
                event == LifecycleEvent.MISSED ? "" : "https://console.example.com/app/runs/1234",
                // Null: let the message take the event's own severity, which is
                // what everything that RUNS does. Only an alert overrides it.
                null);
    }

    // ---------------- rendering ----------------

    /** The marker leads because mobile clients truncate the subject line. */
    @Test
    void subjectLeadsWithTheSeverityMarker() {
        assertThat(renderer.subject(message(LifecycleEvent.FAILED, "Nightly backup", null)))
                .isEqualTo("[Alert] Job \"Nightly backup\" failed");
        assertThat(renderer.subject(message(LifecycleEvent.STALLED, "Nightly backup", null)))
                .startsWith("[Warning] ");
        // INFO carries no marker at all — a prefix on every routine success
        // trains people to filter the whole channel out.
        assertThat(renderer.subject(message(LifecycleEvent.SUCCEEDED, "Nightly backup", null)))
                .isEqualTo("Job \"Nightly backup\" succeeded");
    }

    /**
     * Job names and error text are tenant input rendered into HTML that lands
     * in a mailbox.
     */
    @Test
    void htmlEscapesTenantSuppliedText() {
        String html = renderer.html(
                message(LifecycleEvent.FAILED, "<script>alert(1)</script>", "a & b <tag>"));

        assertThat(html).doesNotContain("<script>");
        assertThat(html).contains("&lt;script&gt;");
        assertThat(html).contains("a &amp; b &lt;tag&gt;");
    }

    /** A MISSED event has no run behind it, so those rows must be absent. */
    @Test
    void aMissedEventRendersWithoutRunDurationOrLink() {
        NotificationMessage missed = message(LifecycleEvent.MISSED, "Nightly backup", "0 3 * * *");

        assertThat(renderer.text(missed))
                .doesNotContain("Run:")
                .doesNotContain("Duration:")
                .doesNotContain("Open in AutoOps:")
                .contains("0 3 * * *");
        assertThat(renderer.html(missed)).doesNotContain("Open in AutoOps");
    }

    /** Pagers and watches show the text alternative, not the HTML. */
    @Test
    void textAlternativeCarriesTheSameFacts() {
        String text = renderer.text(message(LifecycleEvent.FAILED, "Nightly backup", "exit code 1"));

        assertThat(text)
                .contains("Job \"Nightly backup\" failed")
                .contains("Project: Platform")
                .contains("Run: #1234")
                .contains("Duration: 1m 35s")
                .contains("exit code 1");
    }

    // ---------------- guards ----------------

    /**
     * Both plugins must refuse before opening a session. Signing in only to
     * discover there is nobody to send to would burn an auth attempt against a
     * provider that rate-limits them.
     */
    @Test
    void bothEmailPluginsRefuseWithoutRecipients() {
        DeliveryResult gmail = new GmailPlugin(new SmtpSender(), renderer).verify(context(Map.of(
                "username", "ops@example.com", "appPassword", "abcd efgh ijkl mnop")));
        DeliveryResult outlook = new OutlookPlugin(new SmtpSender(), renderer).verify(context(Map.of(
                "username", "ops@example.com", "password", "hunter2")));

        for (DeliveryResult result : new DeliveryResult[]{gmail, outlook}) {
            assertThat(result.ok()).isFalse();
            assertThat(result.retryable()).isFalse();
            assertThat(result.detail()).contains("No recipients");
        }
    }

    /**
     * A refused socket is transient. Classifying it permanently would park the
     * channel on a blip and silence the tenant until someone noticed.
     */
    @Test
    void aRefusedConnectionIsRetryableNotPermanent() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        DeliveryResult result = new SmtpSender().verify(new SmtpSender.SmtpSettings(
                "127.0.0.1", closedPort, "ops@example.com", "hunter2",
                "ops@example.com", "AutoOps", "auth hint"));

        assertThat(result.ok()).isFalse();
        assertThat(result.retryable()).isTrue();
        // The auth hint must NOT appear — this was not an auth failure.
        assertThat(result.detail()).doesNotContain("auth hint");
    }
}
