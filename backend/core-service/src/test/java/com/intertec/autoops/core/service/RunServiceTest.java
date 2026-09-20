package com.intertec.autoops.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intertec.autoops.core.client.EntitlementClient;
import com.intertec.autoops.core.client.SubscriptionInfoClient;
import com.intertec.autoops.core.config.CoreProperties;
import com.intertec.autoops.core.domain.Job;
import com.intertec.autoops.core.domain.Project;
import com.intertec.autoops.core.domain.Run;
import com.intertec.autoops.core.domain.RunStatus;
import com.intertec.autoops.core.client.WorkflowClient;
import com.intertec.autoops.core.domain.RunTargetType;
import com.intertec.autoops.core.domain.RunTrigger;
import com.intertec.autoops.core.exception.CoreException;
import com.intertec.autoops.core.execution.ExecutionEngine;
import com.intertec.autoops.core.execution.SimulatedStepExecutor;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * Execution engine against H2 with real commit semantics: the task executor
 * is synchronous, so a run has fully executed by the time a trigger call
 * returns, and the simulated step delay is zero. The gate is mocked at the
 * EntitlementClient boundary, retention at the SubscriptionInfoClient one.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ProjectService.class, JobService.class, RunService.class,
        JobScheduler.class, ExecutionEngine.class, SimulatedStepExecutor.class,
        SubscriptionGate.class, NativeInputValidator.class,
        LibraryStepResolver.class,
        NativeWorkflowService.class,
        WorkflowReadiness.class,
        com.intertec.autoops.core.client.WorkflowRuntimeClient.class,
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RunServiceTest {

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
    private static final String OTHER_TENANT = "rival-inc-beef4567";
    private static final String ACTOR = "admin@acme.io";
    private static final String TOKEN = "test-access-token";

    private static final EntitlementClient.Decision OK =
            new EntitlementClient.Decision(true, "ok", null, null);

    @Autowired
    private ProjectService projectService;
    @Autowired
    private JobService jobService;
    @Autowired
    private RunService runService;
    @Autowired
    private JobScheduler jobScheduler;
    @Autowired
    private ExecutionEngine executionEngine;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private RunRepository runRepository;
    @MockBean
    private EntitlementClient entitlementClient;
    @MockBean
    private WorkflowClient workflowClient;
    @MockBean
    private SubscriptionInfoClient subscriptionInfoClient;
    @MockBean
    private SchedulerLeaseService schedulerLeaseService;

    /**
     * NativeWorkflowService needs it to resolve a workflow's model, but it
     * drags in repositories, the credential crypto and a vendor probe that
     * this slice has none of — and no case here runs a graph with an llm node.
     */
    @MockBean
    private ModelProviderService modelProviderService;

    @TestConfiguration
    static class TestConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        /** Zero-delay simulation: steps complete instantly. */
        @Bean
        CoreProperties coreProperties() {
            CoreProperties properties = new CoreProperties();
            properties.getExecution().setSimulatedStepMinDelay(Duration.ZERO);
            properties.getExecution().setSimulatedStepMaxDelay(Duration.ZERO);
            properties.getExecution().setRetryDelay(Duration.ZERO);
            return properties;
        }

        /**
         * Deterministic but NOT inline: afterCommit fires on the caller thread,
         * which is still bound to the just-committed transaction — an inline
         * engine would silently join it and lose every save. A fresh joined
         * thread gets clean transaction state, and the trigger call still
         * returns with the run fully finished.
         */
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
                Thread worker = new Thread(command, "test-run-exec");
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

    @BeforeEach
    void resetState() {
        runRepository.deleteAll();
        jobRepository.deleteAll();
        projectRepository.deleteAll();
        when(entitlementClient.checkActive(any())).thenReturn(OK);
        when(entitlementClient.checkQuota(any(), any(), anyLong())).thenReturn(OK);
        when(entitlementClient.checkTenant(any())).thenReturn(OK);
        when(subscriptionInfoClient.historyDays(any(), any())).thenReturn(null);
        when(schedulerLeaseService.tryAcquire(any())).thenReturn(true);
        project = projectService.create(TENANT, ACTOR, TOKEN, "Alpha", null);
    }

    // ------ execution ------

    @Test
    void manualJobRunExecutesEveryStepAndRecordsTheOutcome() {
        Job job = jobService.create(TENANT, ACTOR, TOKEN, project.getId(), "Backup", null, null,
                "{\"steps\":[{\"type\":\"script\",\"label\":\"Dump DB\"},"
                        + "{\"type\":\"s3\",\"label\":\"Upload\"}]}", null);

        Run queued = runService.runJob(TENANT, ACTOR, TOKEN, job.getId());
        Run run = runRepository.findById(queued.getId()).orElseThrow();

        assertEquals(RunStatus.SUCCEEDED, run.getStatus());
        assertEquals(RunTrigger.MANUAL, run.getTrigger());
        assertEquals(2, run.getStepTotal());
        assertEquals(2, run.getStepCompleted());
        assertEquals("Backup", run.getTargetName());
        assertNotNull(run.getStartedAt());
        assertNotNull(run.getFinishedAt());
        assertNotNull(run.getDurationMs());
        assertTrue(run.getLog().contains("Dump DB"), "log names each step");
        assertTrue(run.getLog().contains("Upload"));
    }

    /** Teaches the mocked client about a workflow, as workflow-service would. */
    private WorkflowClient.WorkflowView stubWorkflow(String name, String definition) {
        WorkflowClient.WorkflowView view = new WorkflowClient.WorkflowView(
                901L, TENANT, project.getId(), name, definition, 3, true);
        when(workflowClient.require(TENANT, view.id())).thenReturn(view);
        return view;
    }

    /** The same stub, but delivered only so an agent has something to call. */
    private WorkflowClient.WorkflowView stubComponent(String name, String definition) {
        WorkflowClient.WorkflowView view = new WorkflowClient.WorkflowView(
                902L, TENANT, project.getId(), name, definition, 3, true, "AGENT_COMPONENT");
        when(workflowClient.require(TENANT, view.id())).thenReturn(view);
        return view;
    }

    /**
     * Workflows and agents are licensed separately. A workflow delivered
     * because an agent names it was never sold to the customer, so they cannot
     * run it on its own — even by guessing the id, which a hidden row with a
     * sequential identifier otherwise invites.
     */
    @Test
    void aTenantCannotRunAWorkflowDeliveredAsAnAgentComponent() {
        WorkflowClient.WorkflowView component =
                stubComponent("CloudTrail Change Timeline", "{\"nodes\":[{}]}");

        CoreException thrown = assertThrows(CoreException.class,
                () -> runService.runWorkflow(TENANT, ACTOR, TOKEN, component.id(), null));

        // The same answer as a workflow that does not exist: confirming a
        // hidden component is present, by id, would undo the hiding.
        assertEquals("workflow_not_found", thrown.getError());
    }

    @Test
    void aTenantCannotReadAnAgentComponentsInputFormOrReadiness() {
        WorkflowClient.WorkflowView component =
                stubComponent("CloudTrail Change Timeline", "{\"nodes\":[{}]}");

        assertThrows(CoreException.class,
                () -> runService.inputFormFor(TENANT, component.id()));
        assertThrows(CoreException.class,
                () -> runService.readinessFor(TENANT, component.id()));
    }

    /** The product half: a workflow they were actually given still runs. */
    @Test
    void aWorkflowDeliveredInItsOwnRightStillRuns() {
        WorkflowClient.WorkflowView workflow =
                stubWorkflow("Idle Resource Inventory", "{\"nodes\":[{\"label\":\"Scan\"}]}");

        Run run = runService.runWorkflow(TENANT, ACTOR, TOKEN, workflow.id(), null);

        assertNotNull(run.getId());
    }

    @Test
    void workflowRunExecutesItsNodes() {
        // The definition comes from workflow-service; the run still snapshots
        // it and executes here, which is what this test is about.
        WorkflowClient.WorkflowView workflow = stubWorkflow(
                "Deploy", "{\"nodes\":[{\"label\":\"Build\"},{\"label\":\"Test\"},{\"label\":\"Ship\"}]}");

        Run run = runRepository.findById(
                runService.runWorkflow(TENANT, ACTOR, TOKEN, workflow.id()).getId()).orElseThrow();

        assertEquals(RunStatus.SUCCEEDED, run.getStatus());
        assertEquals(RunTargetType.WORKFLOW, run.getTargetType());
        assertEquals(3, run.getStepCompleted());
    }

    @Test
    void simulatedStepFailureFailsTheRunAndStopsThePipeline() {
        Job job = jobService.create(TENANT, ACTOR, TOKEN, project.getId(), "Flaky", null, null,
                "{\"steps\":[{\"label\":\"ok step\"},{\"label\":\"bad step\",\"simulate\":\"fail\"},"
                        + "{\"label\":\"never runs\"}]}", null);

        Run run = runRepository.findById(
                runService.runJob(TENANT, ACTOR, TOKEN, job.getId()).getId()).orElseThrow();

        assertEquals(RunStatus.FAILED, run.getStatus());
        assertEquals(2, run.getStepCompleted(), "stopped at the failing step");
        assertTrue(run.getError().contains("bad step"));
        assertTrue(!run.getLog().contains("never runs"), "later steps never execute");
    }

    @Test
    void retriedStepSucceedsOnASecondAttempt() {
        // "flaky" fails attempt 0, succeeds attempt 1 — with retries:1 the run passes.
        Job job = jobService.create(TENANT, ACTOR, TOKEN, project.getId(), "Flaky", null, null,
                "{\"steps\":[{\"label\":\"transient\",\"simulate\":\"flaky\",\"retries\":1}]}", null);

        Run run = runRepository.findById(
                runService.runJob(TENANT, ACTOR, TOKEN, job.getId()).getId()).orElseThrow();

        assertEquals(RunStatus.SUCCEEDED, run.getStatus());
        assertTrue(run.getLog().contains("retrying"), "log shows the retry");
    }

    @Test
    void retriesAreExhaustedThenTheStepFails() {
        // Always-fail step with retries:2 → 3 attempts, still fails.
        Job job = jobService.create(TENANT, ACTOR, TOKEN, project.getId(), "Doomed", null, null,
                "{\"steps\":[{\"label\":\"nope\",\"simulate\":\"fail\",\"retries\":2}]}", null);

        Run run = runRepository.findById(
                runService.runJob(TENANT, ACTOR, TOKEN, job.getId()).getId()).orElseThrow();

        assertEquals(RunStatus.FAILED, run.getStatus());
        assertTrue(run.getLog().contains("attempt 3/3"), "all attempts recorded");
    }

    @Test
    void continueOnErrorLetsThePipelineFinish() {
        Job job = jobService.create(TENANT, ACTOR, TOKEN, project.getId(), "Resilient", null, null,
                "{\"steps\":["
                        + "{\"label\":\"non-critical\",\"simulate\":\"fail\",\"continueOnError\":true},"
                        + "{\"label\":\"the important one\"}]}", null);

        Run run = runRepository.findById(
                runService.runJob(TENANT, ACTOR, TOKEN, job.getId()).getId()).orElseThrow();

        assertEquals(RunStatus.SUCCEEDED, run.getStatus());
        assertEquals(2, run.getStepCompleted(), "later steps still ran");
        assertTrue(run.getLog().contains("continue-on-error"));
        assertTrue(run.getLog().contains("ignored failure"));
    }

    @Test
    void manualTriggerIsAGatedMutation() {
        Job job = jobService.create(TENANT, ACTOR, TOKEN, project.getId(), "Backup",
                null, null, null, null);
        when(entitlementClient.checkActive(any()))
                .thenReturn(new EntitlementClient.Decision(false, "trial_expired", null, null));

        CoreException ex = assertThrows(CoreException.class,
                () -> runService.runJob(TENANT, ACTOR, TOKEN, job.getId()));
        assertEquals("trial_expired", ex.getError());
        assertEquals(0, runRepository.count(), "denied trigger leaves no run row");
    }

    @Test
    void runHistorySurvivesJobDeletion() {
        Job job = jobService.create(TENANT, ACTOR, TOKEN, project.getId(), "Backup", null, null,
                "{\"steps\":[{\"label\":\"one\"}]}", null);
        runService.runJob(TENANT, ACTOR, TOKEN, job.getId());
        jobService.delete(TENANT, TOKEN, job.getId());

        List<Run> runs = runService.list(TENANT, TOKEN, project.getId());
        assertEquals(1, runs.size());
        assertEquals("Backup", runs.get(0).getTargetName(), "snapshot outlives the definition");
    }

    // ------ per-target history (what a job's "History" opens) ------

    @Test
    void listScopedToOneJobReturnsOnlyThatJobsRuns() {
        Job a = jobService.create(TENANT, ACTOR, TOKEN, project.getId(), "Job A", null, null,
                "{\"steps\":[{\"label\":\"one\"}]}", null);
        Job b = jobService.create(TENANT, ACTOR, TOKEN, project.getId(), "Job B", null, null,
                "{\"steps\":[{\"label\":\"one\"}]}", null);
        runService.runJob(TENANT, ACTOR, TOKEN, a.getId());
        runService.runJob(TENANT, ACTOR, TOKEN, a.getId());
        runService.runJob(TENANT, ACTOR, TOKEN, b.getId());

        List<Run> onlyA = runService.list(TENANT, TOKEN, project.getId(), RunTargetType.JOB,
                a.getId());

        assertEquals(2, onlyA.size());
        assertTrue(onlyA.stream().allMatch(r -> r.getTargetId().equals(a.getId())));
        assertEquals(3, runService.list(TENANT, TOKEN, project.getId()).size(),
                "the unscoped list still shows the whole project");
    }

    @Test
    void aJobAndAWorkflowSharingAnIdAreNotTheSameHistory() {
        // Ids are per-table, so target TYPE is what stops job 7's history from
        // swallowing workflow 7's — this is why the two params travel together.
        Job job = jobService.create(TENANT, ACTOR, TOKEN, project.getId(), "Backup", null, null,
                "{\"steps\":[{\"label\":\"one\"}]}", null);
        runService.runJob(TENANT, ACTOR, TOKEN, job.getId());
        Run workflowRun = new Run();
        workflowRun.setTenantId(TENANT);
        workflowRun.setProjectId(project.getId());
        workflowRun.setTargetType(RunTargetType.WORKFLOW);
        workflowRun.setTargetId(job.getId()); // same number, different target
        workflowRun.setTargetName("Deploy");
        workflowRun.setStatus(RunStatus.SUCCEEDED);
        runRepository.save(workflowRun);

        assertEquals(1, runService.list(TENANT, TOKEN, project.getId(), RunTargetType.JOB,
                job.getId()).size());
        assertEquals(1, runService.list(TENANT, TOKEN, project.getId(), RunTargetType.WORKFLOW,
                job.getId()).size());
    }

    @Test
    void aQuietJobsHistoryIsNotCrowdedOutByANoisyNeighbour() {
        // The reason this filter is server-side: both queries cap at 200, so a
        // client filtering the project-wide page would lose the quiet job's run
        // as soon as the noisy one produced 200 newer ones.
        Job quiet = jobService.create(TENANT, ACTOR, TOKEN, project.getId(), "Quiet", null, null,
                "{\"steps\":[{\"label\":\"one\"}]}", null);
        Instant base = Instant.now().minus(Duration.ofHours(1));
        Run quietRun = new Run();
        quietRun.setTenantId(TENANT);
        quietRun.setProjectId(project.getId());
        quietRun.setTargetType(RunTargetType.JOB);
        quietRun.setTargetId(quiet.getId());
        quietRun.setTargetName("Quiet");
        quietRun.setStatus(RunStatus.SUCCEEDED);
        ReflectionTestUtils.setField(quietRun, "createdAt", base);
        runRepository.save(quietRun);
        for (int i = 0; i < 205; i++) {
            Run noisy = new Run();
            noisy.setTenantId(TENANT);
            noisy.setProjectId(project.getId());
            noisy.setTargetType(RunTargetType.JOB);
            noisy.setTargetId(quiet.getId() + 1);
            noisy.setTargetName("Noisy");
            noisy.setStatus(RunStatus.SUCCEEDED);
            // Every one strictly newer, so the cap is deterministic.
            ReflectionTestUtils.setField(noisy, "createdAt", base.plusSeconds(i + 1L));
            runRepository.save(noisy);
        }

        List<Run> projectWide = runService.list(TENANT, TOKEN, project.getId());
        assertEquals(200, projectWide.size(), "the project page is capped");
        assertTrue(projectWide.stream().noneMatch(r -> r.getTargetId().equals(quiet.getId())),
                "and the quiet job fell off it — filtering this list client-side would show none");

        assertEquals(1, runService.list(TENANT, TOKEN, project.getId(), RunTargetType.JOB,
                quiet.getId()).size(), "scoping server-side finds it");
    }

    @Test
    void halfATargetFilterIsRejected() {
        CoreException ex = assertThrows(CoreException.class,
                () -> runService.list(TENANT, TOKEN, project.getId(), RunTargetType.JOB, null));
        assertEquals("incomplete_target_filter", ex.getError());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    void scopedHistoryStaysTenantScopedAndRetentionBounded() {
        Job job = jobService.create(TENANT, ACTOR, TOKEN, project.getId(), "Backup", null, null,
                "{\"steps\":[{\"label\":\"one\"}]}", null);
        runService.runJob(TENANT, ACTOR, TOKEN, job.getId());

        assertEquals("project_not_found", assertThrows(CoreException.class,
                () -> runService.list(OTHER_TENANT, TOKEN, project.getId(), RunTargetType.JOB,
                        job.getId())).getError());

        // An OLD run of the same job: retention bounds the scoped page too.
        Run old = new Run();
        old.setTenantId(TENANT);
        old.setProjectId(project.getId());
        old.setTargetType(RunTargetType.JOB);
        old.setTargetId(job.getId());
        old.setTargetName("Backup");
        old.setStatus(RunStatus.SUCCEEDED);
        ReflectionTestUtils.setField(old, "createdAt", Instant.now().minus(Duration.ofDays(60)));
        runRepository.save(old);
        when(subscriptionInfoClient.historyDays(any(), any())).thenReturn(30);

        List<Run> visible = runService.list(TENANT, TOKEN, project.getId(), RunTargetType.JOB,
                job.getId());
        assertEquals(1, visible.size(), "the 60-day-old run is outside a 30-day plan window");
    }

    @Test
    void tenantsAreIsolated() {
        Job job = jobService.create(TENANT, ACTOR, TOKEN, project.getId(), "Backup",
                null, null, null, null);
        Run run = runRepository.findById(
                runService.runJob(TENANT, ACTOR, TOKEN, job.getId()).getId()).orElseThrow();

        CoreException ex = assertThrows(CoreException.class,
                () -> runService.get(OTHER_TENANT, TOKEN, run.getId()));
        assertEquals("run_not_found", ex.getError());
    }

    // ------ cancel ------

    @Test
    void cancelBeforeStartCancelsTheRun() {
        Run queued = new Run();
        queued.setTenantId(TENANT);
        queued.setProjectId(project.getId());
        queued.setTargetType(RunTargetType.JOB);
        queued.setTargetId(999L);
        queued.setTargetName("Queued job");
        queued = runRepository.save(queued);

        runService.cancel(TENANT, TOKEN, queued.getId());
        executionEngine.execute(queued.getId());

        Run run = runRepository.findById(queued.getId()).orElseThrow();
        assertEquals(RunStatus.CANCELED, run.getStatus());
        assertNull(run.getError());
    }

    @Test
    void cancellingAFinishedRunConflicts() {
        Job job = jobService.create(TENANT, ACTOR, TOKEN, project.getId(), "Backup",
                null, null, null, null);
        Run run = runService.runJob(TENANT, ACTOR, TOKEN, job.getId());

        CoreException ex = assertThrows(CoreException.class,
                () -> runService.cancel(TENANT, TOKEN, run.getId()));
        assertEquals("run_finished", ex.getError());
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    // ------ stats ------

    @Test
    void statsAggregateFinishedRunsPerTarget() {
        Job job = jobService.create(TENANT, ACTOR, TOKEN, project.getId(), "Flaky", null, null,
                "{\"steps\":[{\"label\":\"ok\"}]}", null);
        runService.runJob(TENANT, ACTOR, TOKEN, job.getId());
        jobService.update(TENANT, TOKEN, job.getId(), null, null, null,
                "{\"steps\":[{\"label\":\"bad\",\"simulate\":\"fail\"}]}", null);
        runService.runJob(TENANT, ACTOR, TOKEN, job.getId());

        RunService.RunStats stats = runService
                .statsForTarget(TENANT, RunTargetType.JOB, job.getId()).orElseThrow();
        assertEquals(2, stats.total());
        assertEquals(50, stats.successRate());
        assertNotNull(stats.lastRunAt());
        assertNotNull(stats.avgDurationMs());
    }

    // ------ retention ------

    @Test
    void historyReadsAreBoundedByThePlansHistoryDays() {
        Job job = jobService.create(TENANT, ACTOR, TOKEN, project.getId(), "Backup", null, null,
                "{\"steps\":[{\"label\":\"one\"}]}", null);
        // createdAt is updatable=false, so a backdated run must be INSERTED old.
        Run old = new Run();
        old.setTenantId(TENANT);
        old.setProjectId(project.getId());
        old.setTargetType(RunTargetType.JOB);
        old.setTargetId(job.getId());
        old.setTargetName("Backup");
        old.setStatus(RunStatus.SUCCEEDED);
        ReflectionTestUtils.setField(old, "createdAt", Instant.now().minus(Duration.ofDays(45)));
        Long oldId = runRepository.save(old).getId();
        Run recent = runService.runJob(TENANT, ACTOR, TOKEN, job.getId());

        when(subscriptionInfoClient.historyDays(any(), any())).thenReturn(30);
        List<Run> visible = runService.list(TENANT, TOKEN, project.getId());
        assertEquals(1, visible.size());
        assertEquals(recent.getId(), visible.get(0).getId());
        CoreException ex = assertThrows(CoreException.class,
                () -> runService.get(TENANT, TOKEN, oldId));
        assertEquals("run_not_found", ex.getError(), "direct links are bounded too");

        // Unknown retention (service down / no plan) must never hide data.
        when(subscriptionInfoClient.historyDays(any(), any())).thenReturn(null);
        assertEquals(2, runService.list(TENANT, TOKEN, project.getId()).size());
    }

    // ------ scheduler ------

    @Test
    void invalidCronScheduleIsRejectedOnSave() {
        CoreException ex = assertThrows(CoreException.class,
                () -> jobService.create(TENANT, ACTOR, TOKEN, project.getId(), "Nightly",
                        null, null, null, "not a cron"));
        assertEquals("invalid_schedule", ex.getError());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    void fiveFieldUnixCronsAreAcceptedAndComputeNextRun() {
        Job job = jobService.create(TENANT, ACTOR, TOKEN, project.getId(), "Nightly",
                null, null, null, "0 2 * * *");
        assertNotNull(job.getNextRunAt());
        assertTrue(job.getNextRunAt().isAfter(Instant.now()));
    }

    @Test
    void schedulerFiresDueJobsAndAdvancesNextRun() {
        Job job = jobService.create(TENANT, ACTOR, TOKEN, project.getId(), "Nightly", null, null,
                "{\"steps\":[{\"label\":\"sweep\"}]}", "*/5 * * * *");
        job.setNextRunAt(Instant.now().minusSeconds(60)); // force due
        jobRepository.save(job);

        jobScheduler.poll();

        List<Run> runs = runService.list(TENANT, TOKEN, project.getId());
        assertEquals(1, runs.size());
        assertEquals(RunTrigger.SCHEDULE, runs.get(0).getTrigger());
        assertEquals("scheduler", runs.get(0).getTriggeredBy());
        assertEquals(RunStatus.SUCCEEDED, runs.get(0).getStatus());
        assertTrue(jobRepository.findById(job.getId()).orElseThrow()
                .getNextRunAt().isAfter(Instant.now()), "fire time advanced");
    }

    @Test
    void disabledJobsAreNeverScheduled() {
        Job job = jobService.create(TENANT, ACTOR, TOKEN, project.getId(), "Nightly", null, null,
                null, "*/5 * * * *");
        job.setNextRunAt(Instant.now().minusSeconds(60));
        job.setEnabled(false);
        jobRepository.save(job);

        jobScheduler.poll();

        assertEquals(0, runRepository.count());
    }
}
