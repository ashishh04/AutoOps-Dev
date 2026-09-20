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
 * A dismissal, as a record rather than a flag.
 *
 * <p>A boolean on the finding cannot answer the only questions that get asked
 * after something nobody saw turns into an outage: who silenced this, why,
 * how widely, and when does it come back. So dismissal is its own row, it has
 * an author and an expiry, and revoking it is an event rather than an edit.
 */
@Entity
@Table(name = "finding_suppressions")
public class FindingSuppression {

    /**
     * How wide the silence is.
     *
     * <p>Evaluated narrowest-first on ingest, and the winner is recorded on the
     * transition — "why did this never appear" has no answer otherwise.
     *
     * <p>{@link #SERVICE_CATEGORY} and {@link #CATEGORY_GLOBAL} are convenient
     * and quietly enormous: either can hide hundreds of findings nobody ever
     * sees. They are why every list response reports how many it excluded.
     */
    public enum Scope { FINDING, SUBJECT_CATEGORY, SERVICE_CATEGORY, CATEGORY_GLOBAL }

    /**
     * Why, from a closed set.
     *
     * <p>Free text alone is how a backlog becomes fiction: everything is
     * dismissed "for now" and nobody can later tell an accepted risk from a
     * false positive from something somebody meant to return to.
     */
    public enum ReasonCode {
        BY_DESIGN, COMPENSATING_CONTROL, ACCEPTED_RISK, FALSE_POSITIVE, DEFERRED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition =
            "ENUM('FINDING','SUBJECT_CATEGORY','SERVICE_CATEGORY','CATEGORY_GLOBAL')")
    private Scope scope;

    @Column(name = "finding_id")
    private Long findingId;

    @Column(name = "subject_kind", length = 64)
    private String subjectKind;

    @Column(name = "subject_id", length = 512)
    private String subjectId;

    @Column(name = "service_ref")
    private String serviceRef;

    @Column(length = 64)
    private String category;

    @Column(nullable = false, length = 1024)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", nullable = false, columnDefinition =
            "ENUM('BY_DESIGN','COMPENSATING_CONTROL','ACCEPTED_RISK','FALSE_POSITIVE','DEFERRED')")
    private ReasonCode reasonCode;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    /** Null means permanent, which the API restricts to an elevated role. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_by")
    private String revokedBy;

    /**
     * False by default, and that default is the point.
     *
     * <p>A dismissal covers the problem AS IT WAS. If the substance changes
     * materially the finding comes back, because what somebody accepted is no
     * longer what is true. Setting this true is an explicit statement that the
     * acceptance holds however the numbers move.
     */
    @Column(name = "survives_material_change", nullable = false)
    private boolean survivesMaterialChange = false;

    /** Live: not revoked, and not past its expiry. */
    public boolean isActiveAt(Instant when) {
        return revokedAt == null && (expiresAt == null || expiresAt.isAfter(when));
    }

    /** Whether this suppression's scope covers a given finding. */
    public boolean covers(Finding finding) {
        return switch (scope) {
            case FINDING -> finding.getId() != null && finding.getId().equals(findingId);
            case SUBJECT_CATEGORY ->
                    finding.getSubjectId().equals(subjectId) && finding.getCategory().equals(category);
            case SERVICE_CATEGORY ->
                    serviceRef != null && serviceRef.equals(finding.getServiceRef())
                            && finding.getCategory().equals(category);
            case CATEGORY_GLOBAL -> finding.getCategory().equals(category);
        };
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

    public Scope getScope() {
        return scope;
    }

    public void setScope(Scope scope) {
        this.scope = scope;
    }

    public Long getFindingId() {
        return findingId;
    }

    public void setFindingId(Long findingId) {
        this.findingId = findingId;
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

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public ReasonCode getReasonCode() {
        return reasonCode;
    }

    public void setReasonCode(ReasonCode reasonCode) {
        this.reasonCode = reasonCode;
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

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(Instant revokedAt) {
        this.revokedAt = revokedAt;
    }

    public String getRevokedBy() {
        return revokedBy;
    }

    public void setRevokedBy(String revokedBy) {
        this.revokedBy = revokedBy;
    }

    public boolean isSurvivesMaterialChange() {
        return survivesMaterialChange;
    }

    public void setSurvivesMaterialChange(boolean survivesMaterialChange) {
        this.survivesMaterialChange = survivesMaterialChange;
    }
}
