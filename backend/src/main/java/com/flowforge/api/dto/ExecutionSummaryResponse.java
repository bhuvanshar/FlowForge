package com.flowforge.api.dto;

import com.flowforge.application.execution.ExecutionQueryService.Summary;
import com.flowforge.domain.execution.ExecutionStatus;
import com.flowforge.domain.execution.TriggerType;
import com.flowforge.infrastructure.persistence.WorkflowExecutionEntity;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** One row of a workflow's execution history. */
public record ExecutionSummaryResponse(UUID id, UUID workflowId, int workflowVersion, ExecutionStatus status,
                                       TriggerType triggerType, Instant scheduledFor, ExecutionResponse.Error error, Instant createdAt, Instant startedAt,
                                       Instant finishedAt, Long durationMs, int nodeCount, int succeededNodes,
                                       int failedNodes, int skippedNodes, String failedNodeId, String failedNodeName) {

    public static ExecutionSummaryResponse from(Summary s) {
        WorkflowExecutionEntity e = s.execution();
        Map<String, String> names = ExecutionResponse.names(e);
        return new ExecutionSummaryResponse(e.getId(), e.getWorkflowId(), e.getWorkflowVersion(), e.getStatus(),
                e.getTriggerType(), e.getScheduledFor(), e.getError() == null ? null : new ExecutionResponse.Error(e.getError().code(), e.getError().message()),
                e.getCreatedAt(), e.getStartedAt(), e.getFinishedAt(),
                ExecutionResponse.millis(e.getStartedAt(), e.getFinishedAt()), s.nodeCount(), s.succeeded(), s.failed(),
                s.skipped(), s.failedNodeId(), s.failedNodeId() == null ? null : names.get(s.failedNodeId()));
    }
}
