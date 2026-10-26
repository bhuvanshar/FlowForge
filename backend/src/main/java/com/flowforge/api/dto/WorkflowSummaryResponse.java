package com.flowforge.api.dto;

import com.flowforge.domain.execution.ExecutionStatus;
import com.flowforge.domain.execution.TriggerType;
import com.flowforge.domain.workflow.WorkflowStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * List item: workflow metadata without the (potentially large) definition. {@code lastExecution} is the newest run of any
 * kind (omitted when the workflow never ran); {@code finishedAt} is omitted while it is queued or running.
 */
public record WorkflowSummaryResponse(UUID id, String name, String description, WorkflowStatus status, int version,
                                      long lockVersion, int nodeCount, int edgeCount, Instant createdAt,
                                      Instant updatedAt, LastExecution lastExecution) {

    public record LastExecution(UUID id, ExecutionStatus status, TriggerType triggerType, Instant createdAt,
                                Instant finishedAt) {
    }
}
