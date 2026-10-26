package com.flowforge.api.dto;

import com.flowforge.domain.execution.ExecutionStatus;
import java.util.UUID;

public record ExecutionStartedResponse(UUID executionId, UUID workflowId, ExecutionStatus status) {
}
