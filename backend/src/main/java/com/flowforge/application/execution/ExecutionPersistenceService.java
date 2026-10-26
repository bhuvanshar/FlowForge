package com.flowforge.application.execution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowforge.application.ResourceNotFoundException;
import com.flowforge.config.ExecutionProperties;
import com.flowforge.domain.execution.ErrorDetails;
import com.flowforge.domain.execution.ExecutionPlan;
import com.flowforge.domain.execution.ExecutionStatus;
import com.flowforge.domain.execution.NodeExecutionStatus;
import com.flowforge.domain.execution.TriggerType;
import com.flowforge.infrastructure.persistence.ExecutionLogEntity;
import com.flowforge.infrastructure.persistence.ExecutionLogRepository;
import com.flowforge.infrastructure.persistence.NodeExecutionEntity;
import com.flowforge.infrastructure.persistence.NodeExecutionRepository;
import com.flowforge.infrastructure.persistence.WorkflowEntity;
import com.flowforge.infrastructure.persistence.WorkflowExecutionEntity;
import com.flowforge.infrastructure.persistence.WorkflowExecutionRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * JPA persistence of execution history. Each public method is one short transaction; the engine never holds a
 * transaction open while a node runs.
 */
@Service
public class ExecutionPersistenceService implements ExecutionPersistence {

    public static final String TRUNCATED_MARKER = "_truncated";
    private static final int PREVIEW_CHARS = 2000;

    private final WorkflowExecutionRepository executions;
    private final NodeExecutionRepository nodeExecutions;
    private final ExecutionLogRepository logs;
    private final ObjectMapper objectMapper;
    private final ExecutionProperties properties;
    private final Clock clock;

    public ExecutionPersistenceService(WorkflowExecutionRepository executions, NodeExecutionRepository nodeExecutions,
                                       ExecutionLogRepository logs, ObjectMapper objectMapper,
                                       ExecutionProperties properties, Clock clock) {
        this.executions = executions;
        this.nodeExecutions = nodeExecutions;
        this.logs = logs;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
    }

    public record Created(UUID executionId, Map<String, UUID> nodeExecutionIds) {
    }

    public record ExecutionView(WorkflowExecutionEntity execution, List<NodeExecutionEntity> nodes,
                                List<ExecutionLogEntity> logs) {
    }

    /** Creates a manual QUEUED execution; see {@link #createQueued(WorkflowEntity, ExecutionPlan, TriggerType, Instant)}. */
    @Transactional
    public Created createQueued(WorkflowEntity workflow, ExecutionPlan plan) {
        return createQueued(workflow, plan, TriggerType.MANUAL, null);
    }

    /**
     * Creates the QUEUED execution with its definition snapshot and one PENDING row per planned node. Joins the
     * caller's transaction if there is one (the scheduler claims the occurrence and creates the run atomically).
     *
     * @param scheduledFor the schedule occurrence, required for {@link TriggerType#SCHEDULE}; unique per workflow
     */
    @Transactional
    public Created createQueued(WorkflowEntity workflow, ExecutionPlan plan, TriggerType triggerType,
                                Instant scheduledFor) {
        // The early check in prepare() is advisory: concurrent requests may all pass it. Reserve capacity
        // in the same transaction as the insert, including scheduled and webhook executions.
        executions.lockAdmission(workflow.getId());
        if (activeCount(workflow.getId()) >= properties.maxActivePerWorkflow()) {
            throw new ExecutionCapacityException("Workflow " + workflow.getId() + " already has "
                    + properties.maxActivePerWorkflow() + " queued or running executions. Try again later.");
        }
        WorkflowExecutionEntity execution = executions.saveAndFlush(
                WorkflowExecutionEntity.queued(UUID.randomUUID(), workflow, triggerType, scheduledFor));
        Map<String, UUID> ids = new LinkedHashMap<>();
        for (ExecutionPlan.Step step : plan.steps()) {
            NodeExecutionEntity node = new NodeExecutionEntity(UUID.randomUUID(), execution.getId(), step.node().id(),
                    step.node().type(), step.index());
            nodeExecutions.save(node);
            ids.put(step.node().id(), node.getId());
        }
        return new Created(execution.getId(), ids);
    }

    @Override
    @Transactional
    public void executionStarted(UUID executionId, Instant at) {
        execution(executionId).markRunning(at);
    }

    @Override
    @Transactional
    public void nodeStarted(UUID nodeExecutionId, Instant at, JsonNode input) {
        node(nodeExecutionId).markRunning(at, storable(input));
    }

