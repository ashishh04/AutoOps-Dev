package com.intertec.autoops.subscription.service;

import com.intertec.autoops.subscription.config.SubscriptionProperties;
import com.intertec.autoops.subscription.domain.Feature;
import com.intertec.autoops.subscription.domain.LimitType;
import com.intertec.autoops.subscription.domain.Subscription;
import com.intertec.autoops.subscription.domain.SubscriptionStatus;
import com.intertec.autoops.subscription.repo.SubscriptionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * The entitlement decision auth-service (and later the gateway) relies on.
 *
 * <p>Decisions are cached briefly in Redis (evicted on any subscription
 * change). Cache failures fall back to the database — the DECISION itself is
 * what fails closed for callers, never this service silently answering wrong.
 */
@Service
public class EntitlementService {

    private static final Logger log = LoggerFactory.getLogger(EntitlementService.class);
    private static final String CACHE_PREFIX = "sub:entitled:";

    private final SubscriptionRepository subscriptionRepository;
    private final StringRedisTemplate redisTemplate;
    private final SubscriptionProperties properties;
    /** Nullable: slice tests have no MeterRegistry; prod wires Prometheus. */
    private final MeterRegistry meterRegistry;

    public EntitlementService(SubscriptionRepository subscriptionRepository,
                              StringRedisTemplate redisTemplate,
                              SubscriptionProperties properties,
                              ObjectProvider<MeterRegistry> meterRegistry) {
        this.subscriptionRepository = subscriptionRepository;
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.meterRegistry = meterRegistry.getIfAvailable();
    }

    public record Decision(boolean entitled, String reason) {
    }

    /** Quota answer: {@code max == null} while entitled means unlimited. */
    public record QuotaDecision(boolean entitled, String reason, Integer max, Long remaining) {
    }

    @Transactional(readOnly = true)
    public Decision check(String tenantId, String featureCode) {
        if (tenantId == null || tenantId.isBlank()) {
            return counted(new Decision(false, "missing_tenant"));
        }
        String cacheKey = CACHE_PREFIX + tenantId + ":" + (featureCode == null ? "-" : featureCode);
        String cached = cacheGet(cacheKey);
        if (cached != null) {
            int sep = cached.indexOf(':');
            return counted(new Decision("1".equals(cached.substring(0, sep)), cached.substring(sep + 1)));
        }

        Decision decision = decide(tenantId, featureCode);
        cachePut(cacheKey, (decision.entitled() ? "1" : "0") + ":" + decision.reason());
        return counted(decision);
    }

    /**
     * {@code entitlement_checks_total} Prometheus counter — {@code reason} is a
     * closed snake_case set, so cardinality stays bounded. Alert on spikes of
     * denials (a misconfigured plan gates real user actions).
     */
    private Decision counted(Decision decision) {
        count(decision.entitled(), decision.reason());
        return decision;
    }

    private QuotaDecision countedQuota(QuotaDecision decision) {
        count(decision.entitled(), decision.reason());
        return decision;
    }

    private void count(boolean entitled, String reason) {
        if (meterRegistry != null) {
            meterRegistry.counter("entitlement_checks_total",
                    "entitled", String.valueOf(entitled),
                    "reason", reason).increment();
        }
    }

    /**
     * Numeric quota gate: the OWNING service counts its resources and sends
     * {@code current}; this service compares against the plan's ceiling.
     * Never cached — the answer depends on the caller-supplied count, and
     * creations are rare compared to feature checks.
     */
    @Transactional(readOnly = true)
    public QuotaDecision quota(String tenantId, LimitType limit, long current) {
        if (tenantId == null || tenantId.isBlank()) {
            return countedQuota(new QuotaDecision(false, "missing_tenant", null, null));
        }
        Subscription subscription = subscriptionRepository.findByTenantId(tenantId).orElse(null);
        String block = statusBlock(subscription);
        if (block != null) {
            return countedQuota(new QuotaDecision(false, block, null, null));
        }
        if (subscription == null) {
            // Only reachable with billing unenforced: no subscription means no
            // plan, so there is no ceiling to compare against. Unlimited rather
            // than zero — the gate above already decided this tenant is allowed.
            return countedQuota(new QuotaDecision(true, "ok", null, null));
        }
        Integer max = limit.maxFor(subscription.getPlan());
        if (max == null) {
            return countedQuota(new QuotaDecision(true, "ok", null, null)); // unlimited
        }
        if (current >= max) {
            return countedQuota(new QuotaDecision(false, "quota_exceeded", max, 0L));
        }
        return countedQuota(new QuotaDecision(true, "ok", max, max - current));
    }

    private Decision decide(String tenantId, String featureCode) {
        Subscription subscription = subscriptionRepository.findByTenantId(tenantId).orElse(null);
        String block = statusBlock(subscription);
        if (block != null) {
            return new Decision(false, block);
        }
        if (subscription == null) {
            // As above: unenforced billing and no subscription row. There is no
            // plan to read a feature list from, and refusing here would put the
            // paywall back that the gate just took down.
            return new Decision(true, "ok");
        }
        if (featureCode != null && !featureCode.isBlank()) {
            Feature feature = Feature.fromCode(featureCode);
            if (feature == null) {
                return new Decision(false, "unknown_feature");
            }
            if (!subscription.getPlan().getFeatures().contains(feature)) {
                return new Decision(false, "feature_not_in_plan");
            }
        }
        return new Decision(true, "ok");
    }

    /**
     * The subscription-status gate shared by feature and quota decisions:
     * a denial reason, or {@code null} when the subscription is live.
     */
    private String statusBlock(Subscription subscription) {
        // While no real payment provider is configured, the billing lifecycle
        // cannot deny anyone: there is no way to pay, so "your trial has ended"
        // is a locked door with no key. Includes the no-subscription case,
        // because a tenant whose trial was never started is in exactly the same
        // position — see SubscriptionProperties.isBillingEnforced().
        if (!properties.isBillingEnforced()) {
            return null;
        }
        if (subscription == null) {
            return "no_subscription";
        }
        Instant now = Instant.now();
        SubscriptionStatus status = subscription.getStatus();
        if (status == SubscriptionStatus.TRIALING && subscription.getTrialEndsAt() != null
                && now.isAfter(subscription.getTrialEndsAt())) {
            return "trial_expired";
        }
        if (subscription.isCancelAtPeriodEnd() && now.isAfter(subscription.getCurrentPeriodEnd())) {
            return "subscription_canceled";
        }
        if (status == SubscriptionStatus.CANCELED || status == SubscriptionStatus.EXPIRED
                || status == SubscriptionStatus.PAST_DUE) {
            return "subscription_" + status.name().toLowerCase();
        }
        return null;
    }

    /** Called on every subscription mutation so cached decisions never go stale. */
    public void evictTenant(String tenantId) {
        try {
            var keys = redisTemplate.keys(CACHE_PREFIX + tenantId + ":*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        } catch (Exception ex) {
            log.debug("Entitlement cache evict unavailable: {}", ex.getMessage());
        }
    }

    private String cacheGet(String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (Exception ex) {
            return null; // cache miss on Redis outage — DB answers
        }
    }

    private void cachePut(String key, String value) {
        try {
            redisTemplate.opsForValue().set(key, value, properties.getEntitlementCacheTtl());
        } catch (Exception ex) {
            log.debug("Entitlement cache write unavailable: {}", ex.getMessage());
        }
    }
}
