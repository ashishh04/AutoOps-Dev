package com.intertec.autoops.workflow.domain;

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
 * An automation workflow: a JSON canvas definition whose {@code nodes} array
 * size is counted SERVER-SIDE (never client-supplied) and enforced against
 * the plan's MAX_NODES.
 *
 * <p>{@code projectId} is a plain column, not a {@code @ManyToOne}: projects
 * live in core-service's database now, so the relationship is validated over
 * HTTP on write (see {@code CoreClient#requireProject}) instead of by a
 * foreign key.
 */
@Entity
@Table(name = "workflows")
public class Workflow {

    /**
     * Who authored this workflow, and therefore who may read or change it.
     * PROVIDER rows are rolled out from the platform catalog: the tenant may
     * run, enable and disable them, but their {@code definition} is never
     * serialised to a non-PROVIDER caller.
     */
    public enum Origin { TENANT, PROVIDER }

    /**
     * What this copy was delivered AS, which decides whether the customer can
     * see it at all.
     *
     * <p>Workflows and agents are sold separately, but an agent can only act
     * through automations that live in the customer's project and run with
     * their credentials — so delivering an agent delivers the workflows it
     * names. {@code AGENT_COMPONENT} marks those: they are withheld from the
     * tenant's listing and refused by the tenant run path, while tool
     * resolution over {@code /internal} still sees them.
     *
     * <p>A customer who later licenses one of those workflows in its own right
     * receives a separate {@code PRODUCT} copy. Two rows, two lifecycles —
     * revoking the agent takes its component with it and leaves the licensed
     * one alone.
     */
    public enum Delivery { PRODUCT, AGENT_COMPONENT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "ENUM('TENANT','PROVIDER')")
    private Origin origin = Origin.TENANT;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "ENUM('PRODUCT','AGENT_COMPONENT')")
    private Delivery delivery = Delivery.PRODUCT;

    /** library_items id this was rolled out from; null for tenant-authored. */
    @Column(name = "source_id")
    private Long sourceId;

    @Column(nullable = false, length = 128)
    private String name;

    /** Canvas JSON: {@code {"nodes":[...],"edges":[...]}}. */
    @Column(columnDefinition = "MEDIUMTEXT")
    private String definition;

    @Column(name = "node_count", nullable = false)
    private int nodeCount;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_by", length = 255)
    private String createdBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    public Workflow() {
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

    public Origin getOrigin() {
        return origin;
    }

    public void setOrigin(Origin origin) {
        this.origin = origin;
    }

    /** True when the definition must never reach a tenant's browser. */
    public boolean isProviderAuthored() {
        return origin == Origin.PROVIDER;
    }

    public Delivery getDelivery() {
        return delivery;
    }

    public void setDelivery(Delivery delivery) {
        this.delivery = delivery;
    }

    /**
     * True when this copy exists only to give an agent something to call.
     *
     * <p>The customer was not sold it, must not see it in their list, and
     * cannot run it directly. The agent reaches it over {@code /internal},
     * which does not consult this.
     */
    public boolean isAgentComponent() {
        return delivery == Delivery.AGENT_COMPONENT;
    }

    public Long getSourceId() {
        return sourceId;
    }

    public void setSourceId(Long sourceId) {
        this.sourceId = sourceId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDefinition() {
        return definition;
    }

    public void setDefinition(String definition) {
        this.definition = definition;
    }

    public int getNodeCount() {
        return nodeCount;
    }

    public void setNodeCount(int nodeCount) {
        this.nodeCount = nodeCount;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
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

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
