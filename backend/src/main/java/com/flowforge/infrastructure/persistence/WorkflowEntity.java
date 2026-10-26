package com.flowforge.infrastructure.persistence;

import com.flowforge.domain.workflow.WorkflowDefinition;
import com.flowforge.domain.workflow.WorkflowStatus;
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
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "workflows")
public class WorkflowEntity {

    @Id
    private UUID id;

    /** Owner (user or, later, workspace). Always {@code local} in the single-user MVP. */
    @Column(name = "owner_id", nullable = false, updatable = false, length = 64)
    private String ownerId = "local";

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 2000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WorkflowStatus status;

    /** Definition version; incremented by {@link #updateDefinition}. Not the optimistic-lock counter. */
    @Column(nullable = false)
    private int version;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private WorkflowDefinition definition;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "lock_version", nullable = false)
    private Long lockVersion;

    protected WorkflowEntity() {
    }

    public WorkflowEntity(UUID id, String name, String description, WorkflowDefinition definition) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = Objects.requireNonNull(name, "name");
        this.description = description;
        this.definition = Objects.requireNonNull(definition, "definition");
        this.status = WorkflowStatus.DRAFT;
        this.version = 1;
    }

    public WorkflowEntity(UUID id, String ownerId, String name, String description, WorkflowDefinition definition) {
        this(id, name, description, definition);
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
    }

    public void rename(String name, String description) {
        this.name = Objects.requireNonNull(name, "name");
        this.description = description;
    }

    public void changeStatus(WorkflowStatus status) {
        this.status = Objects.requireNonNull(status, "status");
    }

    /** Replaces the definition and bumps the definition version when it actually changed. */
    public void updateDefinition(WorkflowDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        if (!definition.equals(this.definition)) {
            this.definition = definition;
            this.version++;
        }
    }

    public UUID getId() {
        return id;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public WorkflowStatus getStatus() {
        return status;
    }

    public int getVersion() {
        return version;
    }

    public WorkflowDefinition getDefinition() {
        return definition;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getLockVersion() {
        return lockVersion;
    }
}
