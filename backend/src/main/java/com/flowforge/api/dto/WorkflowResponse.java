package com.flowforge.api.dto;

import com.flowforge.domain.workflow.WorkflowDefinition;
import com.flowforge.domain.workflow.WorkflowStatus;
import java.time.Instant;
import java.util.UUID;

public record WorkflowResponse(UUID id, String name, String description, WorkflowStatus status, int version,
                               long lockVersion, WorkflowDefinition definition, Instant createdAt,
                               Instant updatedAt) {
}
