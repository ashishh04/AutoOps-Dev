package com.intertec.autoops.plugin.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * "Send me {events} for {target} through {installation}."
 *
 * <p>Scope widens as the two id columns go null:
 * <ul>
 *   <li>{@code targetId} set — one specific job, workflow or agent.</li>
 *   <li>{@code targetId} null, {@code projectId} set — every target of that
 *       type in that project, including ones created later.</li>
 *   <li>both null — every target of that type in the workspace.</li>
 * </ul>
 * Wildcards matter operationally: a rule written per-job silently fails to
 * cover the job someone adds next week, which is exactly when a missed alert
 * hurts.
 *
 * <p>An {@code ALERT} rule is always one of the last two. An alert has no
 * numeric id, so there is nothing for {@code targetId} to hold — see
 * {@link TargetType#identifiesTargets}.
 */
@Entity
@Table(name = "notification_rules")
public class NotificationRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    /**
     * The channel this fires through. A plain column, not a {@code @ManyToOne}:
     * the pairing is validated by loading the installation with the SAME
     * tenantId, so a rule can never bind to another workspace's channel even
     * if an id is guessed.
     */
    @Column(name = "installation_id", nullable = false)
    private Long installationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false,
            columnDefinition = "ENUM('JOB','WORKFLOW','AGENT','ALERT')")
    private TargetType targetType;

    /** Null means every target of this type in scope. */
    @Column(name = "target_id")
    private Long targetId;

    /** Null means every project. Ignored when {@code targetId} is set. */
    @Column(name = "project_id")
    private Long projectId;

    /**
     * Comma-separated {@link LifecycleEvent} names. A set column rather than a
     * child table: the list is short, bounded by the enum, and always read
     * whole — a join per rule per event would cost far more than it saves.
     */
    @Column(nullable = false, length = 255)
    private String events;

    /**
     * The floor an event's severity has to clear, or null for no opinion.
     *
     * <p>Exists for alerts. A job fires a handful of events a day; a workspace
     * receives hundreds of alerts, most of them informational. A rule that
     * cannot say "critical only" is a rule nobody can afford to leave on — and
     * a channel people mute is worse than one they never had, because they stop
     * reading the messages that mattered too.
     *
     * <p>Null rather than defaulted to INFO. The two are equivalent today and
     * would stop being so the moment a severity below INFO existed; a rule that
     * expressed no opinion must keep meaning exactly that.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "min_severity", length = 16)
    private LifecycleEvent.Severity minSeverity;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_by", length = 255)
    private String createdBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    public NotificationRule() {
    }

    public Set<LifecycleEvent> eventSet() {
        if (events == null || events.isBlank()) {
            return EnumSet.noneOf(LifecycleEvent.class);
        }
        return Arrays.stream(events.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(LifecycleEvent::valueOf)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(LifecycleEvent.class)));
    }

    public void setEventSet(Set<LifecycleEvent> values) {
        // Stored in enum order, not insertion order, so two rules with the
        // same events compare equal as strings.
        this.events = values.stream()
                .sorted()
                .map(Enum::name)
                .collect(Collectors.joining(","));
    }

    /** Does this rule cover that target? Assumes the tenant already matched. */
    public boolean matches(TargetType type, Long target, Long project, LifecycleEvent event) {
        return matches(type, target, project, event, null);
    }

    /**
     * Does this rule cover that target?  Assumes the tenant already matched.
     *
     * @param severity what actually happened, which for an alert is the
     *                 alert's own severity and NOT the event's. A firing alert
     *                 is always a TRIGGERED event; whether it is worth waking
     *                 somebody for is the alert's business. Null falls back to
     *                 the event's own severity, which is what every run-shaped
     *                 event passes.
     */
    public boolean matches(TargetType type, Long target, Long project, LifecycleEvent event,
                           LifecycleEvent.Severity severity) {
        if (!enabled || targetType != type || !eventSet().contains(event)) {
            return false;
        }
        LifecycleEvent.Severity actual = severity != null ? severity : event.severity();
        if (!actual.atLeast(minSeverity)) {
            return false;
        }
        if (targetId != null) {
            return targetId.equals(target);
        }
        // A project-scoped rule must not fire for an event carrying no project.
        return projectId == null || projectId.equals(project);
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

    public Long getInstallationId() {
        return installationId;
    }

    public void setInstallationId(Long installationId) {
        this.installationId = installationId;
    }

    public TargetType getTargetType() {
        return targetType;
    }

    public void setTargetType(TargetType targetType) {
        this.targetType = targetType;
    }

    public Long getTargetId() {
        return targetId;
    }

    public void setTargetId(Long targetId) {
        this.targetId = targetId;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public LifecycleEvent.Severity getMinSeverity() {
        return minSeverity;
    }

    public void setMinSeverity(LifecycleEvent.Severity minSeverity) {
        this.minSeverity = minSeverity;
    }

    public String getEvents() {
        return events;
    }

    public void setEvents(String events) {
        this.events = events;
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
