package com.intertec.autoops.subscription.service;

import com.intertec.autoops.subscription.config.SubscriptionProperties;
import com.intertec.autoops.subscription.domain.Feature;
import com.intertec.autoops.subscription.domain.LimitType;
import com.intertec.autoops.subscription.domain.Plan;
import com.intertec.autoops.subscription.domain.PlanCode;
import com.intertec.autoops.subscription.domain.Payment;
import com.intertec.autoops.subscription.domain.PaymentStatus;
import com.intertec.autoops.subscription.domain.Subscription;
import com.intertec.autoops.subscription.domain.SubscriptionEventType;
import com.intertec.autoops.subscription.domain.SubscriptionStatus;
import com.intertec.autoops.subscription.exception.SubscriptionException;
import com.intertec.autoops.subscription.payment.StubPaymentProvider;
import com.intertec.autoops.subscription.repo.PaymentRepository;
import com.intertec.autoops.subscription.repo.PlanRepository;
import com.intertec.autoops.subscription.repo.SubscriptionAuditLogRepository;
import com.intertec.autoops.subscription.repo.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Subscription lifecycle + entitlement decisions against H2 with real commit
 * semantics (NOT_SUPPORTED disables the test-managed transaction).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({SubscriptionService.class, EntitlementService.class, AuditService.class,
        PaymentService.class, StubPaymentProvider.class})
