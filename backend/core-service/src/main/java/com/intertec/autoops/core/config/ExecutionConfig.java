package com.intertec.autoops.core.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Bounded worker pools for run execution (one thread per in-flight run; excess
 * runs wait QUEUED) and the scheduling infrastructure for the cron poller.
 *
 * <h2>Why there are TWO pools</h2>
 *
 * A workflow run and a job run are not the same kind of work, and mixing them
 * on one fixed pool deadlocks.
 *
 * <p>A workflow run holds its thread while it waits on agent-runtime. If that
 * workflow contains a {@code job} node, the runtime calls BACK into this
 * service to dispatch an automation — which needs a thread of its own. With one
 * pool of four, four concurrent workflows occupy every thread, the jobs they
 * spawn queue behind them forever, and each workflow waits for a job that can
 * never start. Nothing errors; everything stops.
 *
 * <p>So workflow runs get their own pool. A workflow can then starve only other
 * workflows, never the automations it depends on. The two are sized separately
 * because they are bounded by different things: job threads by how much real
 * execution this host should drive at once, workflow threads by how many
 * conversations with a model and a runtime we are willing to hold open.
 */
@Configuration
@EnableScheduling
public class ExecutionConfig {

    /** Job runs: real execution, one thread each. */
    @Bean(name = "executionTaskExecutor")
    public ThreadPoolTaskExecutor executionTaskExecutor(CoreProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("run-exec-");
        executor.setCorePoolSize(properties.getExecution().getPoolSize());
        executor.setMaxPoolSize(properties.getExecution().getPoolSize());
        executor.setQueueCapacity(500);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }

    /**
     * Workflow runs: mostly waiting, and the only kind of run that can cause
     * another run. Separate from the pool above so it can never starve one.
     */
    @Bean(name = "workflowTaskExecutor")
    public ThreadPoolTaskExecutor workflowTaskExecutor(CoreProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("wf-exec-");
        executor.setCorePoolSize(properties.getExecution().getWorkflowPoolSize());
        executor.setMaxPoolSize(properties.getExecution().getWorkflowPoolSize());
        executor.setQueueCapacity(500);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }
}