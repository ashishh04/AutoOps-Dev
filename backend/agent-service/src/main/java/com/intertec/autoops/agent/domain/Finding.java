package com.intertec.autoops.agent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A durable problem, as opposed to one emission about it.
 *
 * <p>An agent run emits VERDICTS. A verdict is a moment — this run, this
 * evidence, this wording. A finding is the thing the verdict is about, and it
 * outlives every run that reported it: it accumulates occurrences, it can be
 * dismissed with an expiry, it comes back when the dismissal lapses, and it
 * closes when somebody fixes it or when it stops being observed.
 *
 * <p><b>Two hashes, and the distinction is the whole design.</b>
 * {@code idempotencyKey} answers <i>is this the same finding</i>; it is
 * composed once by the agent runtime and frozen per
 * {@code idempotencyKeyVersion}. {@code contentHash} answers <i>has the
 * substance changed</i>. Keeping them apart is what lets a dismissed finding
 * stay quiet while its numbers drift, and resurface when they do more than
 * drift. One hash for both would force a choice between re-filing on every
 * rounding change and never noticing that a $40/month problem became $4,000.
 */
@Entity
@Table(name = "findings")
public class Finding {

    /**
     * Where a finding is in its life.
     *
     * <p>{@link #STALE} and {@link #RESOLVED} are deliberately not the same
     * state. RESOLVED means somebody or something acted. STALE means it
     * stopped being observed and nobody knows why — the agent may have crashed.
     * Collapsing them destroys the only signal that separates "we fixed it"
     * from "it stopped being reported", and the second one looks identical to
     * success on every dashboard.
     */
    public enum State { OPEN, ACKNOWLEDGED, SUPPRESSED, RESOLVED, STALE, SUPERSEDED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    /**
     * Part of the unique key, not of the payload.
     *
     * <p>An agent author will eventually change what goes into the hash. With
     * the version in the key that forks cleanly and is a declared migration;
     * buried in the payload it would silently duplicate every finding in the
     * estate, and the duplicates would look like new problems.
     */
    @Column(name = "idempotency_key_version", nullable = false)
    private Short idempotencyKeyVersion = 1;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "agent_name", nullable = false, length = 128)
    private String agentName;

    @Column(name = "agent_version", nullable = false, length = 32)
    private String agentVersion;

    @Column(nullable = false, length = 64)
    private String category;

    @Column(name = "subject_kind", nullable = false, length = 64)
    private String subjectKind = "unknown";

    @Column(name = "subject_id", nullable = false, length = 512)
    private String subjectId = "";

    /**
     * {@code sha256(subject_id)}, which is what the reaper actually joins on.
     *
     * <p>A hash rather than the id because {@code subject_id VARCHAR(512)} costs
     * 2048 bytes of MySQL's 3072-byte index budget in utf8mb4, and two of those
     * in one composite is error 1071. {@code BINARY(32)} is fixed width.
     *
     * <p>Added to the entity after the first real finding landed with it NULL:
     * V7 created the column and nothing ever wrote it, so
     * {@code idx_findings_subject_hash} matched nothing and
     * {@code overclaimSuspects}' {@code COUNT(DISTINCT subject_id_hash)} counted
     * zero — NULLs are not counted, so that gauge read all-clear for a second
     * independent reason.
     */
    @Column(name = "subject_id_hash", columnDefinition = "BINARY(32)")
    private byte[] subjectIdHash;

    @Column(name = "service_ref")
    private String serviceRef;

    @Column(nullable = false, length = 32)
    private String environment = "unknown";

    @Column(length = 16)
    private String severity;

    @Column(name = "risk_tier", length = 16)
    private String riskTier;

    private BigDecimal confidence;

    @Column(name = "confidence_band", length = 16)
    private String confidenceBand;

