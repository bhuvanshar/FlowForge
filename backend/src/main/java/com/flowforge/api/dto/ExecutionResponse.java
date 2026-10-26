package com.flowforge.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.flowforge.application.execution.ExecutionPersistenceService.ExecutionView;
import com.flowforge.domain.execution.ErrorDetails;
import com.flowforge.domain.execution.ExecutionStatus;
import com.flowforge.domain.execution.NodeExecutionStatus;
import com.flowforge.domain.execution.TriggerType;
import com.flowforge.infrastructure.persistence.ExecutionLogEntity;
import com.flowforge.infrastructure.persistence.NodeExecutionEntity;
import com.flowforge.infrastructure.persistence.WorkflowExecutionEntity;
import com.flowforge.domain.workflow.NodeDefinition;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * An execution with its node runs in plan order. Payloads may be truncated (marker {@code _truncated: true}).
 * {@code scheduledFor} is the schedule occurrence of SCHEDULE runs (omitted otherwise).
 */
public record ExecutionResponse(UUID id, UUID workflowId, int workflowVersion, ExecutionStatus status,
                                TriggerType triggerType, Instant scheduledFor, Error error, Instant createdAt, Instant startedAt, Instant finishedAt, Long durationMs,
                                List<NodeRun> nodes, List<LogLine> logs) {

    /** A sanitized line from the execution log (e.g. written by a Logger node). */
    public record LogLine(String nodeId, String level, String message, Instant at) {

        static LogLine from(ExecutionLogEntity e) {
            return new LogLine(e.getNodeId(), e.getLevel(), e.getMessage(), e.getCreatedAt());
        }
    }

    public record Error(String code, String message) {

        static Error from(ErrorDetails e) {
            return e == null ? null : new Error(e.code(), e.message());
        }
    }

    /**
     * @param nodeName name of the node in the workflow snapshot this execution ran (stable after later edits)
     * @param durationMs null while running or when the node never started
     */
    public record NodeRun(String nodeId, String nodeName, String nodeType, int sequence, NodeExecutionStatus status,
                          JsonNode input, JsonNode output, Error error, Instant startedAt, Instant finishedAt,
                          Long durationMs) {

        public static NodeRun from(NodeExecutionEntity n, Map<String, String> names) {
            return new NodeRun(n.getNodeId(), names.getOrDefault(n.getNodeId(), n.getNodeId()), n.getNodeType(),
                    n.getSequence(), n.getStatus(), n.getInput(), n.getOutput(), Error.from(n.getError()),
                    n.getStartedAt(), n.getFinishedAt(), millis(n.getStartedAt(), n.getFinishedAt()));
        }
    }

    public static Long millis(Instant from, Instant to) {
        return from == null || to == null ? null : Duration.between(from, to).toMillis();
    }

    /** Node display names from the execution's own snapshot, by node id. */
    public static Map<String, String> names(WorkflowExecutionEntity execution) {
        Map<String, String> names = new HashMap<>();
        for (NodeDefinition node : execution.getWorkflowSnapshot().nodes()) {
            names.putIfAbsent(node.id(), node.name());
        }
        return names;
    }

    public static ExecutionResponse of(WorkflowExecutionEntity e, List<NodeExecutionEntity> nodes,
                                       List<ExecutionLogEntity> logs) {
        Map<String, String> names = names(e);
        return new ExecutionResponse(e.getId(), e.getWorkflowId(), e.getWorkflowVersion(), e.getStatus(),
                e.getTriggerType(), e.getScheduledFor(), Error.from(e.getError()), e.getCreatedAt(), e.getStartedAt(), e.getFinishedAt(),
                millis(e.getStartedAt(), e.getFinishedAt()),
                nodes.stream().map(n -> NodeRun.from(n, names)).toList(), logs.stream().map(LogLine::from).toList());
    }

    public static ExecutionResponse from(ExecutionView view) {
        return of(view.execution(), view.nodes(), view.logs());
    }
}
