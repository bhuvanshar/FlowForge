package com.flowforge.api.dto;

import com.flowforge.domain.workflow.WorkflowDefinition;
import com.flowforge.domain.workflow.WorkflowStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Full replacement of the editable fields. {@code lockVersion} guards against overwriting concurrent changes. */
public record UpdateWorkflowRequest(
        @NotBlank @Size(max = 200) String name,
        @Size(max = 2000) String description,
        @Schema(description = "Optional. Omit to keep the current status. Set to a non-ARCHIVED value to restore an "
                + "archived workflow.")
        WorkflowStatus status,
        @NotNull @Valid WorkflowDefinition definition,
        @NotNull @Min(0) @Schema(description = "The lockVersion from the last response you read. "
                + "A mismatch returns 409.") Long lockVersion) {
}
