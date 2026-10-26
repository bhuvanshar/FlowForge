package com.flowforge.infrastructure.nodes;

import com.flowforge.application.execution.NodeExecutionRequest;
import com.flowforge.application.execution.NodeExecutionResult;
import com.flowforge.application.execution.NodeExecutor;
import org.springframework.stereotype.Component;

/** {@code core.manualTrigger}: emits the execution's trigger input (request body or configured test input). */
@Component
public class ManualTriggerExecutor implements NodeExecutor {

    @Override
    public String nodeType() {
        return "core.manualTrigger";
    }

    @Override
    public NodeExecutionResult execute(NodeExecutionRequest request) {
        return NodeExecutionResult.success(request.input());
    }
}
