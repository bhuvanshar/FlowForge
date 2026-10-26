package com.flowforge.infrastructure.nodes;

import com.flowforge.application.execution.NodeExecutionRequest;
import com.flowforge.application.execution.NodeExecutionResult;
import com.flowforge.application.execution.NodeExecutor;
import com.flowforge.application.trigger.TriggerNodes;
import org.springframework.stereotype.Component;

/**
 * {@code core.scheduleTrigger}: emits the execution's trigger input. For scheduled runs that is
 * {@code {scheduledFor, firedAt, timezone, cron, late}}; for manual test runs, the configured test input.
 */
@Component
public class ScheduleTriggerExecutor implements NodeExecutor {

    @Override
    public String nodeType() {
        return TriggerNodes.SCHEDULE;
    }

    @Override
    public NodeExecutionResult execute(NodeExecutionRequest request) {
        return NodeExecutionResult.success(request.input());
    }
}
