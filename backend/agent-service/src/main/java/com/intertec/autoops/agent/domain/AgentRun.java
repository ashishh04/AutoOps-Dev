package com.intertec.autoops.agent.domain;

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
 * One execution of an agent: the question, the conversation it produced, and
 * the answer.
 *
 * <p>The row exists because an agent run is not a request/response. It calls a
 * model, the model asks for a tool, the tool takes minutes, the model is asked
 * again — and somewhere in the middle it may stop for a human and not move
 * again until tomorrow. None of that survives in memory, so all of it lives
 * here.
 *
 * <p>{@code model} and {@code vendor} are COPIES, not lookups. An agent's model
 * can be changed after this run finished, and the record has to keep saying
 * which model actually produced this answer.
 */
@Entity
@Table(name = "agent_runs")
public class AgentRun {

    /**
     * PENDING → RUNNING → SUCCEEDED | FAILED | CANCELLED, with
     * AWAITING_APPROVAL as the one state that can be left and re-entered.
     */
    public enum Status {
        PENDING, RUNNING, AWAITING_APPROVAL, SUCCEEDED, FAILED, CANCELLED;

        public boolean isTerminal() {
            return this == SUCCEEDED || this == FAILED || this == CANCELLED;
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "agent_id", nullable = false)
    private Long agentId;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "ENUM('PENDING','RUNNING','AWAITING_APPROVAL',"
            + "'SUCCEEDED','FAILED','CANCELLED')")
    private Status status = Status.PENDING;

    @Column(nullable = false, columnDefinition = "MEDIUMTEXT")
    private String input;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String output;

    /**
     * The resumable state.
     *
     * <p>Under the Python runtime this is the reducer's own state blob, written
     * by it and read by it — this service stores the string and never parses
     * it. Under the legacy Java loop it is the provider-neutral message history
     * from the V4 migration. {@link #stateVersion} is how the two are told
     * apart, and how a state written by a build that is no longer running is
     * refused rather than misread.
     */
    @Column(columnDefinition = "MEDIUMTEXT")
    private String transcript;

    /** Null on legacy-loop runs; set to the runtime's STATE_VERSION otherwise. */
    @Column(name = "state_version")
    private Integer stateVersion;

    /** Which phase of its graph the run is in — surfaced in the run view. */
    @Column(length = 32)
    private String phase;

    /** Langfuse trace for this run, so the console can deep-link to it. */
    @Column(name = "trace_id", length = 128)
    private String traceId;

    @Column(length = 128)
    private String model;

    @Column(length = 32)
    private String vendor;

    @Column(name = "step_count", nullable = false)
    private int stepCount;

    @Column(name = "max_steps", nullable = false)
    private int maxSteps = 12;

    /** core-service approval id, set only while AWAITING_APPROVAL. */
    @Column(name = "approval_reference", length = 64)
    private String approvalReference;

    /** The tool_use id the approval belongs to; the result must carry it back. */
    @Column(name = "pending_tool_id", length = 128)
    private String pendingToolId;

    /**
     * The outstanding tool calls of the current turn, and what has been
     * collected against them so far. Both null except between a CALL_TOOLS
     * directive and the moment every call in it is answered.
     *
     * <p>These exist because the transcript now belongs to the runtime. A model
     * can ask for several tools at once and every vendor requires them all to
     * be answered together, so a turn where the second call needs a human has
     * to hold the first call's result somewhere for however long the human
     * takes. That used to be a partial message on the transcript; it is these
     * two columns now.
     */
    @Column(name = "pending_calls", columnDefinition = "MEDIUMTEXT")
    private String pendingCalls;

    @Column(name = "pending_results", columnDefinition = "MEDIUMTEXT")
    private String pendingResults;

    @Column(columnDefinition = "TEXT")
    private String error;

    /**
     * Claims the final report could not substantiate, if any.
     *
     * <p>Null on a clean run. Non-null means the report shipped carrying a
     * visible UNVERIFIED banner — the run still SUCCEEDED, because a flagged
     * report during an incident is worth more than no report.
     */
    @Column(name = "uncited_claims", columnDefinition = "TEXT")
    private String uncitedClaims;

    @Column(name = "prompt_tokens", nullable = false)
    private long promptTokens;

    @Column(name = "completion_tokens", nullable = false)
    private long completionTokens;

    @Column(name = "created_by", length = 255)
    private String createdBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    /**
     * What this run claims to have covered: a JSON ARRAY of scope objects, one
     * per subject kind.
     *
     * <p>Written twice — at start it is the intent, at completion it is what was
     * actually reached, and the second write may only narrow the first. Stored
     * as text and parsed through {@code RunScope} rather than mapped, because
     * the shape is a closed set of forms with rules Hibernate could not enforce,
     * and a scope that parses loosely is a claim that reaps work nobody looked
     * at.
     *
     * <p>An array rather than one object because a run legitimately covers
     * several kinds: the public exposure auditor correlates cloud resources with
     * principals, and the chain between them is the point.
     */
    @Column(name = "subject_scope", columnDefinition = "JSON")
    private String subjectScope;

    /** How many subjects the run actually looked at, for the coverage gauge. */
    @Column(name = "subjects_evaluated")
    private Integer subjectsEvaluated;

