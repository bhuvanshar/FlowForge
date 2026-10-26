package com.flowforge.application.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.flowforge.domain.execution.ErrorDetails;
import com.flowforge.domain.execution.ExecutionPlan;
import com.flowforge.domain.execution.ExecutionStatus;
import com.flowforge.domain.workflow.WorkflowDefinition;
import com.flowforge.infrastructure.persistence.NodeExecutionEntity;
import com.flowforge.infrastructure.persistence.WorkflowEntity;
import com.flowforge.infrastructure.persistence.WorkflowExecutionEntity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Test double for {@link ExecutionPersistence} backed by the real entity classes, so the same state-transition rules
 * as in production apply (an illegal transition throws). Also records an event log for ordering assertions.
 */
class InMemoryExecutionPersistence implements ExecutionPersistence {

    final List<String> events = Collections.synchronizedList(new ArrayList<>());
    private final Map<UUID, NodeExecutionEntity> nodes = new LinkedHashMap<>();
    private final Map<String, NodeExecutionEntity> nodesById = new LinkedHashMap<>();
    private WorkflowExecutionEntity execution;
    RuntimeException failOnNodeStarted;

    ExecutionJob prepare(WorkflowDefinition definition, ExecutionPlan plan, JsonNode input, CancellationToken token) {
        WorkflowEntity workflow = new WorkflowEntity(UUID.randomUUID(), "wf", null, definition);
        execution = WorkflowExecutionEntity.queued(UUID.randomUUID(), workflow);
        Map<String, UUID> ids = new LinkedHashMap<>();
        for (ExecutionPlan.Step step : plan.steps()) {
            NodeExecutionEntity node = new NodeExecutionEntity(UUID.randomUUID(), execution.getId(), step.node().id(),
                    step.node().type(), step.index());
            nodes.put(node.getId(), node);
            nodesById.put(step.node().id(), node);
            ids.put(step.node().id(), node.getId());
        }
        return new ExecutionJob(execution.getId(), workflow.getId(), 1, plan, ids, input, token);
    }

    WorkflowExecutionEntity execution() {
        return execution;
    }

    NodeExecutionEntity node(String nodeId) {
        return nodesById.get(nodeId);
    }

    @Override
    public void executionStarted(UUID executionId, Instant at) {
        events.add("execution:running");
        execution.markRunning(at);
    }

    @Override
    public void nodeStarted(UUID id, Instant at, JsonNode input) {
        if (failOnNodeStarted != null) {
            throw failOnNodeStarted;
        }
        events.add("start:" + nodes.get(id).getNodeId());
        nodes.get(id).markRunning(at, input);
    }

    @Override
    public void nodeSucceeded(UUID id, Instant at, JsonNode output) {
        events.add("ok:" + nodes.get(id).getNodeId());
        nodes.get(id).markSucceeded(at, output);
    }

    @Override
    public void nodeFailed(UUID id, Instant at, ErrorDetails error) {
        events.add("fail:" + nodes.get(id).getNodeId());
        nodes.get(id).markFailed(at, error);
    }

    @Override
    public void nodeSkipped(UUID id, Instant at) {
        events.add("skip:" + nodes.get(id).getNodeId());
        nodes.get(id).markSkipped(at);
    }

    final List<ExecutionContext.LogEntry> logs = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void appendLogs(UUID executionId, List<ExecutionContext.LogEntry> entries) {
        entries.forEach(e -> events.add("log:" + e.nodeId()));
        logs.addAll(entries);
    }

    @Override
    public void executionFinished(UUID executionId, ExecutionStatus status, Instant at, ErrorDetails error) {
        events.add("execution:" + status);
        switch (status) {
            case SUCCEEDED -> execution.markSucceeded(at);
            case FAILED -> execution.markFailed(at, error);
            case CANCELLED -> execution.markCancelled(at);
            default -> throw new IllegalArgumentException(status.toString());
        }
    }

    @Override
    public void abandon(UUID executionId, ExecutionStatus status, ErrorDetails error) {
        if (execution.getStatus().isTerminal()) return;
        Instant now = Instant.now();
        for (NodeExecutionEntity node : nodes.values()) {
            switch (node.getStatus()) {
                case RUNNING -> nodeFailed(node.getId(), now, error);
                case PENDING -> nodeSkipped(node.getId(), now);
                default -> { }
            }
        }
        executionFinished(executionId, status, now, error);
    }
}
