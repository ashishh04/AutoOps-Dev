package com.intertec.autoops.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intertec.autoops.core.client.EntitlementClient;
import com.intertec.autoops.core.client.SubscriptionInfoClient;
import com.intertec.autoops.core.config.CoreProperties;
import com.intertec.autoops.core.domain.Approval;
import com.intertec.autoops.core.domain.ApprovalStatus;
import com.intertec.autoops.core.domain.Job;
import com.intertec.autoops.core.domain.Project;
import com.intertec.autoops.core.domain.Run;
import com.intertec.autoops.core.domain.RunStatus;
import com.intertec.autoops.core.client.WorkflowClient;
import com.intertec.autoops.core.domain.RunTargetType;
import com.intertec.autoops.core.exception.CoreException;
import com.intertec.autoops.core.execution.ExecutionEngine;
import com.intertec.autoops.core.execution.SimulatedStepExecutor;
import com.intertec.autoops.core.repo.ApprovalRepository;
import com.intertec.autoops.core.repo.JobRepository;
import com.intertec.autoops.core.repo.ProjectRepository;
import com.intertec.autoops.core.repo.RunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * Human approval gate: a requires_approval job run by a non-admin becomes a
 * PENDING approval; an ADMIN approving it starts the run. Same H2 + joined
 * fresh-thread executor setup as {@link RunServiceTest}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ProjectService.class, JobService.class, RunService.class,
        ApprovalService.class, ApprovalSettingsService.class, ExecutionEngine.class,
        SimulatedStepExecutor.class, SubscriptionGate.class, NativeInputValidator.class,
        LibraryStepResolver.class,
        NativeWorkflowService.class,
        WorkflowReadiness.class,
        com.intertec.autoops.core.client.WorkflowRuntimeClient.class,
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ApprovalServiceTest {

    /** JobService now mirrors jobs onto the engine; this slice has no rundeck-service. */
    @MockBean
    private com.intertec.autoops.core.client.RundeckJobClient rundeckJobClient;

    /**
     * ProjectService now tells the execution engine when a project is created,
     * renamed or archived. Mocked because this slice has no rundeck-service.
     */
    @MockBean
    private com.intertec.autoops.core.client.RundeckProjectClient rundeckProjectClient;

    private static final String TENANT = "acme-corp-cafe0123";
    private static final String ADMIN = "admin@acme.io";
    private static final String OPERATOR = "operator@acme.io";
    private static final String TOKEN = "test-access-token";
    private static final String ADMIN_ROLE = "ADMIN";
    private static final String CLIENT_ROLE = "CLIENT";
    private static final String STEPS = "{\"steps\":[{\"type\":\"script\",\"label\":\"Deploy\"}]}";

    private static final EntitlementClient.Decision OK =
            new EntitlementClient.Decision(true, "ok", null, null);

    @Autowired
    private ProjectService projectService;
    @Autowired
    private JobService jobService;
    @Autowired
    private RunService runService;
    @Autowired
    private ApprovalService approvalService;
    @Autowired
    private ApprovalSettingsService approvalSettingsService;
    @Autowired
    private com.intertec.autoops.core.repo.ApprovalSettingRepository approvalSettingRepository;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private RunRepository runRepository;
    @Autowired
    private ApprovalRepository approvalRepository;
    @MockBean
    private EntitlementClient entitlementClient;
    @MockBean
    private SubscriptionInfoClient subscriptionInfoClient;
    @MockBean
    private WorkflowClient workflowClient;

    /**
     * NativeWorkflowService needs it to resolve a workflow's model, but it
     * drags in repositories, the credential crypto and a vendor probe that
     * this slice has none of — and no case here runs a graph with an llm node.
     */
    @MockBean
    private ModelProviderService modelProviderService;

    /** Ids for the stubbed workflow views; workflow-service owns the real ones. */
    private long nextWorkflowId = 1;

    @TestConfiguration
    static class TestConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        CoreProperties coreProperties() {
            CoreProperties properties = new CoreProperties();
            properties.getExecution().setSimulatedStepMinDelay(Duration.ZERO);
            properties.getExecution().setSimulatedStepMaxDelay(Duration.ZERO);
            properties.getExecution().setRetryDelay(Duration.ZERO);
            return properties;
        }

        /** See RunServiceTest: fresh joined thread, never inline. */
        /**
         * Workflow runs use their own pool in production so a workflow cannot
         * starve the job it is waiting for (see ExecutionConfig). Here both
         * point at the same joined-thread executor: nothing in this slice runs
         * a workflow that starts a job, and a second pool would only add a
         * second way for the test to be non-deterministic.
         */
        @Bean(name = "workflowTaskExecutor")
        TaskExecutor workflowTaskExecutor() {
            return executionTaskExecutor();
        }

        @Bean(name = "executionTaskExecutor")
        TaskExecutor executionTaskExecutor() {
            return command -> {
                Thread worker = new Thread(command, "test-approval-exec");
                worker.start();
                try {
                    worker.join();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            };
        }
    }

    private Project project;
    private Job gatedJob;

    @BeforeEach
    void resetState() {
        approvalSettingRepository.deleteAll();
        approvalRepository.deleteAll();
        runRepository.deleteAll();
        jobRepository.deleteAll();
        projectRepository.deleteAll();
        when(entitlementClient.checkActive(any())).thenReturn(OK);
        when(entitlementClient.checkQuota(any(), any(), anyLong())).thenReturn(OK);
        when(subscriptionInfoClient.historyDays(any(), any())).thenReturn(null);
        project = projectService.create(TENANT, ADMIN, TOKEN, "Alpha", null);
        gatedJob = jobService.create(TENANT, ADMIN, TOKEN, project.getId(), "Prod Deploy",
                null, null, STEPS, null, true);
    }

    @Test
    void operatorRunOfGatedJobQueuesAnApprovalInsteadOfARun() {
        Approval approval = approvalService.interceptJobRun(
                TENANT, OPERATOR, CLIENT_ROLE, TOKEN, gatedJob.getId());

        assertNotNull(approval);
        assertEquals(ApprovalStatus.PENDING, approval.getStatus());
        assertEquals(OPERATOR, approval.getRequestedBy());
        assertEquals("Prod Deploy", approval.getTargetName());
        assertEquals(RunTargetType.JOB, approval.getTargetType());
        assertEquals(0, runRepository.count(), "no run until an admin approves");
    }

    @Test
    void adminRunOfGatedJobBypassesTheGate() {
        assertNull(approvalService.interceptJobRun(
                TENANT, ADMIN, ADMIN_ROLE, TOKEN, gatedJob.getId()));
    }

    @Test
    void unflaggedJobNeverNeedsApproval() {
        Job plain = jobService.create(TENANT, ADMIN, TOKEN, project.getId(), "Cleanup",
                null, null, STEPS, null, false);
        assertNull(approvalService.interceptJobRun(
                TENANT, OPERATOR, CLIENT_ROLE, TOKEN, plain.getId()));
    }

    @Test
    void secondRequestWhileOneIsPendingConflicts() {
        approvalService.interceptJobRun(TENANT, OPERATOR, CLIENT_ROLE, TOKEN, gatedJob.getId());

        CoreException ex = assertThrows(CoreException.class, () -> approvalService
                .interceptJobRun(TENANT, OPERATOR, CLIENT_ROLE, TOKEN, gatedJob.getId()));
        assertEquals("approval_pending", ex.getError());
    }

    @Test
    void adminApprovalStartsTheRunAndLinksIt() {
        Approval approval = approvalService.interceptJobRun(
                TENANT, OPERATOR, CLIENT_ROLE, TOKEN, gatedJob.getId());

        Approval decided = approvalService.approve(TENANT, ADMIN, ADMIN_ROLE, TOKEN,
                approval.getId());

        assertEquals(ApprovalStatus.APPROVED, decided.getStatus());
        assertEquals(ADMIN, decided.getDecidedBy());
        assertNotNull(decided.getDecidedAt());
        assertNotNull(decided.getRunId());
        Run run = runRepository.findById(decided.getRunId()).orElseThrow();
        assertEquals(RunStatus.SUCCEEDED, run.getStatus());
        assertEquals(ADMIN, run.getTriggeredBy());
    }

    @Test
    void rejectClosesTheRequestWithoutARun() {
        Approval approval = approvalService.interceptJobRun(
                TENANT, OPERATOR, CLIENT_ROLE, TOKEN, gatedJob.getId());

        Approval decided = approvalService.reject(TENANT, ADMIN, ADMIN_ROLE, approval.getId());

        assertEquals(ApprovalStatus.REJECTED, decided.getStatus());
        assertEquals(0, runRepository.count());
    }

    @Test
    void nonAdminCannotApproveOrReject() {
        Approval approval = approvalService.interceptJobRun(
                TENANT, OPERATOR, CLIENT_ROLE, TOKEN, gatedJob.getId());

        CoreException approveEx = assertThrows(CoreException.class, () -> approvalService
                .approve(TENANT, OPERATOR, CLIENT_ROLE, TOKEN, approval.getId()));
        assertEquals("approval_admin_only", approveEx.getError());
        assertEquals(HttpStatus.FORBIDDEN, approveEx.getStatus());

        CoreException rejectEx = assertThrows(CoreException.class, () -> approvalService
                .reject(TENANT, OPERATOR, CLIENT_ROLE, approval.getId()));
        assertEquals("approval_admin_only", rejectEx.getError());
    }

    @Test
    void resolvedRequestCannotBeDecidedAgain() {
        Approval approval = approvalService.interceptJobRun(
                TENANT, OPERATOR, CLIENT_ROLE, TOKEN, gatedJob.getId());
        approvalService.reject(TENANT, ADMIN, ADMIN_ROLE, approval.getId());

        CoreException ex = assertThrows(CoreException.class, () -> approvalService
                .approve(TENANT, ADMIN, ADMIN_ROLE, TOKEN, approval.getId()));
        assertEquals("approval_resolved", ex.getError());
    }

    @Test
    void afterRejectionANewRequestCanBeFiled() {
        Approval first = approvalService.interceptJobRun(
                TENANT, OPERATOR, CLIENT_ROLE, TOKEN, gatedJob.getId());
        approvalService.reject(TENANT, ADMIN, ADMIN_ROLE, first.getId());

        Approval second = approvalService.interceptJobRun(
                TENANT, OPERATOR, CLIENT_ROLE, TOKEN, gatedJob.getId());
        assertEquals(ApprovalStatus.PENDING, second.getStatus());
    }

    // ------ workflows: automatic complexity gate ------

    /**
     * Workflows live in workflow-service now, so these tests stand up the
     * VIEW core-service actually receives rather than a persisted row. What
     * is under test here — the complexity rule and the approval it queues —
     * is unchanged by that.
     */
    private WorkflowClient.WorkflowView workflow(String name, String definition, int nodeCount) {
        long id = nextWorkflowId++;
        WorkflowClient.WorkflowView view = new WorkflowClient.WorkflowView(
                id, TENANT, project.getId(), name, definition, nodeCount, true);
        when(workflowClient.require(TENANT, id)).thenReturn(view);
        when(workflowClient.find(TENANT, id)).thenReturn(java.util.Optional.of(view));
        return view;
    }

    private WorkflowClient.WorkflowView workflowWithNodes(String name, int plainNodes) {
        StringBuilder nodes = new StringBuilder();
        for (int i = 0; i < plainNodes; i++) {
            if (i > 0) nodes.append(',');
            nodes.append("{\"type\":\"script\",\"label\":\"step ").append(i).append("\"}");
        }
        return workflow(name, "{\"nodes\":[" + nodes + "]}", plainNodes);
    }

    @Test
    void simpleWorkflowRunsWithoutApproval() {
        WorkflowClient.WorkflowView simple = workflowWithNodes("Simple Sync", 2);
        assertNull(approvalService.interceptWorkflowRun(
                TENANT, OPERATOR, CLIENT_ROLE, TOKEN, simple.id()));
    }

    @Test
    void complexWorkflowByNodeCountQueuesAnApproval() {
        WorkflowClient.WorkflowView big = workflowWithNodes("Big Pipeline", 6);

        Approval approval = approvalService.interceptWorkflowRun(
                TENANT, OPERATOR, CLIENT_ROLE, TOKEN, big.id());

        assertNotNull(approval, ">= 5 nodes is complex");
        assertEquals(RunTargetType.WORKFLOW, approval.getTargetType());
        assertEquals("Big Pipeline", approval.getTargetName());
        assertEquals(0, runRepository.count());
    }

    @Test
    void riskyNodeMakesEvenATinyWorkflowComplex() {
        WorkflowClient.WorkflowView infra = workflow("Terraform Apply", "{\"nodes\":[{\"type\":\"terraform\",\"label\":\"apply\"}]}", 1);

        assertNotNull(approvalService.interceptWorkflowRun(
                TENANT, OPERATOR, CLIENT_ROLE, TOKEN, infra.id()));
    }

    @Test
    void adminRunOfComplexWorkflowBypassesTheGate() {
        WorkflowClient.WorkflowView big = workflowWithNodes("Big Pipeline", 6);
        assertNull(approvalService.interceptWorkflowRun(
                TENANT, ADMIN, ADMIN_ROLE, TOKEN, big.id()));
    }

    @Test
    void approvingAWorkflowRequestStartsTheWorkflowRun() {
        WorkflowClient.WorkflowView big = workflowWithNodes("Big Pipeline", 6);
        Approval approval = approvalService.interceptWorkflowRun(
                TENANT, OPERATOR, CLIENT_ROLE, TOKEN, big.id());

        Approval decided = approvalService.approve(TENANT, ADMIN, ADMIN_ROLE, TOKEN,
                approval.getId());

        Run run = runRepository.findById(decided.getRunId()).orElseThrow();
        assertEquals(RunTargetType.WORKFLOW, run.getTargetType());
        assertEquals(RunStatus.SUCCEEDED, run.getStatus());
        assertEquals(6, run.getStepCompleted());
    }

    // ------ per-tenant threshold settings ------

    @Test
    void thresholdDefaultsToPlatformValue() {
        assertEquals(WorkflowComplexity.NODE_THRESHOLD,
                approvalSettingsService.rules(TENANT).nodeThreshold());
        WorkflowClient.WorkflowView three = workflowWithNodes("Three Steps", 3);
        assertNull(approvalService.interceptWorkflowRun(
                TENANT, OPERATOR, CLIENT_ROLE, TOKEN, three.id()),
                "3 nodes is below the default threshold of 5");
    }

    @Test
    void loweredThresholdGatesSmallerWorkflows() {
        approvalSettingsService.update(TENANT, ADMIN, ADMIN_ROLE, 2, null);

        WorkflowClient.WorkflowView three = workflowWithNodes("Three Steps", 3);
        assertNotNull(approvalService.interceptWorkflowRun(
                TENANT, OPERATOR, CLIENT_ROLE, TOKEN, three.id()),
                "3 nodes is complex once the tenant threshold is 2");
    }

    @Test
    void raisedThresholdUngatesNodeCountButNotRiskyTypes() {
        approvalSettingsService.update(TENANT, ADMIN, ADMIN_ROLE, 50, null);

        WorkflowClient.WorkflowView big = workflowWithNodes("Big Pipeline", 6);
        assertNull(approvalService.interceptWorkflowRun(
                TENANT, OPERATOR, CLIENT_ROLE, TOKEN, big.id()),
                "6 nodes is fine under a threshold of 50");

        WorkflowClient.WorkflowView infra = workflow("TF", "{\"nodes\":[{\"type\":\"terraform\",\"label\":\"apply\"}]}", 1);
        assertNotNull(approvalService.interceptWorkflowRun(
                TENANT, OPERATOR, CLIENT_ROLE, TOKEN, infra.id()),
                "risky node types gate regardless of the threshold");
    }

    @Test
    void nonAdminCannotChangeSettings() {
        CoreException ex = assertThrows(CoreException.class, () ->
                approvalSettingsService.update(TENANT, OPERATOR, CLIENT_ROLE, 2, null));
        assertEquals("approval_admin_only", ex.getError());
    }

    @Test
    void thresholdOutsideBoundsIsRejected() {
        CoreException ex = assertThrows(CoreException.class, () ->
                approvalSettingsService.update(TENANT, ADMIN, ADMIN_ROLE, 0, null));
        assertEquals("invalid_threshold", ex.getError());
    }

    @Test
    void settingsAreTenantScoped() {
        approvalSettingsService.update(TENANT, ADMIN, ADMIN_ROLE, 2, null);
        assertEquals(WorkflowComplexity.NODE_THRESHOLD,
                approvalSettingsService.rules("rival-inc-beef4567").nodeThreshold(),
                "other tenants keep the platform default");
    }

    // ------ per-tenant risky node types ------

    @Test
    void customRiskyTypesReplaceThePlatformSet() {
        approvalSettingsService.update(TENANT, ADMIN, ADMIN_ROLE, null, List.of("notify"));

        WorkflowClient.WorkflowView notify = workflow("Pager", "{\"nodes\":[{\"type\":\"notify\",\"label\":\"page\"}]}", 1);
        assertNotNull(approvalService.interceptWorkflowRun(
                TENANT, OPERATOR, CLIENT_ROLE, TOKEN, notify.id()),
                "tenant-defined risky type gates");

        WorkflowClient.WorkflowView infra = workflow("TF", "{\"nodes\":[{\"type\":\"terraform\",\"label\":\"apply\"}]}", 1);
        assertNull(approvalService.interceptWorkflowRun(
                TENANT, OPERATOR, CLIENT_ROLE, TOKEN, infra.id()),
                "the override REPLACES the platform set — terraform no longer gates");
    }

    @Test
    void emptyRiskyListDisablesRiskyGatingButKeepsTheThreshold() {
        approvalSettingsService.update(TENANT, ADMIN, ADMIN_ROLE, null, List.of());

        WorkflowClient.WorkflowView infra = workflow("TF", "{\"nodes\":[{\"type\":\"terraform\",\"label\":\"apply\"}]}", 1);
        assertNull(approvalService.interceptWorkflowRun(
                TENANT, OPERATOR, CLIENT_ROLE, TOKEN, infra.id()),
                "empty list = risky gating off");

        WorkflowClient.WorkflowView big = workflowWithNodes("Big Pipeline", 6);
        assertNotNull(approvalService.interceptWorkflowRun(
                TENANT, OPERATOR, CLIENT_ROLE, TOKEN, big.id()),
                "node-count threshold still applies");
    }

    @Test
    void partialUpdatesLeaveTheOtherKnobUnchanged() {
        approvalSettingsService.update(TENANT, ADMIN, ADMIN_ROLE, 2, null);
        assertEquals(WorkflowComplexity.RISKY_TYPES,
                approvalSettingsService.rules(TENANT).riskyTypes(),
                "threshold-only update keeps the platform risky set");

        approvalSettingsService.update(TENANT, ADMIN, ADMIN_ROLE, null, List.of("notify"));
        assertEquals(2, approvalSettingsService.rules(TENANT).nodeThreshold(),
                "risky-only update keeps the earlier threshold");
    }

    @Test
    void invalidRiskyTypeAndEmptyUpdateAreRejected() {
        CoreException bad = assertThrows(CoreException.class, () -> approvalSettingsService
                .update(TENANT, ADMIN, ADMIN_ROLE, null, List.of("bad type!")));
        assertEquals("invalid_risky_type", bad.getError());

        CoreException empty = assertThrows(CoreException.class, () -> approvalSettingsService
                .update(TENANT, ADMIN, ADMIN_ROLE, null, null));
        assertEquals("nothing_to_update", empty.getError());
    }

    @Test
    void riskyTypesAreNormalizedAndDeduped() {
        approvalSettingsService.update(TENANT, ADMIN, ADMIN_ROLE, null,
                List.of(" Terraform ", "terraform", "SSH"));
        assertEquals(java.util.Set.of("terraform", "ssh"),
                approvalSettingsService.rules(TENANT).riskyTypes());
    }

    @Test
    void approvalsAreTenantScoped() {
        Approval approval = approvalService.interceptJobRun(
                TENANT, OPERATOR, CLIENT_ROLE, TOKEN, gatedJob.getId());

        CoreException ex = assertThrows(CoreException.class, () -> approvalService
                .approve("rival-inc-beef4567", ADMIN, ADMIN_ROLE, TOKEN, approval.getId()));
        assertEquals("approval_not_found", ex.getError());
    }
}