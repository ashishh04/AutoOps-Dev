package com.intertec.autoops.voice.ratelimit;

import com.intertec.autoops.voice.config.VoiceProperties;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sliding-window cap on how many voice sessions get handed out, per client IP
 * and in total.
 *
 * <p>Every session this service mints costs real ElevenLabs credits and the
 * endpoint is anonymous by design — the landing page is public. So the limiter,
 * not authentication, is what stands between a scripted client and the billing
 * account. It is deliberately in-memory: a per-replica cap is enough to make
 * draining the account slow and obvious, and it keeps the landing page's voice
 * button off the critical path of Redis.
 */
@Component
public class SessionRateLimiter {

    /**
     * Ceiling on how many distinct IPs we track. Past this we stop admitting
     * new ones for the rest of the window rather than growing the map without
     * bound — an attacker rotating source addresses cannot turn the limiter
     * itself into the memory leak.
     */
    static final int MAX_TRACKED_IPS = 10_000;

    private final VoiceProperties properties;
    private final Clock clock;

    private final Map<String, Deque<Long>> hitsByIp = new ConcurrentHashMap<>();
    private final Deque<Long> globalHits = new ArrayDeque<>();

    public SessionRateLimiter(VoiceProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public enum Decision {
        ALLOWED,
        /** This IP has had its share of the window. */
        PER_IP_EXCEEDED,
        /** The whole deployment has hit its window budget. */
        GLOBAL_EXCEEDED
    }

    /**
     * Records an attempt and says whether it may proceed. A rejected attempt is
     * NOT counted against the caller, so a client that keeps retrying while
     * blocked still recovers exactly one window after its last allowed call.
     */
    public Decision tryAcquire(String clientIp) {
        VoiceProperties.RateLimit limits = properties.getRateLimit();
        if (!limits.isEnabled()) {
            return Decision.ALLOWED;
        }

        long now = clock.millis();
        long windowStart = now - limits.getWindow().toMillis();
        String key = clientIp == null || clientIp.isBlank() ? "unknown" : clientIp;

        synchronized (this) {
            evictExpired(globalHits, windowStart);
            if (globalHits.size() >= limits.getGlobal()) {
                return Decision.GLOBAL_EXCEEDED;
            }

            purgeIdleIps(windowStart);

            Deque<Long> hits = hitsByIp.get(key);
            if (hits == null) {
                if (hitsByIp.size() >= MAX_TRACKED_IPS) {
                    return Decision.PER_IP_EXCEEDED;
                }
                hits = new ArrayDeque<>();
                hitsByIp.put(key, hits);
            }
            evictExpired(hits, windowStart);
            if (hits.size() >= limits.getPerIp()) {
                return Decision.PER_IP_EXCEEDED;
            }

            hits.addLast(now);
            globalHits.addLast(now);
            return Decision.ALLOWED;
        }
    }

    /**
     * Hands back the slot a preceding {@link #tryAcquire} took, for a session
     * that in the end was never minted.
     *
     * <p>This limiter exists to protect a bill. A call that failed upstream
     * bought nothing and spent nothing, so charging the visitor for it is not
     * caution — it is a wrong answer. Without this, an outage at the vendor
     * reaches the visitor as <em>"you have started several conversations
     * already"</em> after five attempts that all failed, which is both false
     * and the opposite of a clue: it blames them for our fault and buries the
     * real error for a full window.
     *
     * <p>The most recent hit is the one removed, because the slot being handed
     * back is the one just taken. Releasing a slot that was never acquired is
     * harmless and does nothing — the deque is simply empty.
     */
    public void release(String clientIp) {
        if (!properties.getRateLimit().isEnabled()) {
            return;
        }
        String key = clientIp == null || clientIp.isBlank() ? "unknown" : clientIp;
        synchronized (this) {
            Deque<Long> hits = hitsByIp.get(key);
            if (hits != null) {
                hits.pollLast();
                if (hits.isEmpty()) {
                    hitsByIp.remove(key);
                }
            }
            globalHits.pollLast();
        }
    }

    /** Seconds until the caller's oldest hit falls out of the window. */
    public long retryAfterSeconds() {
        return Math.max(1, properties.getRateLimit().getWindow().toSeconds());
    }

    private static void evictExpired(Deque<Long> hits, long windowStart) {
        while (!hits.isEmpty() && hits.peekFirst() <= windowStart) {
            hits.pollFirst();
        }
    }

    /** Drops IPs whose every hit has aged out, so the map tracks live traffic only. */
    private void purgeIdleIps(long windowStart) {
        Iterator<Map.Entry<String, Deque<Long>>> it = hitsByIp.entrySet().iterator();
        while (it.hasNext()) {
            Deque<Long> hits = it.next().getValue();
            evictExpired(hits, windowStart);
            if (hits.isEmpty()) {
                it.remove();
            }
        }
    }

    /** Test seam: how many IPs are currently being tracked. */
    int trackedIpCount() {
        return hitsByIp.size();
    }
}
