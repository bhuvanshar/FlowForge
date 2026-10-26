package com.flowforge.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.domain.workflow.WorkflowDefinition;
import com.flowforge.domain.workflow.WorkflowStatus;
import com.flowforge.support.PostgresIntegrationTest;
import com.flowforge.support.WorkflowSchema;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

class WorkflowRepositoryIT extends PostgresIntegrationTest {

    @Autowired
    WorkflowRepository workflows;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper objectMapper;

    private WorkflowDefinition example;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.execute("TRUNCATE workflows CASCADE");
        example = objectMapper.readValue(WorkflowSchema.exampleDefinition(), WorkflowDefinition.class);
    }

    @Test
    void persistsAndReloadsWorkflowWithDefinition() {
        UUID id = UUID.randomUUID();
        workflows.save(new WorkflowEntity(id, "Orders", "Routes orders", example));

        WorkflowEntity loaded = workflows.findById(id).orElseThrow();

        assertThat(loaded.getName()).isEqualTo("Orders");
        assertThat(loaded.getDescription()).isEqualTo("Routes orders");
        assertThat(loaded.getStatus()).isEqualTo(WorkflowStatus.DRAFT);
        assertThat(loaded.getVersion()).isEqualTo(1);
        assertThat(loaded.getLockVersion()).isZero();
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getUpdatedAt()).isNotNull();
        assertThat(loaded.getDefinition()).isEqualTo(example);
    }

    @Test
    void storedJsonbConformsToPublishedSchema() throws Exception {
        UUID id = UUID.randomUUID();
        workflows.save(new WorkflowEntity(id, "Orders", null, example));

        String stored = jdbc.queryForObject("select definition::text from workflows where id = ?", String.class, id);

        assertThat(jdbc.queryForObject("select jsonb_typeof(definition) from workflows where id = ?", String.class, id))
                .isEqualTo("object");
        assertThat(WorkflowSchema.validate(objectMapper.readTree(stored))).isEmpty();
        JSONAssert.assertEquals(new String(WorkflowSchema.exampleDefinition().readAllBytes()), stored,
                JSONCompareMode.STRICT);
    }

    @Test
    void definitionChangeBumpsVersionButIdenticalDefinitionDoesNot() {
        UUID id = UUID.randomUUID();
        workflows.save(new WorkflowEntity(id, "Orders", null, WorkflowDefinition.empty()));

        WorkflowEntity loaded = workflows.findById(id).orElseThrow();
        loaded.updateDefinition(WorkflowDefinition.empty());
        loaded.updateDefinition(example);
        workflows.save(loaded);

        WorkflowEntity reloaded = workflows.findById(id).orElseThrow();
        assertThat(reloaded.getVersion()).isEqualTo(2);
        assertThat(reloaded.getLockVersion()).isEqualTo(1);
        assertThat(reloaded.getDefinition()).isEqualTo(example);
        assertThat(reloaded.getUpdatedAt()).isAfterOrEqualTo(reloaded.getCreatedAt());
    }

    @Test
    void concurrentUpdateFailsWithOptimisticLock() {
        UUID id = UUID.randomUUID();
        workflows.save(new WorkflowEntity(id, "Orders", null, example));

        WorkflowEntity first = workflows.findById(id).orElseThrow();
        WorkflowEntity second = workflows.findById(id).orElseThrow();

        first.rename("Orders v2", null);
        workflows.save(first);

        second.rename("Conflicting edit", null);
        assertThatThrownBy(() -> workflows.save(second)).isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(workflows.findById(id).orElseThrow().getName()).isEqualTo("Orders v2");
    }

    @Test
    void findsByStatus() {
        WorkflowEntity active = new WorkflowEntity(UUID.randomUUID(), "Active", null, example);
        active.changeStatus(WorkflowStatus.ACTIVE);
        workflows.saveAll(List.of(active, new WorkflowEntity(UUID.randomUUID(), "Draft", null, example)));

        assertThat(workflows.findAll(WorkflowSpecifications.statusIn(List.of(WorkflowStatus.ACTIVE))))
                .extracting(WorkflowEntity::getName).containsExactly("Active");
    }
}
