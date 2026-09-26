package com.intertec.autoops.voice.web;

import com.intertec.autoops.voice.config.VoiceProperties;
import com.intertec.autoops.voice.elevenlabs.ElevenLabsClient;
import com.intertec.autoops.voice.elevenlabs.ElevenLabsException;
import com.intertec.autoops.voice.ratelimit.ClientIp;
import com.intertec.autoops.voice.ratelimit.SessionRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public API behind the "Tap to speak with Aegis-01" button on the landing
 * page. Anonymous by design — visitors have no account yet — so everything here
 * is rate-limited and hands out nothing longer-lived than a 15-minute session.
 */
@RestController
@RequestMapping("/api/voice")
public class VoiceController {

    private static final Logger log = LoggerFactory.getLogger(VoiceController.class);

    private final VoiceProperties properties;
    private final ElevenLabsClient elevenLabs;
    private final SessionRateLimiter rateLimiter;

    public VoiceController(VoiceProperties properties, ElevenLabsClient elevenLabs,
                           SessionRateLimiter rateLimiter) {
        this.properties = properties;
        this.elevenLabs = elevenLabs;
        this.rateLimiter = rateLimiter;
    }

    /**
     * Lets the page decide whether to render the talk button at all. Carries no
     * secret: deliberately not the agent id either, so the agent can stay
     * private to signed URLs.
     */
    public record VoiceConfig(boolean enabled, String agentName) {
    }

    public record VoiceSession(String signedUrl, int expiresInSeconds) {
    }

    public record VoiceError(String error, String message) {
    }

    @GetMapping("/config")
    public VoiceConfig config() {
        return new VoiceConfig(properties.isConfigured(), properties.getAgentName());
    }

    /**
     * Mints one signed WebSocket URL. POST because it consumes an ElevenLabs
     * quota slot — it is not a safe, cacheable read.
     */
    @PostMapping("/session")
    public ResponseEntity<?> session(HttpServletRequest request) {
        String clientIp = ClientIp.of(request);
        SessionRateLimiter.Decision decision = rateLimiter.tryAcquire(clientIp);
        if (decision != SessionRateLimiter.Decision.ALLOWED) {
            log.warn("Voice session refused for {} ({})", clientIp, decision);
            String message = decision == SessionRateLimiter.Decision.GLOBAL_EXCEEDED
                    ? "Aegis-01 is at capacity right now — please try again shortly"
                    : "You have started several conversations already — please try again shortly";
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(rateLimiter.retryAfterSeconds()))
                    .body(new VoiceError("rate_limited", message));
        }

        String signedUrl;
        try {
            signedUrl = elevenLabs.signedUrl();
        } catch (RuntimeException e) {
            // Nothing was minted, so nothing was spent, so the visitor keeps
            // the slot. Counting a failure against them turns one upstream
            // fault into a ten-minute lockout whose message — "you have
            // started several conversations already" — is simply untrue: they
            // started none, and it hides the error that actually happened.
            rateLimiter.release(clientIp);
            throw e;
        }
        return ResponseEntity.ok(new VoiceSession(signedUrl, ElevenLabsClient.SIGNED_URL_TTL_SECONDS));
    }

    @ExceptionHandler(ElevenLabsException.class)
    public ResponseEntity<VoiceError> handleUpstream(ElevenLabsException e) {
        return ResponseEntity.status(e.getStatus())
                .body(new VoiceError("voice_unavailable", e.getMessage()));
    }
}
