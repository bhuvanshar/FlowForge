package com.flowforge.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/** Schedule registration of one workflow; {@code nextFireAt} is the next occurrence the scheduler will handle. */
@Entity
@Table(name = "schedule_triggers")
public class ScheduleTriggerEntity {

    @Id
    @Column(name = "workflow_id")
    private UUID workflowId;

    @Column(name = "node_id", nullable = false, length = 64)
    private String nodeId;

    @Column(nullable = false, length = 120)
    private String cron;

    @Column(nullable = false, length = 64)
    private String timezone;

    @Column(name = "missed_run_policy", nullable = false, length = 20)
    private String missedRunPolicy;

    @Column(name = "next_fire_at", nullable = false)
    private Instant nextFireAt;

    @Column(name = "last_fire_at")
    private Instant lastFireAt;

    @Column(name = "last_execution_id")
    private UUID lastExecutionId;

    @Column(name = "last_missed_at")
    private Instant lastMissedAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(nullable = false)
    private boolean active;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "lock_version", nullable = false)
    private Long lockVersion;

    protected ScheduleTriggerEntity() {
    }

    public ScheduleTriggerEntity(UUID workflowId) {
        this.workflowId = Objects.requireNonNull(workflowId);
    }

    public void configure(String nodeId, String cron, String timezone, String missedRunPolicy, Instant nextFireAt) {
        this.nodeId = nodeId;
        this.cron = cron;
        this.timezone = timezone;
        this.missedRunPolicy = missedRunPolicy;
        this.nextFireAt = Objects.requireNonNull(nextFireAt);
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public void advance(Instant next) {
        this.nextFireAt = Objects.requireNonNull(next);
    }

    public void fired(Instant occurrence, UUID executionId) {
        this.lastFireAt = occurrence;
        this.lastExecutionId = executionId;
        this.lastError = null;
    }

    public void missed(Instant occurrence) {
        this.lastMissedAt = occurrence;
    }

    /** The occurrence could not be started (for example the saved workflow no longer validates). */
    public void failed(Instant occurrence, String error) {
        this.lastMissedAt = occurrence;
        this.lastError = error == null ? null : error.substring(0, Math.min(500, error.length()));
    }

    public UUID getWorkflowId() {
        return workflowId;
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getCron() {
        return cron;
    }

    public String getTimezone() {
        return timezone;
    }

    public String getMissedRunPolicy() {
        return missedRunPolicy;
    }

    public Instant getNextFireAt() {
        return nextFireAt;
    }

    public Instant getLastFireAt() {
        return lastFireAt;
    }

    public UUID getLastExecutionId() {
        return lastExecutionId;
    }

    public Instant getLastMissedAt() {
        return lastMissedAt;
    }

    public String getLastError() {
        return lastError;
    }

    public boolean isActive() {
        return active;
    }
}
