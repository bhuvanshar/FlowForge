package com.flowforge.infrastructure.nodes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.application.execution.NodeExecutionException;
import com.flowforge.application.expression.ExpressionException;
import com.flowforge.domain.workflow.NodeDefinition;
import java.util.function.Supplier;

/** Small helpers shared by the built-in executors. */
final class NodeConfig {

    static final String INVALID_CONFIGURATION = "INVALID_CONFIGURATION";

    private NodeConfig() {
    }

    static JsonNode of(ObjectMapper mapper, NodeDefinition node) {
        return mapper.valueToTree(node.configuration());
    }

    static String requiredText(JsonNode config, String field) {
        JsonNode value = config.get(field);
        if (value == null || !value.isTextual()) {
            throw new NodeExecutionException(INVALID_CONFIGURATION, "Setting '" + field + "' is missing.");
        }
        return value.textValue();
    }

    /** Runs {@code body}, reporting expression problems as {@code EXPRESSION_ERROR} with the setting that caused them. */
    static <T> T resolving(String setting, Supplier<T> body) {
        try {
            return body.get();
        } catch (ExpressionException e) {
            throw new NodeExecutionException(ExpressionException.CODE, setting + ": " + e.getMessage());
        }
    }
}
