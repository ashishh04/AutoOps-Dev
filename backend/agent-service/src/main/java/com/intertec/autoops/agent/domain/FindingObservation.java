package com.intertec.autoops.agent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One accepted verdict, kept.
 *
 * <p>Append-only, and deliberately NOT written on every sighting. An agent
 * sweeping nightly finds that most of what it reports has not changed; a row
 * for each buys nothing the finding's own counters do not already carry, and
 * costs a table somebody has to prune. Observations are written when something
 * actually happened: the finding was created, its substance changed, or it came
 * back from the dead.
 */
@Entity
@Table(name = "finding_observations")
public class FindingObservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "finding_id", nullable = false)
    private Long findingId;

    /**
     * The verdict's own id, unique per tenant.
     *
     * <p>Replay protection independent of finding identity. A network retry of
     * one ingest call must not increment the occurrence count, and the only
     * thing that can tell "the agent found this again" apart from "the HTTP
     * call happened twice" is this.
     */
    @Column(name = "verdict_id", nullable = false, length = 64)
    private String verdictId;

    @Column(name = "run_id")
    private Long runId;

    @Column(name = "agent_version", nullable = false, length = 32)
    private String agentVersion;

    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "is_material", nullable = false)
    private boolean material;

    @Column(nullable = false, columnDefinition = "JSON")
    private String payload;

    public Long getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public Long getFindingId() {
        return findingId;
    }

    public void setFindingId(Long findingId) {
        this.findingId = findingId;
    }

    public String getVerdictId() {
        return verdictId;
    }

    public void setVerdictId(String verdictId) {
        this.verdictId = verdictId;
    }

    public Long getRunId() {
        return runId;
    }

    public void setRunId(Long runId) {
        this.runId = runId;
    }

    public String getAgentVersion() {
        return agentVersion;
    }

    public void setAgentVersion(String agentVersion) {
        this.agentVersion = agentVersion;
    }

    public Instant getObservedAt() {
        return observedAt;
    }

    public void setObservedAt(Instant observedAt) {
        this.observedAt = observedAt;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public boolean isMaterial() {
        return material;
    }

    public void setMaterial(boolean material) {
        this.material = material;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }
}
