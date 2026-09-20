package com.intertec.autoops.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One execution of a job or workflow. The target's name and definition are
 * snapshotted at trigger time so history stays truthful after edits/deletes
 * (deliberately no FK to the target). {@code createdAt} is set in code (not a
 * DB default) because the retention bound filters on it.
 */
@Entity
@Table(name = "runs")
public class Run {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, columnDefinition = "ENUM('JOB','WORKFLOW')")
    private RunTargetType targetType;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    @Column(name = "target_name", nullable = false, length = 128)
    private String targetName;

    /** Snapshot of the steps/nodes JSON that this run executed. */
    @Column(columnDefinition = "MEDIUMTEXT")
    private String definition;

    /**
     * JSON object of the values supplied when this run was triggered, or null
     * when the target declares no inputs. Snapshotted here for the same reason
     * {@code definition} is: it is the record of what this run actually did,
     * and editing the workflow later must not rewrite its history.
     */
    @Column(columnDefinition = "TEXT")
    private String inputs;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false,
            columnDefinition = "ENUM('QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELED')")
    private RunStatus status = RunStatus.QUEUED;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false,
            columnDefinition = "ENUM('MANUAL','SCHEDULE','WEBHOOK','AGENT')")
    private RunTrigger trigger = RunTrigger.MANUAL;

    @Column(name = "triggered_by", length = 255)
    private String triggeredBy;

    @Column(name = "step_total", nullable = false)
    private int stepTotal;

    @Column(name = "step_completed", nullable = false)
    private int stepCompleted;

    /** Set by cancel; the engine checks it between steps. */
    @Column(name = "cancel_requested", nullable = false)
    private boolean cancelRequested;

    /** What the run DID: the engine's trace, for an operator. */
    @Column(columnDefinition = "MEDIUMTEXT")
    private String log;

    /**
     * What the run PRODUCED: the deliverable, for whoever asked for it.
     *
     * <p>Kept apart from {@link #log} because they have different audiences. A
     * report workflow's customer wants the report, not a screenful of node
     * timings and an echo of their own input above it — which is exactly what
     * one shared field gave them.
     *
     * <p>Null means this run produced no document, which is the honest state
     * for every job that restarts a service. An empty string would claim it
     * produced one.
     */
    @Column(columnDefinition = "MEDIUMTEXT")
    private String output;

    @Column(length = 512)
    private String error;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    /**
     * When this run was reported STALLED. Set once and never cleared — a run
     * that overruns is worth saying once, not every watchdog sweep for as long
     * as it keeps going.
     */
    @Column(name = "stalled_notified_at")
    private Instant stalledNotifiedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public Run() {
    }

    public Long getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public RunTargetType getTargetType() {
        return targetType;
    }

    public void setTargetType(RunTargetType targetType) {
        this.targetType = targetType;
    }

    public Long getTargetId() {
        return targetId;
    }

    public void setTargetId(Long targetId) {
        this.targetId = targetId;
    }

    public String getTargetName() {
        return targetName;
    }

    public void setTargetName(String targetName) {
        this.targetName = targetName;
    }

    public String getDefinition() {
        return definition;
    }

    public void setDefinition(String definition) {
        this.definition = definition;
    }

    public String getInputs() {
        return inputs;
    }

    public void setInputs(String inputs) {
        this.inputs = inputs;
    }

    public RunStatus getStatus() {
        return status;
    }

    public void setStatus(RunStatus status) {
        this.status = status;
    }

    public RunTrigger getTrigger() {
        return trigger;
    }

    public void setTrigger(RunTrigger trigger) {
        this.trigger = trigger;
    }

    public String getTriggeredBy() {
        return triggeredBy;
    }

    public void setTriggeredBy(String triggeredBy) {
        this.triggeredBy = triggeredBy;
    }

    public int getStepTotal() {
        return stepTotal;
    }

    public void setStepTotal(int stepTotal) {
        this.stepTotal = stepTotal;
    }

    public int getStepCompleted() {
        return stepCompleted;
    }

    public void setStepCompleted(int stepCompleted) {
        this.stepCompleted = stepCompleted;
    }

    public boolean isCancelRequested() {
        return cancelRequested;
    }

    public void setCancelRequested(boolean cancelRequested) {
        this.cancelRequested = cancelRequested;
    }

    public String getLog() {
        return log;
    }

    public void setLog(String log) {
        this.log = log;
    }

    public String getOutput() {
        return output;
    }

    public void setOutput(String output) {
        this.output = output;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public Instant getStalledNotifiedAt() {
        return stalledNotifiedAt;
    }

    public void setStalledNotifiedAt(Instant stalledNotifiedAt) {
        this.stalledNotifiedAt = stalledNotifiedAt;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}