package com.intertec.autoops.alerts.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ingest token is the ONLY thing standing between the public internet and
 * writing alerts into a customer's project. There is no JWT on that path.
 */
class IngestTokenTest {

    private static final String SECRET = "s3cret-signing-key";

    @Test
    @DisplayName("a minted token verifies back to the scope it was minted for")
    void roundTrips() {
        String token = IngestToken.mint(SECRET, "acme", "7", "datadog");
        var scope = IngestToken.verify(SECRET, token).orElseThrow();

        assertThat(scope.tenantId()).isEqualTo("acme");
        assertThat(scope.projectId()).isEqualTo("7");
        assertThat(scope.providerType()).isEqualTo("datadog");
    }

    @Test
    @DisplayName("a tampered PAYLOAD does not verify")
    void tamperedPayloadRejected() {
        // The attack: take your own valid token, swap the tenant, keep the MAC.
        String mine = IngestToken.mint(SECRET, "acme", "7", "datadog");
        String theirPayload = IngestToken.mint(SECRET, "globex", "7", "datadog").split("\\.")[0];
        String forged = theirPayload + "." + mine.split("\\.")[1];

        assertThat(IngestToken.verify(SECRET, forged)).isEmpty();
    }

    @Test
    @DisplayName("a token signed with a different secret does not verify")
    void wrongSecretRejected() {
        String token = IngestToken.mint("some-other-secret", "acme", "7", "datadog");
        assertThat(IngestToken.verify(SECRET, token)).isEmpty();
    }

    @Test
    @DisplayName("rotating the secret invalidates previously issued tokens")
    void rotationRevokesEverything() {
        String old = IngestToken.mint(SECRET, "acme", "7", "datadog");
        assertThat(IngestToken.verify("rotated-secret", old)).isEmpty();
    }

    @Test
    @DisplayName("malformed input is rejected rather than crashing")
    void malformedRejected() {
        assertThat(IngestToken.verify(SECRET, null)).isEmpty();
        assertThat(IngestToken.verify(SECRET, "")).isEmpty();
        assertThat(IngestToken.verify(SECRET, "nodot")).isEmpty();
        assertThat(IngestToken.verify(SECRET, ".")).isEmpty();
        assertThat(IngestToken.verify(SECRET, "abc.")).isEmpty();
        assertThat(IngestToken.verify(SECRET, ".abc")).isEmpty();
        assertThat(IngestToken.verify(SECRET, "!!!.!!!")).isEmpty();
    }

    @Test
    @DisplayName("tokens for different scopes are different")
    void scopesDoNotCollide() {
        String a = IngestToken.mint(SECRET, "acme", "7", "datadog");
        assertThat(a)
                .isNotEqualTo(IngestToken.mint(SECRET, "globex", "7", "datadog"))
                .isNotEqualTo(IngestToken.mint(SECRET, "acme", "8", "datadog"))
                .isNotEqualTo(IngestToken.mint(SECRET, "acme", "7", "grafana"));
    }

    @Test
    @DisplayName("minting is deterministic, so the same project always sees the same token")
    void mintIsStable() {
        // Nothing stores this. If it were not deterministic, a customer's
        // pasted webhook would stop working the next time the page loaded.
        assertThat(IngestToken.mint(SECRET, "acme", "7", "datadog"))
                .isEqualTo(IngestToken.mint(SECRET, "acme", "7", "datadog"));
    }
}