    /** How many verdicts it emitted, so a silent run is distinguishable from a clean one. */
    @Column(name = "verdicts_emitted")
    private Integer verdictsEmitted;

    /**
     * Whether the coverage claim can be trusted, which is NOT the same question
     * as whether the run finished.
     *
     * <p>A roll-up. Coverage is decided <b>per subject kind</b> inside
     * {@code subject_scope}, because a run that enumerated every bucket and then
     * had its IAM call throw has real coverage of one kind and none of the
     * other. FAILED is the exception and applies to the whole run: a claim that
     * was refused, or a run nobody ever heard from again, grounds nothing.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "scope_status",
            columnDefinition = "ENUM('RUNNING','COMPLETE','PARTIAL','FAILED')")
    private ScopeStatus scopeStatus;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    public AgentRun() {
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

    public Long getAgentId() {
        return agentId;
    }

    public void setAgentId(Long agentId) {
        this.agentId = agentId;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public String getInput() {
        return input;
    }

    public void setInput(String input) {
        this.input = input;
    }

    public String getOutput() {
        return output;
    }

    public void setOutput(String output) {
        this.output = output;
    }

    public String getTranscript() {
        return transcript;
    }

    public void setTranscript(String transcript) {
        this.transcript = transcript;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getVendor() {
        return vendor;
    }

    public void setVendor(String vendor) {
        this.vendor = vendor;
    }

    public int getStepCount() {
        return stepCount;
    }

    public void setStepCount(int stepCount) {
        this.stepCount = stepCount;
    }

    public int getMaxSteps() {
        return maxSteps;
    }

    public void setMaxSteps(int maxSteps) {
        this.maxSteps = maxSteps;
    }

    public String getApprovalReference() {
        return approvalReference;
    }

    public void setApprovalReference(String approvalReference) {
        this.approvalReference = approvalReference;
    }

    public String getPendingToolId() {
        return pendingToolId;
    }

    public void setPendingToolId(String pendingToolId) {
        this.pendingToolId = pendingToolId;
    }

    public Integer getStateVersion() {
        return stateVersion;
    }

    public void setStateVersion(Integer stateVersion) {
        this.stateVersion = stateVersion;
    }

    public String getPhase() {
        return phase;
    }

    public void setPhase(String phase) {
        this.phase = phase;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getPendingCalls() {
        return pendingCalls;
    }

    public void setPendingCalls(String pendingCalls) {
        this.pendingCalls = pendingCalls;
    }

    public String getPendingResults() {
        return pendingResults;
    }

    public void setPendingResults(String pendingResults) {
        this.pendingResults = pendingResults;
    }

    public String getUncitedClaims() {
        return uncitedClaims;
    }

    public void setUncitedClaims(String uncitedClaims) {
        this.uncitedClaims = uncitedClaims;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public long getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(long promptTokens) {
        this.promptTokens = promptTokens;
    }

    public long getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(long completionTokens) {
        this.completionTokens = completionTokens;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
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

    public String getSubjectScope() {
        return subjectScope;
    }

    public void setSubjectScope(String subjectScope) {
        this.subjectScope = subjectScope;
    }

    public Integer getSubjectsEvaluated() {
        return subjectsEvaluated;
    }

    public void setSubjectsEvaluated(Integer subjectsEvaluated) {
        this.subjectsEvaluated = subjectsEvaluated;
    }

    public Integer getVerdictsEmitted() {
        return verdictsEmitted;
    }

    public void setVerdictsEmitted(Integer verdictsEmitted) {
        this.verdictsEmitted = verdictsEmitted;
    }

    public ScopeStatus getScopeStatus() {
        return scopeStatus;
    }

    public void setScopeStatus(ScopeStatus scopeStatus) {
        this.scopeStatus = scopeStatus;
    }

    /**
     * Whether any of this run's coverage could drive a reap.
     *
     * <p>A screen, not the decision. <b>The per-element coverage verdict inside
     * {@code subject_scope} is what decides whether a given subject kind
     * reaps</b>; this only says the run is worth opening. PARTIAL qualifies
     * because it means <i>mixed</i>: a run whose IAM call threw still has real
     * coverage of every bucket it enumerated, and discarding that every time one
     * dimension flakes throws away most of the coverage the estate produces.
     *
     * <p>Deliberately positive — a status added later is excluded until somebody
     * decides otherwise, rather than inheriting permission to resolve findings.
     */
    public boolean hasUsableCoverage() {
        return scopeStatus == ScopeStatus.COMPLETE || scopeStatus == ScopeStatus.PARTIAL;
    }

    /**
     * The run-level roll-up of per-element coverage. See {@link #getScopeStatus()}.
     *
     * <p><b>A summary for indexing and for reading, never the authority.</b> The
     * coverage verdict on each element of {@code subject_scope} decides whether
     * that kind reaps.
     */
    public enum ScopeStatus {
        /** The run declared a scope and is still working through it. */
        RUNNING,
        /** Every declared kind reached COMPLETE. */
        COMPLETE,
        /** Mixed — some kinds complete, some partial or skipped. */
        PARTIAL,
        /** The claim was refused, or the run was abandoned. Nothing reaps. */
        FAILED
    }
}
