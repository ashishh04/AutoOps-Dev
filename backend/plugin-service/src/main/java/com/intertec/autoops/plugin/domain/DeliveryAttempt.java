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

/**
 * One record of one attempt to deliver one event through one channel.
 *
 * <p>This exists because of how the platform's existing email path fails: a
 * SendGrid send to a suppressed address returns 202 and vanishes, leaving no
 * trace anywhere. "The tenant says they never got the alert" has to be
 * answerable, and it can only be answered from a row like this one.
 *
 * <p>Records failures AND successes. A table of failures alone cannot
 * distinguish "we sent it and they missed it" from "we never sent it".
 */
@Entity
@Table(name = "delivery_attempts")
public class DeliveryAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "installation_id", nullable = false)
    private Long installationId;

    /** Denormalised so the log survives the installation being deleted. */
    @Column(name = "plugin_key", nullable = false, length = 64)
    private String pluginKey;

    /** Null for a manual connection test, which belongs to no rule. */
    @Column(name = "rule_id")
    private Long ruleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", columnDefinition = "ENUM('JOB','WORKFLOW','AGENT','ALERT')")
    private TargetType targetType;

    @Column(name = "target_id")
    private Long targetId;

    @Column(name = "target_name", length = 255)
    private String targetName;

    @Enumerated(EnumType.STRING)
    @Column(name = "event", nullable = false, length = 32)
    private LifecycleEvent event;

    @Column(name = "run_id")
    private Long runId;

    @Column(nullable = false)
    private boolean ok;

    /** HTTP status where there was one; 0 for SMTP, which has no equivalent. */
    @Column(name = "status_code")
    private Integer statusCode;

    /** Truncated response or error. Never contains a credential. */
    @Column(length = 1024)
    private String detail;

    @Column(name = "attempted_at", insertable = false, updatable = false)
    private Instant attemptedAt;

    public DeliveryAttempt() {
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

    public String getPluginKey() {
        return pluginKey;
    }

    public void setPluginKey(String pluginKey) {
        this.pluginKey = pluginKey;
    }

    public Long getRuleId() {
        return ruleId;
    }

    public void setRuleId(Long ruleId) {
        this.ruleId = ruleId;
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

    public String getTargetName() {
        return targetName;
    }

    public void setTargetName(String targetName) {
        this.targetName = targetName;
    }

    public LifecycleEvent getEvent() {
        return event;
    }

    public void setEvent(LifecycleEvent event) {
        this.event = event;
    }

    public Long getRunId() {
        return runId;
    }

    public void setRunId(Long runId) {
        this.runId = runId;
    }

    public boolean isOk() {
        return ok;
    }

    public void setOk(boolean ok) {
        this.ok = ok;
    }

    public Integer getStatusCode() {
        return statusCode;
    }

    public void setStatusCode(Integer statusCode) {
        this.statusCode = statusCode;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public Instant getAttemptedAt() {
        return attemptedAt;
    }
}
