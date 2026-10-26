package com.flowforge.api.dto;

import com.flowforge.domain.workflow.WorkflowDefinition;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateWorkflowRequest(
        @NotBlank @Size(max = 200) String name,
        @Size(max = 2000) String description,
        @Valid @Schema(description = "Optional. Defaults to an empty definition at the current schema version.")
        WorkflowDefinition definition) {
}
