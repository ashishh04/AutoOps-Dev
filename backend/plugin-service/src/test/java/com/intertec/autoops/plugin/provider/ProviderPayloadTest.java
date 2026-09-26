package com.intertec.autoops.plugin.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intertec.autoops.plugin.domain.LifecycleEvent;
import com.intertec.autoops.plugin.domain.TargetType;
import com.intertec.autoops.plugin.provider.github.GitHubPlugin;
import com.intertec.autoops.plugin.provider.slack.SlackPlugin;
import com.intertec.autoops.plugin.provider.support.OutboundHttp;
import com.intertec.autoops.plugin.provider.teams.TeamsPlugin;
import com.intertec.autoops.plugin.provider.webhook.GenericWebhookPlugin;
import com.intertec.autoops.plugin.spi.DeliveryResult;
import com.intertec.autoops.plugin.spi.NotificationMessage;
import com.intertec.autoops.plugin.spi.PluginContext;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What each provider actually puts on the wire.
 *
 * <p>{@code PluginRegistryTest} proves the beans exist and their forms render;
 * nothing proved the request itself. These plugins are only ever exercised
 * against a live third party, so a payload that a provider silently rejects —
 * a card in the retired MessageCard shape, a signature computed over different
 * bytes than were sent — would show up as an unexplained failure in a tenant's
 * channel and nowhere else.
 *
 * <p>A real loopback server rather than a mocked client, for the reason given
 * on {@code OutboundHttpTest}: the interesting mistakes happen below the API.
 */
class ProviderPayloadTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private String method;
    private String path;
    private String body;
    private Map<String, String> headers;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            method = exchange.getRequestMethod();
            path = exchange.getRequestURI().getPath();
            body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            headers = new java.util.HashMap<>();
            exchange.getRequestHeaders()
                    .forEach((name, values) -> headers.put(name.toLowerCase(), values.get(0)));
            byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private OutboundHttp http() {
        return new OutboundHttp(Duration.ofSeconds(2), Duration.ofSeconds(2));
    }

    private PluginContext context(Map<String, String> config) {
        return new PluginContext("tenant-1", 7L, "Ops alerts", config);
    }

    private JsonNode sent() throws IOException {
        return JSON.readTree(body);
    }

    /** A FAILED job with everything populated — the busiest card each renders. */
    private NotificationMessage failedJob() {
        return new NotificationMessage(
                "tenant-1",
                TargetType.JOB,
                42L,
                "Nightly backup",
                LifecycleEvent.FAILED,
                1234L,
                9L,
                "Platform",
                "schedule",
                "exit code 1",
                Instant.parse("2026-08-08T09:00:00Z"),
                Duration.ofSeconds(95),
                "https://console.example.com/app/runs/1234",
                null);
    }

    // ---------------- Slack ----------------

    @Test
    void slackVerifyPostsAPlainTextMessage() throws IOException {
        DeliveryResult result = new SlackPlugin(http())
                .verify(context(Map.of("webhookUrl", baseUrl() + "/services/T/B/x")));

        assertThat(result.ok()).isTrue();
        assertThat(method).isEqualTo("POST");
        assertThat(sent().get("text").asText())
                .contains("AutoOps is connected")
                .contains("Ops alerts");
    }

    /**
     * The plain {@code text} field is not decoration: without it a blocks-only
     * message arrives as "sent an attachment" in the mobile push.
     */
    @Test
    void slackSendCarriesTextTitleAndTheSeverityColour() throws IOException {
        new SlackPlugin(http()).send(
                context(Map.of("webhookUrl", baseUrl() + "/services/T/B/x")), failedJob());

        JsonNode payload = sent();
        assertThat(payload.get("text").asText()).isEqualTo("Job \"Nightly backup\" failed");
        assertThat(payload.get("attachments").get(0).get("color").asText())
                .isEqualTo("#e01e5a");
        assertThat(payload.get("attachments").get(0).get("blocks")).isNotEmpty();
    }

    @Test
    void slackAppliesTheIdentityOverridesOnlyWhenSet() throws IOException {
        new SlackPlugin(http()).send(context(Map.of(
                "webhookUrl", baseUrl() + "/services/T/B/x",
                "username", "AutoOps Bot",
                "iconEmoji", ":rotating_light:")), failedJob());

        assertThat(sent().get("username").asText()).isEqualTo("AutoOps Bot");
        assertThat(sent().get("icon_emoji").asText()).isEqualTo(":rotating_light:");

        new SlackPlugin(http()).send(
                context(Map.of("webhookUrl", baseUrl() + "/services/T/B/x")), failedJob());

        assertThat(sent().has("username")).isFalse();
        assertThat(sent().has("icon_emoji")).isFalse();
    }

    // ---------------- Teams ----------------

    /**
     * A bare Adaptive Card posts 202 and renders nothing at all — it has to be
     * wrapped as an attachment on a message, and pinned to schema 1.4.
     */
    @Test
    void teamsWrapsTheCardAsAnAttachmentOnAMessage() throws IOException {
        DeliveryResult result = new TeamsPlugin(http())
                .verify(context(Map.of("webhookUrl", baseUrl() + "/workflows/x")));

        assertThat(result.ok()).isTrue();
        JsonNode payload = sent();
        assertThat(payload.get("type").asText()).isEqualTo("message");
        JsonNode attachment = payload.get("attachments").get(0);
        assertThat(attachment.get("contentType").asText())
                .isEqualTo("application/vnd.microsoft.card.adaptive");
        JsonNode card = attachment.get("content");
        assertThat(card.get("type").asText()).isEqualTo("AdaptiveCard");
        assertThat(card.get("version").asText()).isEqualTo("1.4");
    }

    @Test
    void teamsSendRendersTitleFactsAndTheConsoleLink() throws IOException {
        new TeamsPlugin(http())
                .send(context(Map.of("webhookUrl", baseUrl() + "/workflows/x")), failedJob());

        JsonNode card = sent().get("attachments").get(0).get("content");
        assertThat(card.get("body").get(0).get("text").asText())
                .isEqualTo("Job \"Nightly backup\" failed");
        // CRITICAL maps to Teams' "attention" container style.
        assertThat(card.get("body").get(0).get("color").asText()).isEqualTo("attention");
        assertThat(card.toString()).contains("Nightly backup").contains("Platform");
        assertThat(card.get("actions").get(0).get("url").asText())
                .isEqualTo("https://console.example.com/app/runs/1234");
    }

    // ---------------- GitHub ----------------

    /** A connection test must not leave a stray issue behind. */
    @Test
    void githubVerifyReadsTheRepositoryAndCreatesNothing() {
        DeliveryResult result = new GitHubPlugin(http()).verify(context(Map.of(
                "token", "github_pat_x",
                "repository", "intertec/autoops-runbooks",
                "apiBaseUrl", baseUrl())));

        assertThat(result.ok()).isTrue();
        assertThat(method).isEqualTo("GET");
        assertThat(path).isEqualTo("/repos/intertec/autoops-runbooks");
        assertThat(body).isEmpty();
        assertThat(headers).containsEntry("authorization", "Bearer github_pat_x");
        assertThat(headers).containsEntry("x-github-api-version", "2022-11-28");
    }

    @Test
    void githubSendOpensAnIssueWithLabelsAndAssignees() throws IOException {
        new GitHubPlugin(http()).send(context(Map.of(
                "token", "github_pat_x",
                "repository", "intertec/autoops-runbooks",
                "apiBaseUrl", baseUrl(),
                "labels", "incident, autoops",
                "assignees", "octocat")), failedJob());

        assertThat(method).isEqualTo("POST");
        assertThat(path).isEqualTo("/repos/intertec/autoops-runbooks/issues");
        JsonNode issue = sent();
        assertThat(issue.get("title").asText()).isEqualTo("Job \"Nightly backup\" failed");
        assertThat(issue.get("body").asText()).contains("exit code 1").contains("Platform");
        assertThat(issue.get("labels").toString()).isEqualTo("[\"incident\",\"autoops\"]");
        assertThat(issue.get("assignees").toString()).isEqualTo("[\"octocat\"]");
    }

    /** People paste the browser URL rather than owner/repo. */
    @Test
    void githubAcceptsAPastedRepositoryUrl() {
        new GitHubPlugin(http()).verify(context(Map.of(
                "token", "github_pat_x",
                "repository", "https://github.com/intertec/autoops-runbooks.git",
                "apiBaseUrl", baseUrl())));

        assertThat(path).isEqualTo("/repos/intertec/autoops-runbooks");
    }

    // ---------------- Generic webhook ----------------

    /**
     * The signature has to cover the exact bytes that went out. If the JSON
     * were handed to the client as an object — or re-serialised as a JSON
     * string — the receiver would recompute a different HMAC and reject every
     * event, with nothing on our side looking wrong.
     */
    @Test
    void webhookSignsTheExactBytesItSends() throws Exception {
        String secret = "s3cret";

        new GenericWebhookPlugin(http(), JSON).send(context(Map.of(
                "url", baseUrl() + "/autoops",
                "signingSecret", secret)), failedJob());

        // Raw JSON object, not a quoted string containing JSON.
        assertThat(body).startsWith("{").doesNotStartWith("\"{");
        assertThat(sent().get("event").asText()).isEqualTo("FAILED");
        assertThat(sent().get("runId").asLong()).isEqualTo(1234L);
        assertThat(sent().get("durationSeconds").asLong()).isEqualTo(95L);

        String timestamp = headers.get("x-autoops-timestamp");
        assertThat(timestamp).isNotNull();
        assertThat(headers.get("x-autoops-signature")).isEqualTo(hmac(secret, timestamp + "." + body));
        assertThat(headers).containsEntry("x-autoops-event", "FAILED");
    }

    @Test
    void webhookVerifyPostsAConnectionTestRatherThanAFakeRun() throws IOException {
        new GenericWebhookPlugin(http(), JSON)
                .verify(context(Map.of("url", baseUrl() + "/autoops")));

        assertThat(sent().get("type").asText()).isEqualTo("connection.test");
        assertThat(headers).containsEntry("x-autoops-event", "connection.test");
        // Unsigned when no secret is configured, rather than signed with none.
        assertThat(headers).doesNotContainKey("x-autoops-signature");
    }

    @Test
    void webhookSendsTheExtraHeaderWhenBothHalvesAreSet() {
        new GenericWebhookPlugin(http(), JSON).verify(context(Map.of(
                "url", baseUrl() + "/autoops",
                "headerName", "Authorization",
                "headerValue", "Token abc")));

        assertThat(headers).containsEntry("authorization", "Token abc");
    }

    private static String hmac(String secret, String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(Character.forDigit((b >> 4) & 0xf, 16))
                    .append(Character.forDigit(b & 0xf, 16));
        }
        return hex.toString();
    }
}
