package com.flowforge.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

public record DuplicateWorkflowRequest(
        @Size(max = 200) @Schema(description = "Optional. Defaults to '<original name> (copy)'.") String name) {
}
