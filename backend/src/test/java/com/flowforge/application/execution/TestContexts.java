package com.flowforge.application.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.flowforge.domain.workflow.NodeDefinition;
import com.flowforge.domain.workflow.NodePosition;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Builds execution contexts and requests for executor unit tests. */
public final class TestContexts {

    private TestContexts() {
    }

    public static ExecutionContext context(JsonNode triggerInput) {
        return new ExecutionContext(UUID.randomUUID(), UUID.randomUUID(), 1, triggerInput,
                Instant.now().plusSeconds(600), new CancellationToken());
    }

    public static ExecutionContext context(JsonNode triggerInput, CancellationToken token) {
        return new ExecutionContext(UUID.randomUUID(), UUID.randomUUID(), 1, triggerInput,
                Instant.now().plusSeconds(600), token);
    }

    /** Records {@code output} as the output of node {@code nodeId}, as if it had run. */
    public static void ran(ExecutionContext context, String nodeId, JsonNode output) {
        context.recordOutput(nodeId, output);
    }

    public static List<ExecutionContext.LogEntry> logs(ExecutionContext context) {
        return context.drainLogs();
    }

    public static NodeDefinition node(String id, String type, Map<String, Object> configuration) {
        return new NodeDefinition(id, type, 1, "Node " + id, new NodePosition(0, 0), configuration);
    }

    public static NodeExecutionRequest request(NodeDefinition node, JsonNode input, ExecutionContext context) {
        return new NodeExecutionRequest(node, input, context);
    }
}
