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
 * Why a finding is in the state it is in.
 *
 * <p>Kept apart from observations because the ACTORS differ. An observation is
 * always an agent reporting. A transition is frequently a person deciding, and
 * the question asked afterwards — "who closed this, and on what grounds" — is
 * about the person.
 */
@Entity
@Table(name = "finding_transitions")
public class FindingTransition {

    public enum ActorKind { HUMAN, AGENT, SYSTEM }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "finding_id", nullable = false)
    private Long findingId;

    @Column(name = "from_state", length = 16)
    private String fromState;

    @Column(name = "to_state", nullable = false, length = 16)
    private String toState;

    @Column(name = "at", nullable = false)
    private Instant at = Instant.now();

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_kind", nullable = false,
            columnDefinition = "ENUM('HUMAN','AGENT','SYSTEM')")
    private ActorKind actorKind;

    @Column(name = "actor_ref")
    private String actorRef;

    @Column(name = "reason_code", nullable = false, length = 64)
    private String reasonCode;

    @Column(length = 1024)
    private String detail;

    /**
     * The suppression, observation or decision that caused this.
     *
     * <p>Untyped because the referent varies with the reason code, and a
     * column per kind would be four nullable columns that are always three
     * nulls and one value.
     */
    @Column(name = "ref_id")
    private Long refId;

    public static FindingTransition of(Finding finding, Finding.State from, Finding.State to,
                                       ActorKind actorKind, String actorRef,
                                       String reasonCode, String detail, Long refId) {
        FindingTransition transition = new FindingTransition();
        transition.tenantId = finding.getTenantId();
        transition.findingId = finding.getId();
        transition.fromState = from == null ? null : from.name();
        transition.toState = to.name();
        transition.actorKind = actorKind;
        transition.actorRef = actorRef;
        transition.reasonCode = reasonCode;
        transition.detail = detail;
        transition.refId = refId;
        return transition;
    }

    public Long getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public Long getFindingId() {
        return findingId;
    }

    public String getFromState() {
        return fromState;
    }

    public String getToState() {
        return toState;
    }

    public Instant getAt() {
        return at;
    }

    public ActorKind getActorKind() {
        return actorKind;
    }

    public String getActorRef() {
        return actorRef;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public String getDetail() {
        return detail;
    }

    public Long getRefId() {
        return refId;
    }
}