    @Override
    @Transactional
    public void nodeSucceeded(UUID nodeExecutionId, Instant at, JsonNode output) {
        node(nodeExecutionId).markSucceeded(at, storable(output));
    }

    @Override
    @Transactional
    public void nodeFailed(UUID nodeExecutionId, Instant at, ErrorDetails error) {
        node(nodeExecutionId).markFailed(at, error);
    }

    @Override
    @Transactional
    public void nodeSkipped(UUID nodeExecutionId, Instant at) {
        node(nodeExecutionId).markSkipped(at);
    }

    @Override
    @Transactional
    public void appendLogs(UUID executionId, List<ExecutionContext.LogEntry> entries) {
        logs.saveAll(entries.stream()
                .map(e -> new ExecutionLogEntity(executionId, e.nodeId(), e.level(), e.message(), e.at()))
                .toList());
    }

    @Override
    @Transactional
    public void executionFinished(UUID executionId, ExecutionStatus status, Instant at, ErrorDetails error) {
        WorkflowExecutionEntity execution = execution(executionId);
        switch (status) {
            case SUCCEEDED -> execution.markSucceeded(at);
            case FAILED -> execution.markFailed(at, error);
            case CANCELLED -> execution.markCancelled(at);
            default -> throw new IllegalArgumentException("Not a terminal status: " + status);
        }
    }

    /**
     * Ends an execution that has no engine thread (rejected by the queue, cancelled after a restart, or interrupted by a
     * restart): RUNNING nodes fail, PENDING nodes are skipped, the execution gets {@code status}.
     */
    @Override
    @Transactional
    public void abandon(UUID executionId, ExecutionStatus status, ErrorDetails error) {
        if (execution(executionId).getStatus().isTerminal()) {
            return; // finished in the meantime (e.g. cancel raced with completion)
        }
        var now = clock.instant();
        for (NodeExecutionEntity node : nodeExecutions.findByExecutionIdOrderBySequenceAsc(executionId)) {
            if (node.getStatus() == NodeExecutionStatus.RUNNING) {
                node.markFailed(now, error != null ? error : ErrorDetails.sanitized("CANCELLED", "Cancelled"));
            } else if (node.getStatus() == NodeExecutionStatus.PENDING) {
                node.markSkipped(now);
            }
        }
        executionFinished(executionId, status, now, error);
    }

    /** Queued or running executions of one workflow. */
    @Transactional(readOnly = true)
    public long activeCount(UUID workflowId) {
        return executions.countByWorkflowIdAndStatusIn(workflowId, List.of(ExecutionStatus.QUEUED,
                ExecutionStatus.RUNNING));
    }

    @Transactional(readOnly = true)
    public List<UUID> findUnfinished() {
        return executions.findByStatusInOrderByCreatedAtAsc(List.of(ExecutionStatus.QUEUED, ExecutionStatus.RUNNING))
                .stream().map(WorkflowExecutionEntity::getId).toList();
    }

    @Transactional(readOnly = true)
    public ExecutionView find(UUID executionId) {
        return new ExecutionView(execution(executionId), nodeExecutions.findByExecutionIdOrderBySequenceAsc(executionId),
                logs.findByExecutionIdOrderByIdAsc(executionId));
    }

    /**
     * Credential-looking fields are redacted first ({@link PayloadRedactor}). Payloads above the storage limit are replaced by a marker object with a text preview. Downstream nodes still get
     * the full value; only the stored history is shortened (the database caps payloads at 1 MiB).
     */
    JsonNode storable(JsonNode value) {
        if (value == null) {
            return null;
        }
        value = PayloadRedactor.redact(value);
        String text;
        try {
            text = objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Node payload is not serializable", e);
        }
        int bytes = text.getBytes(StandardCharsets.UTF_8).length;
        if (bytes <= properties.maxStoredPayloadBytes()) {
            return value;
        }
        ObjectNode marker = objectMapper.createObjectNode();
        marker.put(TRUNCATED_MARKER, true);
        marker.put("originalSizeBytes", bytes);
        marker.put("preview", text.substring(0, Math.min(PREVIEW_CHARS, text.length())));
        return marker;
    }

    private WorkflowExecutionEntity execution(UUID id) {
        return executions.findById(id).orElseThrow(() -> new ResourceNotFoundException("Execution " + id + " not found."));
    }

    private NodeExecutionEntity node(UUID id) {
        return nodeExecutions.findById(id)
                .orElseThrow(() -> new IllegalStateException("Node execution " + id + " not found"));
    }
}
