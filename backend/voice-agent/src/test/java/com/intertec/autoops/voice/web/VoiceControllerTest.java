package com.intertec.autoops.voice.web;

import com.intertec.autoops.voice.config.VoiceProperties;
import com.intertec.autoops.voice.elevenlabs.ElevenLabsClient;
import com.intertec.autoops.voice.elevenlabs.ElevenLabsException;
import com.intertec.autoops.voice.ratelimit.SessionRateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(VoiceController.class)
@EnableConfigurationProperties(VoiceProperties.class)
@TestPropertySource(properties = {
        "autoops.voice.api-key=sk_test_key",
        "autoops.voice.agent-id=agent_abc123",
        "autoops.voice.agent-name=Aegis-01"
})
class VoiceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ElevenLabsClient elevenLabs;

    @MockitoBean
    private SessionRateLimiter rateLimiter;

    @Test
    void configReportsEnabledWithoutRevealingCredentials() throws Exception {
        mockMvc.perform(get("/api/voice/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.agentName").value("Aegis-01"))
                // The key and the agent id must never appear in a public response.
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("sk_test_key"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("agent_abc123"))));
    }

    @Test
    void sessionReturnsTheSignedUrlAndItsLifetime() throws Exception {
        given(rateLimiter.tryAcquire(any())).willReturn(SessionRateLimiter.Decision.ALLOWED);
        given(elevenLabs.signedUrl()).willReturn("wss://api.elevenlabs.io/v1/convai/conversation?token=xyz");

        mockMvc.perform(post("/api/voice/session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signedUrl")
                        .value("wss://api.elevenlabs.io/v1/convai/conversation?token=xyz"))
                .andExpect(jsonPath("$.expiresInSeconds").value(900));
    }

    @Test
    void aRateLimitedVisitorNeverReachesElevenLabs() throws Exception {
        given(rateLimiter.tryAcquire(any())).willReturn(SessionRateLimiter.Decision.PER_IP_EXCEEDED);
        given(rateLimiter.retryAfterSeconds()).willReturn(600L);

        mockMvc.perform(post("/api/voice/session"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "600"))
                .andExpect(jsonPath("$.error").value("rate_limited"));

        // The whole point of the cap: no upstream call, so no credits spent.
        verify(elevenLabs, never()).signedUrl();
    }

    @Test
    void aGlobalCapSaysCapacityRatherThanBlamingTheVisitor() throws Exception {
        given(rateLimiter.tryAcquire(any())).willReturn(SessionRateLimiter.Decision.GLOBAL_EXCEEDED);
        given(rateLimiter.retryAfterSeconds()).willReturn(600L);

        mockMvc.perform(post("/api/voice/session"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("at capacity")));
    }

    @Test
    void theClientIpFromXForwardedForIsWhatGetsRateLimited() throws Exception {
        given(rateLimiter.tryAcquire("203.0.113.7")).willReturn(SessionRateLimiter.Decision.ALLOWED);
        given(elevenLabs.signedUrl()).willReturn("wss://example.invalid/session");

        mockMvc.perform(post("/api/voice/session")
                        .header("X-Forwarded-For", "203.0.113.7, 10.0.0.2, 10.0.0.3"))
                .andExpect(status().isOk());

        verify(rateLimiter).tryAcquire("203.0.113.7");
    }

    @Test
    void anUpstreamFailureBecomesACleanJsonError() throws Exception {
        given(rateLimiter.tryAcquire(any())).willReturn(SessionRateLimiter.Decision.ALLOWED);
        given(elevenLabs.signedUrl()).willThrow(new ElevenLabsException(
                HttpStatus.SERVICE_UNAVAILABLE, "The voice agent is not available right now"));

        mockMvc.perform(post("/api/voice/session"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("voice_unavailable"))
                .andExpect(jsonPath("$.message").value("The voice agent is not available right now"));
    }

    @Test
    void anUpstreamFailureHandsTheVisitorTheirSlotBack() throws Exception {
        // Nothing was minted and nothing was spent, so nothing should be
        // charged. Counting these turned a bad API key into a ten-minute
        // lockout whose message blamed the visitor for conversations they
        // never had, and buried the real error for the length of the window.
        given(rateLimiter.tryAcquire("203.0.113.7")).willReturn(SessionRateLimiter.Decision.ALLOWED);
        given(elevenLabs.signedUrl()).willThrow(new ElevenLabsException(
                HttpStatus.SERVICE_UNAVAILABLE, "The voice agent is not available right now"));

        mockMvc.perform(post("/api/voice/session").header("X-Forwarded-For", "203.0.113.7"))
                .andExpect(status().isServiceUnavailable());

        verify(rateLimiter).release("203.0.113.7");
    }

    @Test
    void aSessionThatWasActuallyMintedKeepsItsSlot() throws Exception {
        given(rateLimiter.tryAcquire(any())).willReturn(SessionRateLimiter.Decision.ALLOWED);
        given(elevenLabs.signedUrl()).willReturn("wss://example.invalid/session");

        mockMvc.perform(post("/api/voice/session")).andExpect(status().isOk());

        verify(rateLimiter, never()).release(any());
    }
}
