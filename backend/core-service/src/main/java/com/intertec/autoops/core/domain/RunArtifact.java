package com.intertec.autoops.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A file a run produced. The bytes live in object storage; this is the record
 * of what they are and where they went.
 *
 * <p>Deliberately no FK to {@link Run}: the row has to OUTLIVE its run, because
 * it is the only thing that knows which object in the bucket belongs to that
 * run. Retention purges the object first and the row second — see the note on
 * {@code V36__run_artifacts.sql}.
 *
 * <p>{@code storageKey} is opaque to everything but the artifact store, which
 * is what lets the same row address MinIO in compose and S3 in AWS.
 */
@Entity
@Table(name = "run_artifacts")
public class RunArtifact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    /** Null for a run-level artifact that no single step owns. */
    @Column(name = "step_index")
    private Integer stepIndex;

    @Column(name = "step_label", length = 128)
    private String stepLabel;

    /** A bare filename — never a path. Sanitised before it ever gets here. */
    @Column(nullable = false, length = 255)
    private String filename;

    @Column(name = "content_type", nullable = false, length = 128)
    private String contentType = "application/octet-stream";

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "storage_key", nullable = false, length = 512)
    private String storageKey;

    /**
     * Lets a consumer tell "unchanged since yesterday" from "a different file
     * that happens to share a name".
     */
    // CHAR(64), not VARCHAR: a SHA-256 hex digest is always exactly 64
    // characters, which is what V36 declares. `length = 64` alone makes
    // Hibernate expect varchar(64), and ddl-auto: validate then refuses to
    // build the SessionFactory at all — core-service does not start.
    @Column(nullable = false, columnDefinition = "CHAR(64)")
    private String sha256;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    /** Set once the sweeper has deleted the object; the row lingers briefly. */
    @Column(name = "purged_at")
    private Instant purgedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public Long getRunId() {
        return runId;
    }

    public void setRunId(Long runId) {
        this.runId = runId;
    }

    public Integer getStepIndex() {
        return stepIndex;
    }

    public void setStepIndex(Integer stepIndex) {
        this.stepIndex = stepIndex;
    }

    public String getStepLabel() {
        return stepLabel;
    }

    public void setStepLabel(String stepLabel) {
        this.stepLabel = stepLabel;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public void setStorageKey(String storageKey) {
        this.storageKey = storageKey;
    }

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getPurgedAt() {
        return purgedAt;
    }

    public void setPurgedAt(Instant purgedAt) {
        this.purgedAt = purgedAt;
    }
}
