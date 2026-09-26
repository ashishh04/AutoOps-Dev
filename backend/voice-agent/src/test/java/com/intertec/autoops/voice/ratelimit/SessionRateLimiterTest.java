package com.intertec.autoops.voice.ratelimit;

import com.intertec.autoops.voice.config.VoiceProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class SessionRateLimiterTest {

    /** A clock the test moves by hand, so windows expire without sleeping. */
    static class TickingClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }

    private static VoiceProperties props(int perIp, int global, Duration window) {
        VoiceProperties properties = new VoiceProperties();
        properties.getRateLimit().setPerIp(perIp);
        properties.getRateLimit().setGlobal(global);
        properties.getRateLimit().setWindow(window);
        return properties;
    }

    @Test
    void allowsUpToThePerIpCapThenRefuses() {
        SessionRateLimiter limiter = new SessionRateLimiter(
                props(3, 100, Duration.ofMinutes(10)), new TickingClock());

        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("1.2.3.4")).isEqualTo(SessionRateLimiter.Decision.ALLOWED);
        }
        assertThat(limiter.tryAcquire("1.2.3.4"))
                .isEqualTo(SessionRateLimiter.Decision.PER_IP_EXCEEDED);
    }

    @Test
    void oneIpsExhaustionDoesNotBlockAnother() {
        SessionRateLimiter limiter = new SessionRateLimiter(
                props(1, 100, Duration.ofMinutes(10)), new TickingClock());

        assertThat(limiter.tryAcquire("1.1.1.1")).isEqualTo(SessionRateLimiter.Decision.ALLOWED);
        assertThat(limiter.tryAcquire("1.1.1.1")).isEqualTo(SessionRateLimiter.Decision.PER_IP_EXCEEDED);
        assertThat(limiter.tryAcquire("2.2.2.2")).isEqualTo(SessionRateLimiter.Decision.ALLOWED);
    }

    @Test
    void theWindowSlidesSoABlockedClientRecovers() {
        TickingClock clock = new TickingClock();
        SessionRateLimiter limiter = new SessionRateLimiter(
                props(1, 100, Duration.ofMinutes(10)), clock);

        assertThat(limiter.tryAcquire("1.2.3.4")).isEqualTo(SessionRateLimiter.Decision.ALLOWED);
        clock.advance(Duration.ofMinutes(9));
        assertThat(limiter.tryAcquire("1.2.3.4")).isEqualTo(SessionRateLimiter.Decision.PER_IP_EXCEEDED);
        clock.advance(Duration.ofMinutes(2));
        assertThat(limiter.tryAcquire("1.2.3.4")).isEqualTo(SessionRateLimiter.Decision.ALLOWED);
    }

    @Test
    void theGlobalCapStopsADistributedDrain() {
        SessionRateLimiter limiter = new SessionRateLimiter(
                props(10, 2, Duration.ofMinutes(10)), new TickingClock());

        assertThat(limiter.tryAcquire("1.1.1.1")).isEqualTo(SessionRateLimiter.Decision.ALLOWED);
        assertThat(limiter.tryAcquire("2.2.2.2")).isEqualTo(SessionRateLimiter.Decision.ALLOWED);
        // A third, entirely fresh address still cannot spend a credit.
        assertThat(limiter.tryAcquire("3.3.3.3")).isEqualTo(SessionRateLimiter.Decision.GLOBAL_EXCEEDED);
    }

    @Test
    void refusedAttemptsAreNotCountedAgainstTheCaller() {
        TickingClock clock = new TickingClock();
        SessionRateLimiter limiter = new SessionRateLimiter(
                props(1, 100, Duration.ofMinutes(10)), clock);

        assertThat(limiter.tryAcquire("1.2.3.4")).isEqualTo(SessionRateLimiter.Decision.ALLOWED);
        // Hammering while blocked must not keep pushing the recovery time out.
        for (int i = 0; i < 5; i++) {
            clock.advance(Duration.ofMinutes(1));
            assertThat(limiter.tryAcquire("1.2.3.4")).isEqualTo(SessionRateLimiter.Decision.PER_IP_EXCEEDED);
        }
        clock.advance(Duration.ofMinutes(6));
        assertThat(limiter.tryAcquire("1.2.3.4")).isEqualTo(SessionRateLimiter.Decision.ALLOWED);
    }

    @Test
    void aSessionThatWasNeverMintedDoesNotCostTheVisitorASlot() {
        // The limiter protects a bill. An upstream failure bought nothing, so
        // charging the visitor for it turns one outage at the vendor into a
        // full-window lockout — and the visitor is told they have "started
        // several conversations already" having started none.
        TickingClock clock = new TickingClock();
        SessionRateLimiter limiter = new SessionRateLimiter(
                props(2, 100, Duration.ofMinutes(10)), clock);

        for (int i = 0; i < 20; i++) {
            assertThat(limiter.tryAcquire("1.2.3.4")).isEqualTo(SessionRateLimiter.Decision.ALLOWED);
            limiter.release("1.2.3.4");
        }

        // Two real sessions are still available afterwards, and the second is
        // still the last one.
        assertThat(limiter.tryAcquire("1.2.3.4")).isEqualTo(SessionRateLimiter.Decision.ALLOWED);
        assertThat(limiter.tryAcquire("1.2.3.4")).isEqualTo(SessionRateLimiter.Decision.ALLOWED);
        assertThat(limiter.tryAcquire("1.2.3.4")).isEqualTo(SessionRateLimiter.Decision.PER_IP_EXCEEDED);
    }

    @Test
    void releasingGivesTheGlobalBudgetBackToo() {
        // Otherwise a vendor outage drains the deployment-wide cap and takes
        // every other visitor down with it, long after the vendor recovers.
        TickingClock clock = new TickingClock();
        SessionRateLimiter limiter = new SessionRateLimiter(
                props(50, 2, Duration.ofMinutes(10)), clock);

        assertThat(limiter.tryAcquire("1.1.1.1")).isEqualTo(SessionRateLimiter.Decision.ALLOWED);
        limiter.release("1.1.1.1");
        assertThat(limiter.tryAcquire("2.2.2.2")).isEqualTo(SessionRateLimiter.Decision.ALLOWED);
        assertThat(limiter.tryAcquire("3.3.3.3")).isEqualTo(SessionRateLimiter.Decision.ALLOWED);
        assertThat(limiter.tryAcquire("4.4.4.4")).isEqualTo(SessionRateLimiter.Decision.GLOBAL_EXCEEDED);
    }

    @Test
    void releasingASlotThatWasNeverTakenChangesNothing() {
        // A refused attempt never acquired anything, so the release that
        // follows a failure must not hand back somebody else's slot.
        TickingClock clock = new TickingClock();
        SessionRateLimiter limiter = new SessionRateLimiter(
                props(1, 10, Duration.ofMinutes(10)), clock);

        limiter.release("9.9.9.9");
        assertThat(limiter.trackedIpCount()).isZero();

        assertThat(limiter.tryAcquire("1.2.3.4")).isEqualTo(SessionRateLimiter.Decision.ALLOWED);
        limiter.release("9.9.9.9");
        assertThat(limiter.tryAcquire("1.2.3.4")).isEqualTo(SessionRateLimiter.Decision.PER_IP_EXCEEDED);
    }

    @Test
    void idleIpsAreForgottenSoTheMapTracksLiveTrafficOnly() {
        TickingClock clock = new TickingClock();
        SessionRateLimiter limiter = new SessionRateLimiter(
                props(5, 1000, Duration.ofMinutes(10)), clock);

        for (int i = 0; i < 50; i++) {
            limiter.tryAcquire("10.0.0." + i);
        }
        assertThat(limiter.trackedIpCount()).isEqualTo(50);

        clock.advance(Duration.ofMinutes(11));
        limiter.tryAcquire("192.168.0.1");
        assertThat(limiter.trackedIpCount()).isEqualTo(1);
    }

    @Test
    void rotatingSourceAddressesCannotGrowTheMapWithoutBound() {
        SessionRateLimiter limiter = new SessionRateLimiter(
                props(1, Integer.MAX_VALUE, Duration.ofMinutes(10)), new TickingClock());

        for (int i = 0; i < SessionRateLimiter.MAX_TRACKED_IPS + 500; i++) {
            limiter.tryAcquire("10." + (i / 65536) + "." + ((i / 256) % 256) + "." + (i % 256));
        }
        assertThat(limiter.trackedIpCount()).isEqualTo(SessionRateLimiter.MAX_TRACKED_IPS);
    }

    @Test
    void disablingTheLimiterAllowsEverything() {
        VoiceProperties properties = props(1, 1, Duration.ofMinutes(10));
        properties.getRateLimit().setEnabled(false);
        SessionRateLimiter limiter = new SessionRateLimiter(properties, new TickingClock());

        for (int i = 0; i < 20; i++) {
            assertThat(limiter.tryAcquire("1.2.3.4")).isEqualTo(SessionRateLimiter.Decision.ALLOWED);
        }
    }

    @Test
    void aMissingClientAddressIsBucketedRatherThanCrashing() {
        SessionRateLimiter limiter = new SessionRateLimiter(
                props(1, 100, Duration.ofMinutes(10)), new TickingClock());

        assertThat(limiter.tryAcquire(null)).isEqualTo(SessionRateLimiter.Decision.ALLOWED);
        assertThat(limiter.tryAcquire("")).isEqualTo(SessionRateLimiter.Decision.PER_IP_EXCEEDED);
    }
}
