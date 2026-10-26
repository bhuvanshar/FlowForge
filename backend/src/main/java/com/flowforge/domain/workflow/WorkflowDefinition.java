package com.flowforge.domain.workflow;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Canonical, versioned workflow graph. Stored as JSONB in {@code workflows.definition} and
 * {@code workflow_executions.workflow_snapshot}. The JSON form is specified by
 * {@code schemas/workflow-definition.v1.schema.json} and documented in {@code docs/WORKFLOW_SCHEMA.md}.
 *
 * <p>Bean Validation annotations cover structure only. Graph rules (unique ids, edge endpoints exist,
 * known node types and handles, no cycles) belong to the workflow validation module.
 */
public record WorkflowDefinition(
        @Min(1) @Max(CURRENT_SCHEMA_VERSION) int schemaVersion,
        @NotNull @Size(max = MAX_NODES) List<@NotNull @Valid NodeDefinition> nodes,
        @NotNull @Size(max = MAX_EDGES) List<@NotNull @Valid EdgeDefinition> edges,
        @Valid EditorMetadata editor) {

    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final int MAX_NODES = 500;
    public static final int MAX_EDGES = 2000;

    public static final String ID_PATTERN = "^[A-Za-z0-9_-]{1,64}$";
    public static final String HANDLE_PATTERN = "^[A-Za-z][A-Za-z0-9_-]{0,63}$";
    public static final String NODE_TYPE_PATTERN = "^[a-z][a-z0-9]*(\\.[a-z][A-Za-z0-9]*)+$";

    public WorkflowDefinition {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
    }

    /** An empty definition at the current schema version, used for newly created workflows. */
    public static WorkflowDefinition empty() {
        return new WorkflowDefinition(CURRENT_SCHEMA_VERSION, List.of(), List.of(), null);
    }
}
