package com.flowforge.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.domain.execution.ErrorDetails;
import com.flowforge.domain.execution.ExecutionStatus;
import com.flowforge.domain.execution.NodeExecutionStatus;
import com.flowforge.domain.workflow.WorkflowDefinition;
import com.flowforge.support.PostgresIntegrationTest;
import com.flowforge.support.WorkflowSchema;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

class ExecutionRepositoryIT extends PostgresIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");

    @Autowired
    WorkflowRepository workflows;

    @Autowired
    WorkflowExecutionRepository executions;

    @Autowired
    NodeExecutionRepository nodeExecutions;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper objectMapper;

    private WorkflowEntity workflow;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.execute("TRUNCATE workflows CASCADE");
        WorkflowDefinition example = objectMapper.readValue(WorkflowSchema.exampleDefinition(),
                WorkflowDefinition.class);
        workflow = workflows.save(new WorkflowEntity(UUID.randomUUID(), "Orders", null, example));
    }

    @Test
    void snapshotIsIndependentOfLaterWorkflowEdits() {
        WorkflowExecutionEntity execution = executions.save(
                WorkflowExecutionEntity.queued(UUID.randomUUID(), workflow));
        WorkflowDefinition original = workflow.getDefinition();

        WorkflowEntity edited = workflows.findById(workflow.getId()).orElseThrow();
        edited.updateDefinition(WorkflowDefinition.empty());
        workflows.save(edited);

        WorkflowExecutionEntity loaded = executions.findById(execution.getId()).orElseThrow();
        assertThat(loaded.getWorkflowSnapshot()).isEqualTo(original);
        assertThat(loaded.getWorkflowVersion()).isEqualTo(1);
        assertThat(loaded.getStatus()).isEqualTo(ExecutionStatus.QUEUED);
        assertThat(loaded.getCreatedAt()).isNotNull();
    }

    @Test
    void persistsExecutionLifecycleAndSanitizedError() {
        WorkflowExecutionEntity execution = executions.save(
                WorkflowExecutionEntity.queued(UUID.randomUUID(), workflow));

        WorkflowExecutionEntity running = executions.findById(execution.getId()).orElseThrow();
        running.markRunning(T0);
        executions.save(running);

        WorkflowExecutionEntity failing = executions.findById(execution.getId()).orElseThrow();
        failing.markFailed(T0.plusSeconds(2), ErrorDetails.sanitized("HTTP_ERROR", "401 token=abc"));
        executions.save(failing);

        WorkflowExecutionEntity loaded = executions.findById(execution.getId()).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(loaded.getStartedAt()).isEqualTo(T0);
        assertThat(loaded.getFinishedAt()).isEqualTo(T0.plusSeconds(2));
        assertThat(loaded.getError()).isEqualTo(new ErrorDetails("HTTP_ERROR", "401 token=[REDACTED]"));
        assertThat(loaded.getLockVersion()).isEqualTo(2);
    }

    @Test
    void invalidTransitionsAreRejectedBeforeReachingTheDatabase() {
        WorkflowExecutionEntity execution = WorkflowExecutionEntity.queued(UUID.randomUUID(), workflow);

        assertThatThrownBy(() -> execution.markSucceeded(T0)).isInstanceOf(IllegalStateException.class);
        execution.markCancelled(T0);
        assertThatThrownBy(() -> execution.markRunning(T0)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void nodeExecutionInputAndOutputRoundTripAsJson() throws Exception {
        WorkflowExecutionEntity execution = executions.save(
                WorkflowExecutionEntity.queued(UUID.randomUUID(), workflow));
        JsonNode input = objectMapper.readTree(
                "{\"items\":[{\"order\":{\"total\":120.5,\"tags\":[\"a\",\"b\"],\"note\":null}}],\"ok\":true}");
        JsonNode output = objectMapper.readTree("[{\"handle\":\"true\",\"count\":1}]");

        NodeExecutionEntity second = new NodeExecutionEntity(UUID.randomUUID(), execution.getId(), "check",
                "core.if", 1);
        second.markRunning(T0, input);
        second.markSucceeded(T0.plusMillis(15), output);
        NodeExecutionEntity first = new NodeExecutionEntity(UUID.randomUUID(), execution.getId(), "trigger",
                "core.manualTrigger", 0);
        first.markRunning(T0, null);
        first.markFailed(T0.plusMillis(1), ErrorDetails.sanitized("BOOM", "failed"));
        nodeExecutions.saveAll(List.of(second, first));

        List<NodeExecutionEntity> loaded = nodeExecutions.findByExecutionIdOrderBySequenceAsc(execution.getId());

        assertThat(loaded).extracting(NodeExecutionEntity::getNodeId).containsExactly("trigger", "check");
        assertThat(loaded.get(0).getStatus()).isEqualTo(NodeExecutionStatus.FAILED);
        assertThat(loaded.get(0).getError().code()).isEqualTo("BOOM");
        assertThat(loaded.get(0).getInput()).isNull();
        assertThat(loaded.get(1).getInput()).isEqualTo(input);
        assertThat(loaded.get(1).getOutput()).isEqualTo(output);
        assertThat(loaded.get(1).getFinishedAt()).isEqualTo(T0.plusMillis(15));
    }

    @Test
    void oversizedNodeOutputIsRejectedByDatabase() throws Exception {
        WorkflowExecutionEntity execution = executions.save(
                WorkflowExecutionEntity.queued(UUID.randomUUID(), workflow));
        NodeExecutionEntity node = new NodeExecutionEntity(UUID.randomUUID(), execution.getId(), "trigger",
                "core.manualTrigger", 0);
        node.markRunning(T0, null);
        node.markSucceeded(T0, objectMapper.getNodeFactory().textNode("x".repeat(1_048_576)));

        assertThatThrownBy(() -> nodeExecutions.saveAndFlush(node))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void queriesByWorkflowAndActiveStatus() {
        WorkflowExecutionEntity queued = executions.save(WorkflowExecutionEntity.queued(UUID.randomUUID(), workflow));
        WorkflowExecutionEntity done = WorkflowExecutionEntity.queued(UUID.randomUUID(), workflow);
        done.markRunning(T0);
        done.markSucceeded(T0.plusSeconds(1));
        executions.save(done);

        assertThat(executions.findByWorkflowIdAndStatusInOrderByCreatedAtDesc(workflow.getId(),
                java.util.EnumSet.allOf(ExecutionStatus.class), PageRequest.of(0, 10)))
                .hasSize(2);
        assertThat(executions.findByStatusInOrderByCreatedAtAsc(List.of(ExecutionStatus.QUEUED, ExecutionStatus.RUNNING)))
                .extracting(WorkflowExecutionEntity::getId).containsExactly(queued.getId());
    }

    @Test
    void retentionDeleteRemovesOldFinishedExecutionsAndTheirNodes() {
        WorkflowExecutionEntity old = WorkflowExecutionEntity.queued(UUID.randomUUID(), workflow);
        old.markRunning(T0.minus(40, ChronoUnit.DAYS));
        old.markSucceeded(T0.minus(40, ChronoUnit.DAYS));
        executions.save(old);
        NodeExecutionEntity oldNode = new NodeExecutionEntity(UUID.randomUUID(), old.getId(), "trigger",
                "core.manualTrigger", 0);
        nodeExecutions.save(oldNode);
        WorkflowExecutionEntity recent = WorkflowExecutionEntity.queued(UUID.randomUUID(), workflow);
        recent.markRunning(T0);
        recent.markSucceeded(T0);
        executions.save(recent);
        WorkflowExecutionEntity active = executions.save(WorkflowExecutionEntity.queued(UUID.randomUUID(), workflow));

        int deleted = executions.deleteFinishedBefore(T0.minus(30, ChronoUnit.DAYS));

        assertThat(deleted).isEqualTo(1);
        assertThat(executions.findAll()).extracting(WorkflowExecutionEntity::getId)
                .containsExactlyInAnyOrder(recent.getId(), active.getId());
        assertThat(nodeExecutions.findById(oldNode.getId())).isEmpty();
    }
}
