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
 * An AI agent: a named operator scoped to one project, carrying its persona
 * ({@code instructions}) and the CLOSED ALLOW-LIST of automations it may
 * operate ({@code tools}). Nothing outside that list is reachable by the
 * agent, and {@code toolCount} is derived SERVER-SIDE from the list, never
 * client-supplied.
 *
 * <p>{@code projectId} is a plain column, not a {@code @ManyToOne}: projects
 * live in core-service's database now, so the relationship is validated over
 * HTTP on write (see {@code ToolTargetClient#requireProject}) instead of by a
 * foreign key.
 */
@Entity
@Table(name = "agents")
public class Agent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /**
     * Who built this agent, and therefore who may read or change its persona.
     * PROVIDER rows are rolled out from the platform catalog: the tenant may
     * run, enable and disable them and can always see the tool allow-list,
     * but {@code instructions} is never serialised to a non-PROVIDER caller.
     */
    public enum Origin { TENANT, PROVIDER }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "ENUM('TENANT','PROVIDER')")
    private Origin origin = Origin.TENANT;

    /** library_items id this was rolled out from; null for tenant-built. */
    @Column(name = "source_id")
    private Long sourceId;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(length = 512)
    private String description;

    /** Model id the agent will run on — free text; no runtime validates it yet. */
    @Column(length = 128)
    private String model;

    /**
     * Which module in the Python runtime's registry runs this agent.
     *
     * <p>NULL means the agent is a JSON persona — {@link #instructions} below —
     * and runs on the single-phase compatibility graph, which is every agent
     * that exists today. A non-null ref means the persona, the prompts and the
     * phase graph live in the runtime's image and were never copied into this
     * tenant's database at all.
     */
    @Column(name = "graph_ref", length = 128)
    private String graphRef;

    /** The version rolled out to this tenant; see the V5 migration. */
    @Column(name = "graph_version", length = 32)
    private String graphVersion;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String instructions;

    /**
     * JSON allow-list: {@code [{"type":"JOB|WORKFLOW","id":N,"mutating":bool}]}.
     *
     * <p>{@code mutating} is optional and defaults to TRUE when absent — see
     * {@code AgentToolbox.mutating}. It is declared by whoever authored the
     * agent because nothing else in the platform records whether a saved
     * automation changes state.
     */
    @Column(columnDefinition = "MEDIUMTEXT")
    private String tools;

    /**
     * Ordered, comma-separated phase names for an agent with no {@link #graphRef}.
     *
     * <p>NULL means this agent made no phase declaration, and it resolves to the
     * un-phased compatibility loop — which is every agent that exists today.
     * That default is the contract, not a migration convenience: a persona
     * written for the old loop has no citation rule, and switching evidence
     * enforcement on underneath one would fill its report with markers its
     * author never accounted for. An author opts in; a deploy never does it for
     * them.
     *
     * <p>Ignored entirely when {@code graphRef} is set. A shipped agent's graph
     * is part of what was reviewed and released, and a row that could append ACT
     * to a read-only auditor would turn a hand-edited database record into a
     * different agent running against production.
     */
    @Column(length = 255)
    private String phases;

    @Column(name = "tool_count", nullable = false)
    private int toolCount;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_by", length = 255)
    private String createdBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    public Agent() {
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

    /** True when the persona must never reach a tenant's browser. */
    public boolean isProviderAuthored() {
        return origin == Origin.PROVIDER;
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

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getGraphRef() {
        return graphRef;
    }

    public void setGraphRef(String graphRef) {
        this.graphRef = graphRef;
    }

    public String getGraphVersion() {
        return graphVersion;
    }

    public void setGraphVersion(String graphVersion) {
        this.graphVersion = graphVersion;
    }

    public String getInstructions() {
        return instructions;
    }

    public void setInstructions(String instructions) {
        this.instructions = instructions;
    }

    public String getTools() {
        return tools;
    }

    public void setTools(String tools) {
        this.tools = tools;
    }

    public String getPhases() {
        return phases;
    }

    public void setPhases(String phases) {
        this.phases = phases;
    }

    public int getToolCount() {
        return toolCount;
    }

    public void setToolCount(int toolCount) {
        this.toolCount = toolCount;
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
