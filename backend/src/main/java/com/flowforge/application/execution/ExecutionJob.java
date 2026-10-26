package com.flowforge.application.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.flowforge.domain.execution.ExecutionPlan;
import java.util.Map;
import java.util.UUID;

/**
 * One queued execution, handed from the request thread to a worker.
 *
 * @param nodeExecutionIds node id to the id of its pre-created (PENDING) node execution row
 * @param ownerId          owner of the workflow; the run acts as this owner (e.g. when resolving credentials)
 */
public record ExecutionJob(UUID executionId, UUID workflowId, int workflowVersion, ExecutionPlan plan,
                           Map<String, UUID> nodeExecutionIds, JsonNode triggerInput, CancellationToken cancellation,
                           String ownerId) {

    public ExecutionJob {
        nodeExecutionIds = Map.copyOf(nodeExecutionIds);
        ownerId = ownerId == null ? com.flowforge.application.security.OwnerContext.LOCAL_OWNER : ownerId;
    }

    public ExecutionJob(UUID executionId, UUID workflowId, int workflowVersion, ExecutionPlan plan,
                        Map<String, UUID> nodeExecutionIds, JsonNode triggerInput, CancellationToken cancellation) {
        this(executionId, workflowId, workflowVersion, plan, nodeExecutionIds, triggerInput, cancellation, null);
    }
}
