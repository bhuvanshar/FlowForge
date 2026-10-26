package com.flowforge.application.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.flowforge.domain.execution.ErrorDetails;
import java.util.Objects;
import java.util.Set;

/**
 * Outcome of one node run.
 *
 * @param output           JSON passed to every downstream node connected to an activated output (null on failure)
 * @param activatedOutputs output handles whose edges fire; downstream nodes on other handles are SKIPPED.
 *                         Ordinary nodes activate {@code main}; a condition activates {@code true} or {@code false}.
 * @param error            set exactly when the node failed
 */
public record NodeExecutionResult(JsonNode output, Set<String> activatedOutputs, ErrorDetails error) {

    public static final String MAIN = "main";

    public NodeExecutionResult {
        activatedOutputs = activatedOutputs == null ? Set.of() : Set.copyOf(activatedOutputs);
        if (error == null && output == null) {
            output = NullNode.getInstance();
        }
    }

    public static NodeExecutionResult success(JsonNode output) {
        return new NodeExecutionResult(output, Set.of(MAIN), null);
    }

    /** Success that continues only along {@code handle} (e.g. a condition's {@code true}). */
    public static NodeExecutionResult branch(JsonNode output, String handle) {
        return new NodeExecutionResult(output, Set.of(Objects.requireNonNull(handle)), null);
    }

    public static NodeExecutionResult failure(ErrorDetails error) {
        return new NodeExecutionResult(null, Set.of(), Objects.requireNonNull(error));
    }

    public static NodeExecutionResult failure(String code, String message) {
        return failure(ErrorDetails.sanitized(code, message));
    }

    public boolean succeeded() {
        return error == null;
    }
}
