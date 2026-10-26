package com.flowforge.infrastructure.persistence;

import com.flowforge.domain.execution.ExecutionStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface WorkflowExecutionRepository extends JpaRepository<WorkflowExecutionEntity, UUID> {

    /** Serialize admissions per workflow until commit, without locking trigger/workflow rows in reverse order. */
    @Query(value = "select 1 from pg_advisory_xact_lock(hashtextextended(cast(:workflowId as text), 0))",
            nativeQuery = true)
    int lockAdmission(@Param("workflowId") UUID workflowId);

    Page<WorkflowExecutionEntity> findByWorkflowIdAndStatusInOrderByCreatedAtDesc(UUID workflowId,
            Collection<ExecutionStatus> statuses, Pageable pageable);

    /** Newest execution of one workflow (see {@link #findLatestPerWorkflow}). */
    interface LatestRow {
        UUID getWorkflowId();

        UUID getId();

        String getStatus();

        String getTriggerType();

        Instant getCreatedAt();

        Instant getFinishedAt();
    }

    /** The most recent execution of each given workflow in one query (uses idx_workflow_executions_workflow_created). */
    @Query(value = "select distinct on (workflow_id) workflow_id as workflowId, id as id, status as status, "
            + "trigger_type as triggerType, created_at as createdAt, finished_at as finishedAt "
            + "from workflow_executions where workflow_id in (:ids) order by workflow_id, created_at desc",
            nativeQuery = true)
    List<LatestRow> findLatestPerWorkflow(@Param("ids") Collection<UUID> workflowIds);

    long countByWorkflowIdAndStatusIn(UUID workflowId, java.util.Collection<com.flowforge.domain.execution.ExecutionStatus> statuses);

    boolean existsByWorkflowIdAndScheduledFor(UUID workflowId, Instant scheduledFor);

    List<WorkflowExecutionEntity> findByStatusInOrderByCreatedAtAsc(Collection<ExecutionStatus> statuses);

    /**
     * Retention support: bulk-deletes finished executions older than {@code cutoff}. Node executions are removed
     * by the {@code ON DELETE CASCADE} foreign key. Bypasses the persistence context, so callers must not hold
     * loaded instances of the deleted rows.
     *
     * @return number of deleted executions
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from WorkflowExecutionEntity e where e.finishedAt is not null and e.finishedAt < :cutoff")
    int deleteFinishedBefore(@Param("cutoff") Instant cutoff);
}
