package com.flowforge.application.execution;

import com.flowforge.application.InvalidRequestException;
import com.flowforge.application.ResourceNotFoundException;
import com.flowforge.application.WorkflowService;
import com.flowforge.domain.execution.ExecutionStatus;
import com.flowforge.domain.execution.NodeExecutionStatus;
import com.flowforge.infrastructure.persistence.NodeExecutionEntity;
import com.flowforge.infrastructure.persistence.NodeExecutionRepository;
import com.flowforge.infrastructure.persistence.WorkflowExecutionEntity;
import com.flowforge.infrastructure.persistence.WorkflowExecutionRepository;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side of execution history: paged lists with status filters and node-level results. */
@Service
@Transactional(readOnly = true)
public class ExecutionQueryService {

    public static final int MAX_PAGE_SIZE = 100;

    private final WorkflowService workflows;
    private final WorkflowExecutionRepository executions;
    private final NodeExecutionRepository nodeExecutions;

    public ExecutionQueryService(WorkflowService workflows, WorkflowExecutionRepository executions,
                                 NodeExecutionRepository nodeExecutions) {
        this.workflows = workflows;
        this.executions = executions;
        this.nodeExecutions = nodeExecutions;
    }

    /** Node counts of one execution (for list rows); {@code failedNodeId} is the first failed node, if any. */
    public record Summary(WorkflowExecutionEntity execution, int nodeCount, int succeeded, int failed, int skipped,
                          String failedNodeId) {
    }

    /** The newest execution of a workflow, for dashboards. */
    public record Latest(UUID id, ExecutionStatus status, com.flowforge.domain.execution.TriggerType triggerType,
                         java.time.Instant createdAt, java.time.Instant finishedAt) {
    }

    /** Newest execution per workflow in one query; workflows that never ran are absent from the map. */
    public Map<UUID, Latest> latestByWorkflow(Collection<UUID> workflowIds) {
        if (workflowIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Latest> out = new HashMap<>();
        for (WorkflowExecutionRepository.LatestRow row : executions.findLatestPerWorkflow(workflowIds)) {
            out.put(row.getWorkflowId(), new Latest(row.getId(), ExecutionStatus.valueOf(row.getStatus()),
                    com.flowforge.domain.execution.TriggerType.valueOf(row.getTriggerType()), row.getCreatedAt(),
                    row.getFinishedAt()));
        }
        return out;
    }

    public record NodesView(WorkflowExecutionEntity execution, List<NodeExecutionEntity> nodes) {
    }

    /** Executions of a workflow, newest first. Works for archived workflows too (history is kept). */
    public Page<Summary> list(UUID workflowId, Collection<ExecutionStatus> statuses, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidRequestException("page must be >= 0 and size must be between 1 and " + MAX_PAGE_SIZE);
        }
        workflows.get(workflowId);
        Collection<ExecutionStatus> filter = statuses == null || statuses.isEmpty()
                ? EnumSet.allOf(ExecutionStatus.class) : statuses;
        Page<WorkflowExecutionEntity> result = executions.findByWorkflowIdAndStatusInOrderByCreatedAtDesc(workflowId,
                filter, PageRequest.of(page, size));
        Map<UUID, List<NodeExecutionRepository.StatusRow>> rows = result.isEmpty() ? Map.of()
                : nodeExecutions.findStatusRows(result.map(WorkflowExecutionEntity::getId).toList()).stream()
                .collect(Collectors.groupingBy(NodeExecutionRepository.StatusRow::getExecutionId));
        return result.map(e -> summarize(e, rows.getOrDefault(e.getId(), List.of())));
    }

    /** Node runs of one execution in plan order, optionally filtered by status. */
    public NodesView nodes(UUID executionId, Collection<NodeExecutionStatus> statuses) {
        WorkflowExecutionEntity execution = executions.findById(executionId)
                .orElseThrow(() -> new ResourceNotFoundException("Execution " + executionId + " not found."));
        List<NodeExecutionEntity> nodes = statuses == null || statuses.isEmpty()
                ? nodeExecutions.findByExecutionIdOrderBySequenceAsc(executionId)
                : nodeExecutions.findByExecutionIdAndStatusInOrderBySequenceAsc(executionId, statuses);
        return new NodesView(execution, nodes);
    }

    private static Summary summarize(WorkflowExecutionEntity execution, List<NodeExecutionRepository.StatusRow> rows) {
        Map<NodeExecutionStatus, Integer> counts = new HashMap<>();
        String failedNode = null;
        for (var row : rows) {
            counts.merge(row.getStatus(), 1, Integer::sum);
            if (row.getStatus() == NodeExecutionStatus.FAILED && failedNode == null) {
                failedNode = row.getNodeId();
            }
        }
        return new Summary(execution, rows.size(), counts.getOrDefault(NodeExecutionStatus.SUCCEEDED, 0),
                counts.getOrDefault(NodeExecutionStatus.FAILED, 0), counts.getOrDefault(NodeExecutionStatus.SKIPPED, 0),
                failedNode);
    }
}
