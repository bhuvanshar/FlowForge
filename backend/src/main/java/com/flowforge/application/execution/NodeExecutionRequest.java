package com.flowforge.application.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.flowforge.domain.workflow.NodeDefinition;

/**
 * Everything a node executor gets.
 *
 * @param node    the node from the execution snapshot (configuration included)
 * @param input   output of the upstream node (for the trigger: the execution's trigger input); never null
 * @param context run-scoped state (outputs of earlier nodes, variables, cancellation)
 */
public record NodeExecutionRequest(NodeDefinition node, JsonNode input, ExecutionContext context) {
}
