package com.flowforge.infrastructure.nodes;

import com.flowforge.application.execution.NodeExecutionRequest;
import com.flowforge.application.execution.NodeExecutionResult;
import com.flowforge.application.execution.NodeExecutor;
import com.flowforge.application.trigger.TriggerNodes;
import org.springframework.stereotype.Component;

/**
 * {@code core.webhookTrigger}: emits the execution's trigger input. For webhook runs that is the mapped request (the
 * JSON body, or the request envelope in {@code inputMode: "request"}); for manual test runs, the configured test input.
 */
@Component
public class WebhookTriggerExecutor implements NodeExecutor {

    @Override
    public String nodeType() {
        return TriggerNodes.WEBHOOK;
    }

    @Override
    public NodeExecutionResult execute(NodeExecutionRequest request) {
        return NodeExecutionResult.success(request.input());
    }
}
