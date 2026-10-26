package com.flowforge.api.dto;

import com.flowforge.application.execution.ExecutionQueryService.NodesView;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Node-level results of one execution, in execution order. */
public record ExecutionNodesResponse(UUID executionId, List<ExecutionResponse.NodeRun> nodes) {

    public static ExecutionNodesResponse from(NodesView view) {
        Map<String, String> names = ExecutionResponse.names(view.execution());
        return new ExecutionNodesResponse(view.execution().getId(),
                view.nodes().stream().map(n -> ExecutionResponse.NodeRun.from(n, names)).toList());
    }
}
