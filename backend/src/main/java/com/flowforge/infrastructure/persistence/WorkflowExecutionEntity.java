package com.flowforge.infrastructure.persistence;

import com.flowforge.domain.execution.ErrorDetails;
import com.flowforge.domain.execution.ExecutionStatus;
import com.flowforge.domain.execution.TriggerType;
import com.flowforge.domain.workflow.WorkflowDefinition;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A single run of a workflow. The state-transition methods only enforce the invariants that the database also
 * checks; orchestration belongs to the execution module.
 */
@Entity
@Table(name = "workflow_executions")
public class WorkflowExecutionEntity {

    @Id
    private UUID id;

    @Column(name = "workflow_id", nullable = false, updatable = false)
    private UUID workflowId;

    @Column(name = "workflow_version", nullable = false, updatable = false)
    private int workflowVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "workflow_snapshot", nullable = false, updatable = false, columnDefinition = "jsonb")
    private WorkflowDefinition workflowSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ExecutionStatus status;

    @Column(name = "error_code", length = ErrorDetails.MAX_CODE_LENGTH)
    private String errorCode;

    @Column(name = "error_message", length = ErrorDetails.MAX_MESSAGE_LENGTH)
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, updatable = false, length = 20)
    private TriggerType triggerType;

    /** The schedule occurrence this run belongs to (SCHEDULE runs only); unique per workflow. */
    @Column(name = "scheduled_for", updatable = false)
    private Instant scheduledFor;

    @Version
    @Column(name = "lock_version", nullable = false)
    private Long lockVersion;

    protected WorkflowExecutionEntity() {
    }

    /** Creates a QUEUED execution with an immutable snapshot of the workflow's current definition. */
    public static WorkflowExecutionEntity queued(UUID id, WorkflowEntity workflow) {
        return queued(id, workflow, TriggerType.MANUAL, null);
    }

    /** @param scheduledFor required for {@link TriggerType#SCHEDULE}, null otherwise */
    public static WorkflowExecutionEntity queued(UUID id, WorkflowEntity workflow, TriggerType triggerType,
                                                 Instant scheduledFor) {
        if ((triggerType == TriggerType.SCHEDULE) != (scheduledFor != null)) {
            throw new IllegalArgumentException("scheduledFor is required exactly for scheduled runs");
        }
        WorkflowExecutionEntity execution = new WorkflowExecutionEntity();
        execution.triggerType = Objects.requireNonNull(triggerType, "triggerType");
        execution.scheduledFor = scheduledFor;
        execution.id = Objects.requireNonNull(id, "id");
        execution.workflowId = workflow.getId();
        execution.workflowVersion = workflow.getVersion();
        execution.workflowSnapshot = workflow.getDefinition();
        execution.status = ExecutionStatus.QUEUED;
        return execution;
    }

    public void markRunning(Instant startedAt) {
        requireStatus(ExecutionStatus.QUEUED);
        this.status = ExecutionStatus.RUNNING;
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
    }

    public void markSucceeded(Instant finishedAt) {
        requireStatus(ExecutionStatus.RUNNING);
        finish(ExecutionStatus.SUCCEEDED, finishedAt, null);
    }

    public void markFailed(Instant finishedAt, ErrorDetails error) {
        Objects.requireNonNull(error, "error");
        if (status.isTerminal()) {
            throw new IllegalStateException("Execution " + id + " is already " + status);
        }
        finish(ExecutionStatus.FAILED, finishedAt, error);
    }

    public void markCancelled(Instant finishedAt) {
        if (status.isTerminal()) {
            throw new IllegalStateException("Execution " + id + " is already " + status);
        }
        finish(ExecutionStatus.CANCELLED, finishedAt, null);
    }

    private void finish(ExecutionStatus terminal, Instant finishedAt, ErrorDetails error) {
        this.status = terminal;
        this.finishedAt = Objects.requireNonNull(finishedAt, "finishedAt");
        this.errorCode = error == null ? null : error.code();
        this.errorMessage = error == null ? null : error.message();
    }

    private void requireStatus(ExecutionStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("Execution " + id + " is " + status + ", expected " + expected);
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkflowId() {
        return workflowId;
    }

    public int getWorkflowVersion() {
        return workflowVersion;
    }

    public WorkflowDefinition getWorkflowSnapshot() {
        return workflowSnapshot;
    }

    public ExecutionStatus getStatus() {
        return status;
    }

    public ErrorDetails getError() {
        return errorCode == null ? null : new ErrorDetails(errorCode, errorMessage);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public TriggerType getTriggerType() {
        return triggerType;
    }

    public Instant getScheduledFor() {
        return scheduledFor;
    }

    public Long getLockVersion() {
        return lockVersion;
    }
}
