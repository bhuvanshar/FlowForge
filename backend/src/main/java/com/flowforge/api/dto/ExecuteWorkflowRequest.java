package com.flowforge.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;

public record ExecuteWorkflowRequest(
        @Schema(description = "Trigger input (any JSON). Omit to use the trigger's configured test input, or {}.")
        JsonNode input) {
}
