package com.flowforge.api.dto;

import com.flowforge.domain.workflow.WorkflowDefinition;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;

public record ValidateWorkflowRequest(
        @Valid @Schema(description = "Definition to validate, e.g. the editor's unsaved canvas. "
                + "Omit (or send no body) to validate the stored definition.") WorkflowDefinition definition) {
}
