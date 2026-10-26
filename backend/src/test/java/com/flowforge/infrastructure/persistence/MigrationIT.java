package com.flowforge.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowforge.support.PostgresIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Verifies the Flyway schema directly with SQL, independent of the JPA mappings. */
class MigrationIT extends PostgresIntegrationTest {

    private static final String DEFINITION = "{\"schemaVersion\":1,\"nodes\":[],\"edges\":[]}";

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE workflows CASCADE");
    }

    @Test
    void allMigrationsAppliedInOrder() {
        List<String> versions = jdbc.queryForList(
                "select version from flyway_schema_history where success order by installed_rank", String.class);

        assertThat(versions).containsExactly("1", "2", "3", "4", "5", "6", "7", "8");
    }

    @Test
    void foreignKeysAndIndexesExist() {
        List<String> fks = jdbc.queryForList(
                "select conname from pg_constraint where contype = 'f' order by conname", String.class);
        List<String> indexes = jdbc.queryForList(
                "select indexname from pg_indexes where schemaname = 'public' and indexname like 'idx_%'",
                String.class);

        assertThat(fks).containsExactly("fk_execution_logs_execution", "fk_node_executions_execution",
                "fk_schedule_triggers_workflow", "fk_webhook_triggers_workflow", "fk_workflow_executions_workflow");
        assertThat(indexes).contains("idx_workflows_status_updated_at", "idx_workflow_executions_workflow_created",
                "idx_workflow_executions_active", "idx_workflow_executions_finished_at",
                "idx_node_executions_execution_node", "idx_schedule_triggers_due");
    }

    @Test
    void rejectsInvalidWorkflowRows() {
        assertViolation("insert into workflows (id, name, status, definition) values (?, 'x', 'BOGUS', ?::jsonb)",
                UUID.randomUUID(), DEFINITION);
        assertViolation("insert into workflows (id, name, definition) values (?, '   ', ?::jsonb)",
                UUID.randomUUID(), DEFINITION);
        assertViolation("insert into workflows (id, name, definition) values (?, 'x', ?::jsonb)",
                UUID.randomUUID(), "{\"nodes\":[],\"edges\":[]}");
        assertViolation("insert into workflows (id, name, definition) values (?, 'x', ?::jsonb)",
                UUID.randomUUID(), "[]");
        assertViolation("insert into workflows (id, name, definition) values (?, 'x', ?::jsonb)",
                UUID.randomUUID(), "{\"schemaVersion\":1,\"nodes\":[]}");
    }

    @Test
    void rejectsSnapshotWithoutSchemaVersion() {
        assertViolation("insert into workflow_executions (id, workflow_id, workflow_version, workflow_snapshot)"
                + " values (?, ?, 1, ?::jsonb)", UUID.randomUUID(), insertWorkflow(), "{\"nodes\":[],\"edges\":[]}");
    }

    @Test
    void rejectsOversizedDefinition() {
        String huge = "{\"schemaVersion\":1,\"nodes\":[],\"edges\":[],\"pad\":\"" + "x".repeat(1_048_576) + "\"}";

        assertViolation("insert into workflows (id, name, definition) values (?, 'x', ?::jsonb)",
                UUID.randomUUID(), huge);
    }

    @Test
    void enforcesExecutionLifecycleConstraints() {
        UUID workflowId = insertWorkflow();
        String insert = "insert into workflow_executions (id, workflow_id, workflow_version, workflow_snapshot, status,"
                + " error_code, started_at, finished_at) values (?, ?, 1, ?::jsonb, ?, ?, ?::timestamptz, ?::timestamptz)";

        // RUNNING without started_at
        assertViolation(insert, UUID.randomUUID(), workflowId, DEFINITION, "RUNNING", null, null, null);
        // terminal without finished_at
        assertViolation(insert, UUID.randomUUID(), workflowId, DEFINITION, "SUCCEEDED", null, "2026-01-01T00:00:00Z",
                null);
        // FAILED without error code
        assertViolation(insert, UUID.randomUUID(), workflowId, DEFINITION, "FAILED", null, "2026-01-01T00:00:00Z",
                "2026-01-01T00:00:01Z");
        // finished before started
        assertViolation(insert, UUID.randomUUID(), workflowId, DEFINITION, "SUCCEEDED", null, "2026-01-01T00:00:01Z",
                "2026-01-01T00:00:00Z");
        // unknown workflow
        assertViolation(insert, UUID.randomUUID(), UUID.randomUUID(), DEFINITION, "QUEUED", null, null, null);
    }

    @Test
    void enforcesNodeExecutionConstraints() {
        UUID executionId = insertExecution(insertWorkflow());
        String insert = "insert into node_executions (id, execution_id, node_id, node_type, sequence)"
                + " values (?, ?, ?, 'core.noOp', ?)";
        jdbc.update(insert, UUID.randomUUID(), executionId, "a", 0);

        assertViolation(insert, UUID.randomUUID(), executionId, "b", 0); // duplicate sequence
        assertViolation(insert, UUID.randomUUID(), executionId, "bad id!", 1);
        assertViolation(insert, UUID.randomUUID(), executionId, "b", -1);
    }

    @Test
    void deletingWorkflowCascadesToExecutionsAndNodeExecutions() {
        UUID workflowId = insertWorkflow();
        UUID executionId = insertExecution(workflowId);
        jdbc.update("insert into node_executions (id, execution_id, node_id, node_type, sequence)"
                + " values (?, ?, 'a', 'core.noOp', 0)", UUID.randomUUID(), executionId);

        jdbc.update("delete from workflows where id = ?", workflowId);

        assertThat(jdbc.queryForObject("select count(*) from workflow_executions", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from node_executions", Integer.class)).isZero();
    }

    private UUID insertWorkflow() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into workflows (id, name, definition) values (?, 'wf', ?::jsonb)", id, DEFINITION);
        return id;
    }

    private UUID insertExecution(UUID workflowId) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into workflow_executions (id, workflow_id, workflow_version, workflow_snapshot)"
                + " values (?, ?, 1, ?::jsonb)", id, workflowId, DEFINITION);
        return id;
    }

    private void assertViolation(String sql, Object... args) {
        assertThatThrownBy(() -> jdbc.update(sql, args)).isInstanceOf(DataIntegrityViolationException.class);
    }
}
