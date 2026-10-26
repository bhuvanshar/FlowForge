package com.flowforge.domain.workflow;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A node in the workflow graph.
 *
 * @param id            unique within the workflow; referenced by edges and node executions
 * @param type          node type identifier, e.g. {@code core.httpRequest}; resolved against the node registry
 * @param typeVersion   version of the node type's configuration schema
 * @param name          display name, unique within the workflow
 * @param position      editor position
 * @param configuration node-type specific settings, validated against the node type's schema
 */
public record NodeDefinition(
        @NotBlank @Pattern(regexp = WorkflowDefinition.ID_PATTERN) String id,
        @NotBlank @Pattern(regexp = WorkflowDefinition.NODE_TYPE_PATTERN) String type,
        @Min(1) int typeVersion,
        @NotBlank @Size(max = 100) String name,
        @NotNull @Valid NodePosition position,
        @NotNull Map<String, Object> configuration) {

    public NodeDefinition {
        // LinkedHashMap rather than Map.copyOf: configuration values may legitimately be JSON null.
        configuration = configuration == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(configuration));
    }
}