@EnableConfigurationProperties(SubscriptionProperties.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SubscriptionServiceTest {

    private static final String TENANT = "acme-corp-cafe0123";
    private static final String ACTOR = "admin@acme.io";

    @Autowired
    private SubscriptionService subscriptionService;
    @Autowired
    private EntitlementService entitlementService;
    @Autowired
    private SubscriptionRepository subscriptionRepository;
    @Autowired
    private PlanRepository planRepository;
    @Autowired
    private SubscriptionAuditLogRepository auditLogRepository;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private PaymentService paymentService;
    @Autowired
    private SubscriptionProperties properties;
    /** Redis is unavailable in the slice — the cache must fail open to the DB. */
    @MockBean
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void seedPlans() {
        properties.setPaymentStubFails(false);
        // Reset, because SubscriptionProperties is a shared bean and a test
        // that switches the provider would otherwise leave every later test
        // asking for a Stripe bean that does not exist.
        properties.setPaymentProvider("stub");
        // Explicit, because the DEFAULT is now derived: with the stub payment
        // provider, billing is unenforced (nobody can pay, so a paywall is a
        // locked door with no key). Every test below is about the lifecycle
        // RULES, which still exist — so they turn enforcement on and say so.
        properties.setEnforceBilling(true);
        paymentRepository.deleteAll();
        auditLogRepository.deleteAll();
        subscriptionRepository.deleteAll();
        planRepository.deleteAll();
        // Limits mirror the V1..V5 catalog: projects/automations/jobs/cloud —
        // Starter 3/5/5/2, Team 10/15/10/5, Enterprise 30/30/30/10 (no tier
        // is unlimited since V4).
        plan(PlanCode.STARTER, 1, EnumSet.of(Feature.CORE_AUTOMATION), 3, 5, 5, 2);
        plan(PlanCode.TEAM, 2, EnumSet.of(Feature.CORE_AUTOMATION, Feature.AUDIT_LOG, Feature.API_ACCESS), 10, 15, 10, 5);
        plan(PlanCode.ENTERPRISE, 4, EnumSet.allOf(Feature.class), 30, 30, 30, 10);
    }

    private void plan(PlanCode code, int order, EnumSet<Feature> features,
                      Integer maxProjects, Integer maxAutomations,
                      Integer maxJobs, Integer maxCloudIntegrations) {
        Plan p = new Plan();
        p.setCode(code);
        p.setName(code.name());
        p.setDescription(code.name() + " plan");
        p.setPriceMonthly(49 * order);
        p.setTrialDays(14);
        p.setActive(true);
        p.setSortOrder(order);
        p.setFeatures(features);
        p.setMaxProjects(maxProjects);
        p.setMaxAutomations(maxAutomations);
        p.setMaxJobs(maxJobs);
        p.setMaxCloudIntegrations(maxCloudIntegrations);
        planRepository.save(p);
    }

    @Test
    void firstSubscribeStartsTrial() {
        Subscription sub = subscriptionService.subscribe(TENANT, PlanCode.TEAM, ACTOR);

        assertEquals(SubscriptionStatus.TRIALING, sub.getStatus());
        assertNotNull(sub.getTrialEndsAt());
        assertTrue(sub.getTrialEndsAt().isAfter(Instant.now().plus(Duration.ofDays(13))));
        assertEquals(PlanCode.TEAM, sub.getPlan().getCode());
    }

    @Test
    void planChangeKeepsPhaseAndSwitchesPlan() {
        subscriptionService.subscribe(TENANT, PlanCode.STARTER, ACTOR);
        Subscription sub = subscriptionService.subscribe(TENANT, PlanCode.ENTERPRISE, ACTOR);

        assertEquals(PlanCode.ENTERPRISE, sub.getPlan().getCode());
        assertEquals(SubscriptionStatus.TRIALING, sub.getStatus());
        assertEquals(1, subscriptionRepository.count(), "still one subscription per tenant");
    }

    @Test
    void cancelKeepsAccessUntilPeriodEnd() {
        subscriptionService.subscribe(TENANT, PlanCode.TEAM, ACTOR);
        Subscription canceled = subscriptionService.cancel(TENANT, ACTOR);

        assertTrue(canceled.isCancelAtPeriodEnd());
        // Still inside the paid/trial period => still entitled.
        assertTrue(entitlementService.check(TENANT, null).entitled());
    }

    @Test
    void expiredTrialIsNotEntitled() {
        subscriptionService.subscribe(TENANT, PlanCode.TEAM, ACTOR);
        Subscription sub = subscriptionRepository.findByTenantId(TENANT).orElseThrow();
        sub.setTrialEndsAt(Instant.now().minus(Duration.ofDays(1)));
        subscriptionRepository.save(sub);

        EntitlementService.Decision decision = entitlementService.check(TENANT, null);
        assertFalse(decision.entitled());
        assertEquals("trial_expired", decision.reason());
        // And the surfaced status reflects it.
        assertEquals(SubscriptionStatus.EXPIRED,
                subscriptionService.findCurrent(TENANT).orElseThrow().getStatus());
    }

    // ---------------- billing that cannot be paid is not enforced ----------

    @Test
    void anExpiredTrialStillWorksWhileNoRealPaymentProviderExists() {
        // The demo failure. With paymentProvider=stub there is no way to buy
        // anything, so denying an expired trial locks a customer out of a
        // product they cannot purchase — and the only remedy was editing the
        // database.
        properties.setEnforceBilling(null);
        properties.setPaymentProvider("stub");
        subscriptionService.subscribe(TENANT, PlanCode.TEAM, ACTOR);
        Subscription sub = subscriptionRepository.findByTenantId(TENANT).orElseThrow();
        sub.setTrialEndsAt(Instant.now().minus(Duration.ofDays(1)));
        subscriptionRepository.save(sub);

        assertTrue(entitlementService.check(TENANT, null).entitled());
    }

    @Test
    void theSameTrialIsDeniedOnceARealProviderIsConfigured() {
        // The other half, and the reason this is derived rather than a flag:
        // wiring a real provider turns the paywall on by itself, with nobody
        // having to remember a setting.
        properties.setEnforceBilling(null);
        properties.setPaymentProvider("stripe");
        subscriptionService.subscribe(TENANT, PlanCode.TEAM, ACTOR);
        Subscription sub = subscriptionRepository.findByTenantId(TENANT).orElseThrow();
        sub.setTrialEndsAt(Instant.now().minus(Duration.ofDays(1)));
        subscriptionRepository.save(sub);

        EntitlementService.Decision decision = entitlementService.check(TENANT, null);
        assertFalse(decision.entitled());
        assertEquals("trial_expired", decision.reason());
    }

    @Test
    void aTenantWithNoSubscriptionAtAllIsAllowedWhileBillingIsUnenforced() {
        // Signup starts the trial as a NON-FATAL follow-up call, so a tenant
        // can legitimately exist with no subscription row. Under an unenforced
        // paywall that must not be the one thing that still blocks them.
        properties.setEnforceBilling(false);

        assertTrue(entitlementService.check("tenant-with-nothing", null).entitled());
        assertTrue(entitlementService
                .quota("tenant-with-nothing", LimitType.MAX_PROJECTS, 999).entitled());
    }

    @Test
    void quotasAreStillEnforcedWhenBillingIsNot() {
        // Plan limits are PRODUCT behaviour, not payment. Turning the paywall
        // off must not turn the product into an unlimited one for a tenant who
        // does hold a plan.
        properties.setEnforceBilling(false);
        subscriptionService.subscribe(TENANT, PlanCode.TEAM, ACTOR);

        EntitlementService.QuotaDecision decision =
                entitlementService.quota(TENANT, LimitType.MAX_PROJECTS, 99999);

        assertFalse(decision.entitled());
        assertEquals("quota_exceeded", decision.reason());
    }

    @Test
    void featuresOutsideThePlanAreStillRefusedWhenBillingIsNot() {
        properties.setEnforceBilling(false);
        subscriptionService.subscribe(TENANT, PlanCode.STARTER, ACTOR);

        EntitlementService.Decision sso = entitlementService.check(TENANT, "SSO");

        assertFalse(sso.entitled());
        assertEquals("feature_not_in_plan", sso.reason());
    }

    @Test
    void resubscribeAfterExpiryReactivates() {
        subscriptionService.subscribe(TENANT, PlanCode.TEAM, ACTOR);
        Subscription sub = subscriptionRepository.findByTenantId(TENANT).orElseThrow();
        sub.setTrialEndsAt(Instant.now().minus(Duration.ofDays(1)));
        subscriptionRepository.save(sub);

        Subscription reactivated = subscriptionService.subscribe(TENANT, PlanCode.TEAM, ACTOR);
        assertEquals(SubscriptionStatus.ACTIVE, reactivated.getStatus());
        assertTrue(entitlementService.check(TENANT, null).entitled());
        assertTrue(auditLogRepository.findByTenantIdOrderByCreatedAtDesc(TENANT).stream()
                .anyMatch(e -> e.getEventType() == SubscriptionEventType.REACTIVATED));
    }

    @Test
    void featureEntitlementFollowsThePlan() {
        subscriptionService.subscribe(TENANT, PlanCode.TEAM, ACTOR);

        assertTrue(entitlementService.check(TENANT, "AUDIT_LOG").entitled());
        EntitlementService.Decision sso = entitlementService.check(TENANT, "SSO");
        assertFalse(sso.entitled());
        assertEquals("feature_not_in_plan", sso.reason());

        subscriptionService.subscribe(TENANT, PlanCode.ENTERPRISE, ACTOR);
        assertTrue(entitlementService.check(TENANT, "SSO").entitled());
    }

    @Test
    void noSubscriptionMeansNotEntitled() {
        EntitlementService.Decision decision = entitlementService.check("ghost-tenant", null);
        assertFalse(decision.entitled());
        assertEquals("no_subscription", decision.reason());
    }

    @Test
    void unknownPlanIsRejected() {
        assertThrows(SubscriptionException.class,
                () -> subscriptionService.cancel("tenant-without-subscription", ACTOR));
    }

    @Test
    void unknownFeatureIsNotEntitled() {
        subscriptionService.subscribe(TENANT, PlanCode.ENTERPRISE, ACTOR);
        EntitlementService.Decision decision = entitlementService.check(TENANT, "TIME_TRAVEL");
        assertFalse(decision.entitled());
        assertEquals("unknown_feature", decision.reason());
    }

    @Test
    void quotaUnderTheLimitIsAllowed() {
        subscriptionService.subscribe(TENANT, PlanCode.STARTER, ACTOR);

        EntitlementService.QuotaDecision decision =
                entitlementService.quota(TENANT, LimitType.MAX_PROJECTS, 2);
        assertTrue(decision.entitled());
        assertEquals(3, decision.max());
        assertEquals(1L, decision.remaining());
    }

    @Test
    void quotaAtTheLimitIsDenied() {
        subscriptionService.subscribe(TENANT, PlanCode.STARTER, ACTOR);

        EntitlementService.QuotaDecision decision =
                entitlementService.quota(TENANT, LimitType.MAX_AUTOMATIONS, 5);
        assertFalse(decision.entitled());
        assertEquals("quota_exceeded", decision.reason());
        assertEquals(5, decision.max());
        assertEquals(0L, decision.remaining());
    }

    @Test
    void jobsAndCloudIntegrationsAreQuotaGated() {
        subscriptionService.subscribe(TENANT, PlanCode.STARTER, ACTOR);

        assertTrue(entitlementService.quota(TENANT, LimitType.MAX_JOBS, 4).entitled());
        EntitlementService.QuotaDecision jobs =
                entitlementService.quota(TENANT, LimitType.MAX_JOBS, 5);
        assertFalse(jobs.entitled());
        assertEquals(5, jobs.max());

        EntitlementService.QuotaDecision cloud =
                entitlementService.quota(TENANT, LimitType.MAX_CLOUD_INTEGRATIONS, 2);
        assertFalse(cloud.entitled());
        assertEquals("quota_exceeded", cloud.reason());
        assertEquals(2, cloud.max());
    }

    @Test
    void enterpriseQuotaIsCappedSinceV4() {
        subscriptionService.subscribe(TENANT, PlanCode.ENTERPRISE, ACTOR);

        EntitlementService.QuotaDecision decision =
                entitlementService.quota(TENANT, LimitType.MAX_PROJECTS, 30);
        assertFalse(decision.entitled());
        assertEquals("quota_exceeded", decision.reason());
        assertEquals(30, decision.max());
    }

    @Test
    void nullMaxStillMeansUnlimited() {
        // No catalog tier is unlimited anymore, but the NULL=unlimited
        // convention stays supported (custom/negotiated plans).
        Plan enterprise = planRepository.findByCode(PlanCode.ENTERPRISE).orElseThrow();
        enterprise.setMaxProjects(null);
        planRepository.save(enterprise);
        subscriptionService.subscribe(TENANT, PlanCode.ENTERPRISE, ACTOR);

        EntitlementService.QuotaDecision decision =
                entitlementService.quota(TENANT, LimitType.MAX_PROJECTS, 100_000);
        assertTrue(decision.entitled());
        assertNull(decision.max(), "null max means unlimited");
    }

    @Test
    void expiredTrialDeniesQuotaBeforeComparingCounts() {
        subscriptionService.subscribe(TENANT, PlanCode.STARTER, ACTOR);
        Subscription sub = subscriptionRepository.findByTenantId(TENANT).orElseThrow();
        sub.setTrialEndsAt(Instant.now().minus(Duration.ofDays(1)));
        subscriptionRepository.save(sub);

        EntitlementService.QuotaDecision decision =
                entitlementService.quota(TENANT, LimitType.MAX_PROJECTS, 0);
        assertFalse(decision.entitled());
        assertEquals("trial_expired", decision.reason());
    }

    // ------ payments ------

    @Test
    void trialSubscribeDoesNotCharge() {
        subscriptionService.subscribe(TENANT, PlanCode.TEAM, ACTOR);
        assertEquals(0, paymentRepository.count(), "trials are free");
    }

    @Test
    void reactivationChargesThePlanPrice() {
        subscriptionService.subscribe(TENANT, PlanCode.TEAM, ACTOR);
        Subscription sub = subscriptionRepository.findByTenantId(TENANT).orElseThrow();
        sub.setTrialEndsAt(Instant.now().minus(Duration.ofDays(1)));
        subscriptionRepository.save(sub);

        Subscription reactivated = subscriptionService.subscribe(TENANT, PlanCode.TEAM, ACTOR);

        assertEquals(SubscriptionStatus.ACTIVE, reactivated.getStatus());
        Payment payment = paymentRepository.findByTenantIdOrderByCreatedAtDesc(TENANT).get(0);
        assertEquals(PaymentStatus.SUCCEEDED, payment.getStatus());
        assertEquals(2 * 49 * 100, payment.getAmountCents(), "plan price in cents");
        assertNotNull(payment.getProviderRef());
    }

    @Test
    void declinedChargeDropsToPastDueAndRetryRecovers() {
        subscriptionService.subscribe(TENANT, PlanCode.TEAM, ACTOR);
        Subscription sub = subscriptionRepository.findByTenantId(TENANT).orElseThrow();
        sub.setTrialEndsAt(Instant.now().minus(Duration.ofDays(1)));
        subscriptionRepository.save(sub);

        properties.setPaymentStubFails(true);
        Subscription pastDue = subscriptionService.subscribe(TENANT, PlanCode.TEAM, ACTOR);
        assertEquals(SubscriptionStatus.PAST_DUE, pastDue.getStatus());
        assertEquals(PaymentStatus.FAILED,
                paymentRepository.findByTenantIdOrderByCreatedAtDesc(TENANT).get(0).getStatus());
        EntitlementService.Decision denied = entitlementService.check(TENANT, null);
        assertFalse(denied.entitled());
        assertEquals("subscription_past_due", denied.reason());

        // Payment succeeds on retry -> back to ACTIVE, entitled again.
        properties.setPaymentStubFails(false);
        Payment retry = paymentService.retryLatestFailed(TENANT, ACTOR);
        assertEquals(PaymentStatus.SUCCEEDED, retry.getStatus());
        assertEquals(SubscriptionStatus.ACTIVE,
                subscriptionService.findCurrent(TENANT).orElseThrow().getStatus());
        assertTrue(entitlementService.check(TENANT, null).entitled());
    }

    @Test
    void planChangeWhileActiveCharges() {
        subscriptionService.subscribe(TENANT, PlanCode.TEAM, ACTOR);
        Subscription sub = subscriptionRepository.findByTenantId(TENANT).orElseThrow();
        sub.setTrialEndsAt(Instant.now().minus(Duration.ofDays(1)));
        subscriptionRepository.save(sub);
        subscriptionService.subscribe(TENANT, PlanCode.TEAM, ACTOR); // reactivate -> ACTIVE (1 charge)

        subscriptionService.subscribe(TENANT, PlanCode.ENTERPRISE, ACTOR); // upgrade (2nd charge)

        // Order-insensitive: created_at has no DB default in the H2 slice.
        var payments = paymentRepository.findByTenantIdOrderByCreatedAtDesc(TENANT);
        assertEquals(2, payments.size());
        assertTrue(payments.stream().anyMatch(p -> p.getAmountCents() == 4 * 49 * 100),
                "charged the NEW plan's price");
        assertTrue(auditLogRepository.findByTenantIdOrderByCreatedAtDesc(TENANT).stream()
                .anyMatch(e -> e.getEventType() == SubscriptionEventType.PAYMENT_SUCCEEDED));
    }

    @Test
    void retryWithoutFailureIs404() {
        subscriptionService.subscribe(TENANT, PlanCode.TEAM, ACTOR);
        SubscriptionException ex = assertThrows(SubscriptionException.class,
                () -> paymentService.retryLatestFailed(TENANT, ACTOR));
        assertEquals("no_failed_payment", ex.getError());
    }

    @Test
    void lifecycleLeavesAnAuditTrail() {
        subscriptionService.subscribe(TENANT, PlanCode.STARTER, ACTOR);
        subscriptionService.subscribe(TENANT, PlanCode.ENTERPRISE, ACTOR);
        subscriptionService.cancel(TENANT, ACTOR);

        var events = auditLogRepository.findByTenantIdOrderByCreatedAtDesc(TENANT);
        assertEquals(3, events.size());
        assertTrue(events.stream().allMatch(e -> ACTOR.equals(e.getActor())));
        assertTrue(events.stream()
                .anyMatch(e -> e.getEventType() == SubscriptionEventType.SUBSCRIBED
                        && e.getPlanCode() == PlanCode.STARTER));
        assertTrue(events.stream()
                .anyMatch(e -> e.getEventType() == SubscriptionEventType.PLAN_CHANGED
                        && e.getPlanCode() == PlanCode.ENTERPRISE));
        assertTrue(events.stream()
                .anyMatch(e -> e.getEventType() == SubscriptionEventType.CANCELED));
    }
}
