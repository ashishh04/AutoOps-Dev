package com.intertec.autoops.workflow.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intertec.autoops.workflow.client.AgentClient;
import com.intertec.autoops.workflow.client.CoreClient;
import com.intertec.autoops.workflow.client.EntitlementClient;
import com.intertec.autoops.workflow.domain.Workflow;
import com.intertec.autoops.workflow.exception.WorkflowException;
import com.intertec.autoops.workflow.repo.WorkflowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Workflow lifecycle against H2 with real commit semantics. These are the
 * cases that used to live in core-service's CoreServiceTest, plus the two the
 * split introduced: the project now has to be confirmed by core-service over
 * HTTP, and the automation budget is shared with agents living in a third
 * service.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({WorkflowService.class, SubscriptionGate.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WorkflowServiceTest {

    private static final String TENANT = "acme-corp-cafe0123";
    private static final String OTHER_TENANT = "rival-inc-beef4567";
    private static final String ACTOR = "admin@acme.io";
    private static final String TOKEN = "test-access-token";
    private static final long PROJECT = 7L;
    private static final long CATALOG_ID = 4200L;

    private static final EntitlementClient.Decision OK =
            new EntitlementClient.Decision(true, "ok", null, null);

    @Autowired
    private WorkflowService workflowService;
    @Autowired
    private WorkflowRepository workflowRepository;
    @MockBean
    private EntitlementClient entitlementClient;
    @MockBean
    private CoreClient coreClient;
    @MockBean
    private AgentClient agentClient;

    @TestConfiguration
    static class TestConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @BeforeEach
    void resetState() {
        workflowRepository.deleteAll();
        when(entitlementClient.checkActive(any())).thenReturn(OK);
        when(entitlementClient.checkQuota(any(), any(), anyLong())).thenReturn(OK);
        when(agentClient.countForTenant(any())).thenReturn(0L);
    }

    /**
     * A workflow created against the CALLER's own plan. Rollout deliberately
     * bypasses that gate (the caller there is the provider, not the tenant
     * receiving the workflow), so the quota and node-limit cases below run
     * through this path — it is the one where the token's plan is the right
     * plan to test.
     */
    private Workflow create(String name, String definition) {
        return workflowService.createTrusted(TENANT, ACTOR, TOKEN, PROJECT, name, definition);
    }

    /** A workflow delivered from the catalog in its own right: visible, runnable. */
    private Workflow rolledOut(String name, String definition) {
        return workflowService.rollOut(TENANT, ACTOR, TOKEN, PROJECT, CATALOG_ID, name, definition,
                Workflow.Delivery.PRODUCT);
    }

    /** The same catalog workflow, delivered only so an agent has something to call. */
    private Workflow rolledOutAsComponent(String name, String definition) {
        return workflowService.rollOut(TENANT, ACTOR, TOKEN, PROJECT, CATALOG_ID, name, definition,
                Workflow.Delivery.AGENT_COMPONENT);
    }

    // ------ definition parsing and node limits ------

    @Test
    void nodeCountIsParsedServerSide() {
        Workflow workflow = create("Deploy", "{\"nodes\":[{},{},{}],\"edges\":[]}");

        assertEquals(3, workflow.getNodeCount());
        // MAX_NODES bounds the SIZE: N nodes allowed iff N <= max, asked as current = N-1.
        verify(entitlementClient).checkQuota(eq(TOKEN), eq("MAX_NODES"), eq(2L));
    }

    @Test
    void invalidDefinitionIsRejected() {
        WorkflowException ex = assertThrows(WorkflowException.class,
                () -> create("Broken", "not-json"));
        assertEquals("invalid_definition", ex.getError());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    void nodeLimitDeniedOnDefinitionUpdate() {
        Workflow workflow = create("Deploy", "{\"nodes\":[{}]}");
        when(entitlementClient.checkQuota(any(), eq("MAX_NODES"), anyLong()))
                .thenReturn(new EntitlementClient.Decision(false, "quota_exceeded", 10, 0L));

        WorkflowException ex = assertThrows(WorkflowException.class,
                () -> workflowService.update(TENANT, TOKEN, workflow.getId(), null,
                        "{\"nodes\":[{},{},{},{},{},{},{},{},{},{},{}]}", true));
        assertEquals("quota_exceeded", ex.getError());
    }

    // ------ the shared automation budget ------

    @Test
    void agentsCountTowardTheSameAutomationBudget() {
        when(agentClient.countForTenant(TENANT)).thenReturn(2L);

        create("Deploy", null);

        // Two agents already hold two slots, so this workflow is asked with 2.
        verify(entitlementClient).checkQuota(eq(TOKEN), eq("MAX_AUTOMATIONS"), eq(2L));
    }

    @Test
    void deleteFreesTheAutomationSlot() {
        Workflow workflow = create("Deploy", null);
        workflowService.delete(TENANT, TOKEN, workflow.getId(), false);
        clearInvocations(entitlementClient);

        create("Deploy again", null);
        verify(entitlementClient).checkQuota(eq(TOKEN), eq("MAX_AUTOMATIONS"), eq(0L));
    }

    @Test
    void createDeniedAtTheAutomationQuota() {
        when(entitlementClient.checkQuota(any(), eq("MAX_AUTOMATIONS"), anyLong()))
                .thenReturn(new EntitlementClient.Decision(false, "quota_exceeded", 3, 0L));

        WorkflowException ex = assertThrows(WorkflowException.class, () -> create("Deploy", null));
        assertEquals("quota_exceeded", ex.getError());
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        assertTrue(ex.getMessage().contains("3"), "message carries the plan max for the UI");
    }

    @Test
    void mutationDeniedWhenSubscriptionExpired() {
        Workflow workflow = create("Deploy", null);
        when(entitlementClient.checkActive(any()))
                .thenReturn(new EntitlementClient.Decision(false, "trial_expired", null, null));

        WorkflowException ex = assertThrows(WorkflowException.class,
                () -> workflowService.setEnabled(TENANT, TOKEN, workflow.getId(), false));
        assertEquals("trial_expired", ex.getError());
    }

    // ------ what the split changed: the project lives elsewhere ------

    @Test
    void theOwningProjectIsConfirmedByCoreService() {
        workflowService.list(TENANT, PROJECT);

        ArgumentCaptor<Long> projectId = ArgumentCaptor.forClass(Long.class);
        verify(coreClient).requireProject(eq(TENANT), projectId.capture());
        assertEquals(PROJECT, projectId.getValue());
    }

    @Test
    void anUnknownProjectStopsTheRead() {
        doThrow(WorkflowException.notFound("project_not_found", "No such project"))
                .when(coreClient).requireProject(any(), any());

        WorkflowException ex = assertThrows(WorkflowException.class,
                () -> workflowService.list(TENANT, PROJECT));
        assertEquals("project_not_found", ex.getError());
    }

    @Test
    void anUnreachableCoreServiceFailsClosed() {
        // Without the check we cannot prove the project belongs to this tenant,
        // so the request must not proceed — 503, never "assume it is fine".
        doThrow(WorkflowException.serviceUnavailable("core_unavailable", "down"))
                .when(coreClient).requireProject(any(), any());

        WorkflowException ex = assertThrows(WorkflowException.class,
                () -> workflowService.list(TENANT, PROJECT));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatus());
    }

    @Test
    void theTrustedInternalPathSkipsTheProjectRoundTrip() {
        // core-service's SCM import already resolved the project against its
        // own database; bouncing back would put both pools on one request.
        workflowService.createTrusted(TENANT, ACTOR, TOKEN, PROJECT, "Imported", null);

        verify(coreClient, times(0)).requireProject(any(), any());
        assertEquals(1, workflowRepository.count());
    }

    @Test
    void rolloutSkipsTheProjectRoundTripToo() {
        // Same reasoning: core-service's provider surface resolves the target
        // project — and confirms it belongs to the target tenant — before it
        // calls here. That check is core-service's to make; this service must
        // not be the one that proves it, or the two pools deadlock-race again.
        rolledOut("Payment Exception Repair", null);

        verify(coreClient, times(0)).requireProject(any(), any());
        assertEquals(1, workflowRepository.count());
    }

    // ------ naming, isolation and counts ------

    @Test
    void duplicateNameInTheSameProjectIsRejected() {
        create("Deploy", null);

        WorkflowException ex = assertThrows(WorkflowException.class, () -> create("Deploy", null));
        assertEquals("workflow_exists", ex.getError());
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    @Test
    void anotherTenantCannotSeeOrTouchTheWorkflow() {
        Workflow workflow = create("Deploy", null);

        assertEquals("workflow_not_found", assertThrows(WorkflowException.class,
                () -> workflowService.get(OTHER_TENANT, workflow.getId())).getError());
        assertEquals("workflow_not_found", assertThrows(WorkflowException.class,
                () -> workflowService.delete(OTHER_TENANT, TOKEN, workflow.getId(), false)).getError());
        assertEquals(1, workflowRepository.count(), "it survives the foreign delete");
    }

    @Test
    void countsAreTenantScopedForTheAgentHalfOfTheBudget() {
        create("Deploy", null);
        create("Rollback", null);
        workflowService.createTrusted(OTHER_TENANT, ACTOR, TOKEN, PROJECT, "Theirs", null);

        assertEquals(2, workflowService.countForTenant(TENANT));
        assertEquals(1, workflowService.countForTenant(OTHER_TENANT));
        assertEquals(2L, workflowService.countsByTenant().get(TENANT));
    }

    @Test
    void listingIsScopedToTheProjectAndTenant() {
        create("Deploy", null);
        workflowService.createTrusted(TENANT, ACTOR, TOKEN, 9L, "Elsewhere", null);

        assertEquals(List.of("Deploy"),
                workflowService.list(TENANT, PROJECT).stream().map(Workflow::getName).toList());
        assertTrue(workflowService.listUnchecked(OTHER_TENANT, PROJECT).isEmpty());
    }

    @Test
    void enableAndDisableFlipTheFlag() {
        Workflow workflow = create("Deploy", null);
        assertTrue(workflow.isEnabled(), "workflows start enabled");

        assertEquals(false,
                workflowService.setEnabled(TENANT, TOKEN, workflow.getId(), false).isEnabled());
        assertEquals(true,
                workflowService.setEnabled(TENANT, TOKEN, workflow.getId(), true).isEnabled());
    }

    // ------ provider-authored workflows are the tenant's to run, not to change ------

    @Test
    void rolloutDoesNotTestTheCallersPlan() {
        // The token on a rollout belongs to the PROVIDER, so gating on it
        // would test the wrong subscription entirely — and, since a provider
        // tenant typically has no plan of its own, would refuse every
        // delivery. core-service checks the RECEIVING customer's subscription
        // instead, before it ever calls here.
        when(entitlementClient.checkQuota(any(), any(), anyLong()))
                .thenReturn(new EntitlementClient.Decision(false, "quota_exceeded", 1, 0L));

        Workflow delivered = rolledOut("Payment Exception Repair", "{\"nodes\":[{},{}]}");

        assertEquals(2, delivered.getNodeCount(), "still counted server-side");
        verify(entitlementClient, times(0)).checkQuota(any(), any(), anyLong());
    }

    @Test
    void rolloutMarksTheWorkflowProviderAuthoredAndRecordsItsSource() {
        Workflow workflow = rolledOut("Payment Exception Repair", "{\"nodes\":[{},{}]}");

        assertEquals(Workflow.Origin.PROVIDER, workflow.getOrigin());
        assertEquals(CATALOG_ID, workflow.getSourceId());
        assertTrue(workflow.isProviderAuthored());
    }

    @Test
    void tenantCannotEditAProviderAuthoredWorkflow() {
        Workflow workflow = rolledOut("Payment Exception Repair", "{\"nodes\":[{}]}");

        WorkflowException ex = assertThrows(WorkflowException.class,
                () -> workflowService.update(TENANT, TOKEN, workflow.getId(), "Renamed",
                        "{\"nodes\":[]}", false));

        assertEquals("provider_managed", ex.getError());
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        assertEquals("Payment Exception Repair",
                workflowService.get(TENANT, workflow.getId()).getName(), "left untouched");
    }

    @Test
    void tenantCannotDeleteAProviderAuthoredWorkflow() {
        Workflow workflow = rolledOut("Payment Exception Repair", null);

        assertEquals("provider_managed", assertThrows(WorkflowException.class,
                () -> workflowService.delete(TENANT, TOKEN, workflow.getId(), false)).getError());
        assertTrue(workflowRepository.findByIdAndTenantId(workflow.getId(), TENANT).isPresent());
    }

    @Test
    void tenantMayStillPauseAProviderAuthoredWorkflow() {
        // Whether a rolled-out automation is live in their own workspace is
        // the customer's call — only the DESIGN is sealed.
        Workflow workflow = rolledOut("Payment Exception Repair", null);

        assertEquals(false,
                workflowService.setEnabled(TENANT, TOKEN, workflow.getId(), false).isEnabled());
    }

    @Test
    void providerMayStillEditWhatItAuthored() {
        Workflow workflow = rolledOut("Payment Exception Repair", "{\"nodes\":[{}]}");

        Workflow updated = workflowService.update(TENANT, TOKEN, workflow.getId(),
                "Payment Exception Repair v2", "{\"nodes\":[{},{}]}", true);

        assertEquals("Payment Exception Repair v2", updated.getName());
        assertEquals(2, updated.getNodeCount());
    }

    @Test
    void legacyTenantAuthoredWorkflowsStayEditableByTheTenant() {
        // Rows that predate the provider-authored model (createTrusted, e.g. an
        // SCM import from before the change) must not become unmanageable.
        Workflow legacy = workflowService.createTrusted(TENANT, ACTOR, TOKEN, PROJECT,
                "Legacy", "{\"nodes\":[{}]}");
        assertEquals(Workflow.Origin.TENANT, legacy.getOrigin());

        assertEquals("Legacy renamed", workflowService.update(TENANT, TOKEN, legacy.getId(),
                "Legacy renamed", null, false).getName());
    }

    // ------ one delivered copy per catalog item per project ------

    @Test
    void deliveringTheSameCatalogWorkflowTwiceIsRefused() {
        rolledOut("Card Fraud Alert Triage", "{\"nodes\":[{}]}");

        WorkflowException ex = assertThrows(WorkflowException.class,
                () -> rolledOut("Card Fraud Alert Triage", "{\"nodes\":[{}]}"));

        assertEquals("already_delivered", ex.getError());
        assertEquals(1, workflowRepository.count());
    }

    /**
     * The case the name check cannot see: rename the catalog item, roll it out
     * again, and the names no longer clash — but it is still the same workflow.
     */
    @Test
    void renamingTheCatalogItemDoesNotSlipASecondCopyThrough() {
        rolledOut("Card Fraud Alert Triage", "{\"nodes\":[{}]}");

        WorkflowException ex = assertThrows(WorkflowException.class,
                () -> rolledOut("Card Fraud Triage v2", "{\"nodes\":[{}]}"));

        assertEquals("already_delivered", ex.getError());
        assertEquals(1, workflowRepository.count());
    }

    /**
     * The limit is per PROJECT, not per tenant: a customer running two projects
     * may legitimately have the same workflow in both.
     */
    @Test
    void theSameCatalogWorkflowMayGoToTwoOfACustomersProjects() {
        rolledOut("Card Fraud Alert Triage", "{\"nodes\":[{}]}");

        Workflow second = workflowService.rollOut(TENANT, ACTOR, TOKEN, PROJECT + 1, CATALOG_ID,
                "Card Fraud Alert Triage", "{\"nodes\":[{}]}", Workflow.Delivery.PRODUCT);

        assertEquals(PROJECT + 1, second.getProjectId());
        assertEquals(2, workflowRepository.count());
    }

    /** A tenant-built workflow has no source, so it is never a repeat delivery. */
    @Test
    void tenantBuiltWorkflowsAreUnaffectedByTheRolloutCheck() {
        create("Nightly backup", "{\"nodes\":[{}]}");

        Workflow second = create("Nightly sweep", "{\"nodes\":[{}]}");

        assertNull(second.getSourceId());
        assertEquals(2, workflowRepository.count());
    }

    // ------ agent components: delivered, but not sold ------

    /**
     * The commercial line. Workflows and agents are licensed separately, but an
     * agent can only act through automations that live in the customer's
     * project — so rolling out an agent has to deliver them. Listing those to
     * the customer hands them a product nobody sold them, and invites the fair
     * question "I did not ask for this, why is it in my environment?".
     */
    @Test
    void anAgentComponentIsNotListedToTheCustomer() {
        rolledOut("Incident Postmortem Writer", "{\"nodes\":[{}]}");
        rolledOutAsComponent("CloudTrail Change Timeline", "{\"nodes\":[{}]}");

        List<Workflow> visible = workflowService.list(TENANT, PROJECT);

        assertEquals(1, visible.size());
        assertEquals("Incident Postmortem Writer", visible.get(0).getName());
    }

    /**
     * The other half, and the one that would break the product if it were
     * wrong: tool resolution reads the INTERNAL listing, which must return
     * components. A component hidden from this makes the agent that needs it
     * undeliverable.
     */
    @Test
    void theInternalListingStillSeesEveryComponent() {
        rolledOut("Incident Postmortem Writer", "{\"nodes\":[{}]}");
        rolledOutAsComponent("CloudTrail Change Timeline", "{\"nodes\":[{}]}");

        assertEquals(2, workflowService.listUnchecked(TENANT, PROJECT).size());
    }

    /**
     * A customer who later licenses one of these workflows in its own right
     * gets a separate copy they own. Two rows, two lifecycles — revoking the
     * agent takes its component and leaves the licensed one alone.
     */
    @Test
    void licensingAComponentLaterDeliversASeparateCopy() {
        rolledOutAsComponent("CloudTrail Change Timeline", "{\"nodes\":[{}]}");

        Workflow licensed = workflowService.rollOut(TENANT, ACTOR, TOKEN, PROJECT, CATALOG_ID,
                "CloudTrail Change Timeline", "{\"nodes\":[{}]}", Workflow.Delivery.PRODUCT);

        assertEquals(2, workflowRepository.count());
        assertEquals(Workflow.Delivery.PRODUCT, licensed.getDelivery());
        // And only the licensed one is theirs to see.
        List<Workflow> visible = workflowService.list(TENANT, PROJECT);
        assertEquals(1, visible.size());
        assertEquals(licensed.getId(), visible.get(0).getId());
    }

    /** A repeat of the SAME kind is still the defect the dedupe exists for. */
    @Test
    void theSameComponentIsNotDeliveredTwice() {
        rolledOutAsComponent("CloudTrail Change Timeline", "{\"nodes\":[{}]}");

        WorkflowException thrown = assertThrows(WorkflowException.class,
                () -> rolledOutAsComponent("CloudTrail Change Timeline", "{\"nodes\":[{}]}"));

        assertTrue(thrown.getMessage().contains("already has this workflow"));
        assertEquals(1, workflowRepository.count());
    }

    /** Everything that predates this concept was delivered as a product. */
    @Test
    void aWorkflowDeliveredInItsOwnRightDefaultsToProduct() {
        assertEquals(Workflow.Delivery.PRODUCT,
                rolledOut("Idle Resource Inventory", "{\"nodes\":[{}]}").getDelivery());
    }
}
