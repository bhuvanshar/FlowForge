package com.flowforge.api.dto;

import com.flowforge.application.execution.ExecutionQueryService;
import com.flowforge.infrastructure.persistence.WorkflowEntity;

/** Maps persistence entities to API DTOs. Entities never leave the service/controller boundary. */
public final class WorkflowMapper {

    private WorkflowMapper() {
    }

    public static WorkflowResponse toResponse(WorkflowEntity e) {
        return new WorkflowResponse(e.getId(), e.getName(), e.getDescription(), e.getStatus(), e.getVersion(),
                e.getLockVersion(), e.getDefinition(), e.getCreatedAt(), e.getUpdatedAt());
    }

    public static WorkflowSummaryResponse toSummary(WorkflowEntity e) {
        return toSummary(e, null);
    }

    public static WorkflowSummaryResponse toSummary(WorkflowEntity e, ExecutionQueryService.Latest latest) {
        return new WorkflowSummaryResponse(e.getId(), e.getName(), e.getDescription(), e.getStatus(), e.getVersion(),
                e.getLockVersion(), e.getDefinition().nodes().size(), e.getDefinition().edges().size(),
                e.getCreatedAt(), e.getUpdatedAt(), latest == null ? null : new WorkflowSummaryResponse.LastExecution(
                latest.id(), latest.status(), latest.triggerType(), latest.createdAt(), latest.finishedAt()));
    }
}
