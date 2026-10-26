package com.flowforge.infrastructure.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.flowforge.domain.execution.ErrorDetails;
import com.flowforge.domain.execution.NodeExecutionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One run of one node within a workflow execution. Input and output are arbitrary JSON and are subject to the
 * size caps in {@code V4__create_node_executions.sql}; the execution module must truncate before persisting.
 */
@Entity
@Table(name = "node_executions")
public class NodeExecutionEntity {

    @Id
    private UUID id;

    @Column(name = "execution_id", nullable = false, updatable = false)
    private UUID executionId;

    @Column(name = "node_id", nullable = false, updatable = false, length = 64)
    private String nodeId;

    @Column(name = "node_type", nullable = false, updatable = false, length = 100)
    private String nodeType;

    @Column(nullable = false, updatable = false)
    private int sequence;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NodeExecutionStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode input;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode output;

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

    protected NodeExecutionEntity() {
    }

    public NodeExecutionEntity(UUID id, UUID executionId, String nodeId, String nodeType, int sequence) {
        this.id = Objects.requireNonNull(id, "id");
        this.executionId = Objects.requireNonNull(executionId, "executionId");
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.nodeType = Objects.requireNonNull(nodeType, "nodeType");
        this.sequence = sequence;
        this.status = NodeExecutionStatus.PENDING;
    }

    public void markRunning(Instant startedAt, JsonNode input) {
        if (status != NodeExecutionStatus.PENDING) {
            throw new IllegalStateException("Node execution " + id + " is " + status + ", expected PENDING");
        }
        this.status = NodeExecutionStatus.RUNNING;
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
        this.input = input;
    }

    public void markSucceeded(Instant finishedAt, JsonNode output) {
        if (status != NodeExecutionStatus.RUNNING) {
            throw new IllegalStateException("Node execution " + id + " is " + status + ", expected RUNNING");
        }
        this.status = NodeExecutionStatus.SUCCEEDED;
        this.finishedAt = Objects.requireNonNull(finishedAt, "finishedAt");
        this.output = output;
    }

    public void markFailed(Instant finishedAt, ErrorDetails error) {
        Objects.requireNonNull(error, "error");
        if (status.isTerminal()) {
            throw new IllegalStateException("Node execution " + id + " is already " + status);
        }
        this.status = NodeExecutionStatus.FAILED;
        this.finishedAt = Objects.requireNonNull(finishedAt, "finishedAt");
        this.errorCode = error.code();
        this.errorMessage = error.message();
    }

    public void markSkipped(Instant finishedAt) {
        if (status != NodeExecutionStatus.PENDING) {
            throw new IllegalStateException("Node execution " + id + " is " + status + ", expected PENDING");
        }
        this.status = NodeExecutionStatus.SKIPPED;
        this.finishedAt = Objects.requireNonNull(finishedAt, "finishedAt");
    }

    public UUID getId() {
        return id;
    }

    public UUID getExecutionId() {
        return executionId;
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getNodeType() {
        return nodeType;
    }

    public int getSequence() {
        return sequence;
    }

    public NodeExecutionStatus getStatus() {
        return status;
    }

    public JsonNode getInput() {
        return input;
    }

    public JsonNode getOutput() {
        return output;
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
}
