package com.flowforge.application.expression;

import com.fasterxml.jackson.databind.JsonNode;
import com.flowforge.application.execution.ExecutionContext;

/**
 * What references can see while one node runs:
 * <ul>
 *   <li>{@code input}: the node's input (its parent's output)</li>
 *   <li>{@code trigger.body}: the data the execution was started with</li>
 *   <li>{@code vars.<name>}: workflow variables (Set Variables)</li>
 *   <li>{@code nodes.<nodeId>.output}: output of a node that already ran (missing if it was skipped or has not run)</li>
 * </ul>
 */
public record ExpressionScope(JsonNode input, ExecutionContext context) {
}
