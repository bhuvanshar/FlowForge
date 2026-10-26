package com.flowforge.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One log line of an execution. Append-only. */
@Entity
@Table(name = "execution_logs")
public class ExecutionLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "execution_id", nullable = false, updatable = false)
    private UUID executionId;

    @Column(name = "node_id", updatable = false, length = 64)
    private String nodeId;

    @Column(nullable = false, updatable = false, length = 10)
    private String level;

    @Column(nullable = false, updatable = false, length = 4000)
    private String message;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ExecutionLogEntity() {
    }

    public ExecutionLogEntity(UUID executionId, String nodeId, String level, String message, Instant createdAt) {
        this.executionId = Objects.requireNonNull(executionId);
        this.nodeId = nodeId;
        this.level = Objects.requireNonNull(level);
        this.message = Objects.requireNonNull(message);
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    public Long getId() {
        return id;
    }

    public UUID getExecutionId() {
        return executionId;
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getLevel() {
        return level;
    }

    public String getMessage() {
        return message;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