    @Column(name = "priority_score")
    private BigDecimal priorityScore;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition =
            "ENUM('OPEN','ACKNOWLEDGED','SUPPRESSED','RESOLVED','STALE','SUPERSEDED')")
    private State state = State.OPEN;

    @Column(name = "state_reason")
    private String stateReason;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "last_material_change_at", nullable = false)
    private Instant lastMaterialChangeAt;

    @Column(name = "state_changed_at", nullable = false)
    private Instant stateChangedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Every sighting, including the ones that changed nothing. */
    @Column(name = "occurrence_count", nullable = false)
    private int occurrenceCount = 1;

    /**
     * How many times this came BACK after being closed.
     *
     * <p>The number that says a fix did not hold, and the one worth putting in
     * front of a customer — a finding on its fourth episode is a different
     * conversation from one seen four hundred times without ever closing.
     */
    @Column(name = "episode_count", nullable = false)
    private int episodeCount = 1;

    @Column(name = "material_change_count", nullable = false)
    private int materialChangeCount = 0;

    @Column(name = "head_observation_id")
    private Long headObservationId;

    @Column(nullable = false, columnDefinition = "JSON")
    private String payload;

    @Column(name = "last_run_id")
    private Long lastRunId;

    private String assignee;

    /** Whether this finding is currently something anybody should be shown. */
    public boolean isLive() {
        return state == State.OPEN || state == State.ACKNOWLEDGED;
    }

    /** Whether it had been closed, which makes the next sighting a regression. */
    public boolean isClosed() {
        return state == State.RESOLVED || state == State.STALE;
    }

    // ------------------------------------------------------------ accessors ---

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

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public Short getIdempotencyKeyVersion() {
        return idempotencyKeyVersion;
    }

    public void setIdempotencyKeyVersion(Short idempotencyKeyVersion) {
        this.idempotencyKeyVersion = idempotencyKeyVersion;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public String getAgentName() {
        return agentName;
    }

    public void setAgentName(String agentName) {
        this.agentName = agentName;
    }

    public String getAgentVersion() {
        return agentVersion;
    }

    public void setAgentVersion(String agentVersion) {
        this.agentVersion = agentVersion;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getSubjectKind() {
        return subjectKind;
    }

    public void setSubjectKind(String subjectKind) {
        this.subjectKind = subjectKind;
    }

    public String getSubjectId() {
        return subjectId;
    }

    public void setSubjectId(String subjectId) {
        this.subjectId = subjectId;
    }

    public String getServiceRef() {
        return serviceRef;
    }

    public void setServiceRef(String serviceRef) {
        this.serviceRef = serviceRef;
    }

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getRiskTier() {
        return riskTier;
    }

    public void setRiskTier(String riskTier) {
        this.riskTier = riskTier;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public void setConfidence(BigDecimal confidence) {
        this.confidence = confidence;
    }

    public String getConfidenceBand() {
        return confidenceBand;
    }

    public void setConfidenceBand(String confidenceBand) {
        this.confidenceBand = confidenceBand;
    }

    public BigDecimal getPriorityScore() {
        return priorityScore;
    }

    public void setPriorityScore(BigDecimal priorityScore) {
        this.priorityScore = priorityScore;
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public String getStateReason() {
        return stateReason;
    }

    public void setStateReason(String stateReason) {
        this.stateReason = stateReason;
    }

    public Instant getFirstSeenAt() {
        return firstSeenAt;
    }

    public void setFirstSeenAt(Instant firstSeenAt) {
        this.firstSeenAt = firstSeenAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(Instant lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }

    public Instant getLastMaterialChangeAt() {
        return lastMaterialChangeAt;
    }

    public void setLastMaterialChangeAt(Instant lastMaterialChangeAt) {
        this.lastMaterialChangeAt = lastMaterialChangeAt;
    }

    public Instant getStateChangedAt() {
        return stateChangedAt;
    }

    public void setStateChangedAt(Instant stateChangedAt) {
        this.stateChangedAt = stateChangedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public int getOccurrenceCount() {
        return occurrenceCount;
    }

    public void setOccurrenceCount(int occurrenceCount) {
        this.occurrenceCount = occurrenceCount;
    }

    public int getEpisodeCount() {
        return episodeCount;
    }

    public void setEpisodeCount(int episodeCount) {
        this.episodeCount = episodeCount;
    }

    public int getMaterialChangeCount() {
        return materialChangeCount;
    }

    public void setMaterialChangeCount(int materialChangeCount) {
        this.materialChangeCount = materialChangeCount;
    }

    public Long getHeadObservationId() {
        return headObservationId;
    }

    public void setHeadObservationId(Long headObservationId) {
        this.headObservationId = headObservationId;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public Long getLastRunId() {
        return lastRunId;
    }

    public void setLastRunId(Long lastRunId) {
        this.lastRunId = lastRunId;
    }

    public String getAssignee() {
        return assignee;
    }

    public void setAssignee(String assignee) {
        this.assignee = assignee;
    }

    public byte[] getSubjectIdHash() {
        return subjectIdHash;
    }

    public void setSubjectIdHash(byte[] subjectIdHash) {
        this.subjectIdHash = subjectIdHash;
    }
}
