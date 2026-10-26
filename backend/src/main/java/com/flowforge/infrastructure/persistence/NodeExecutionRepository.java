package com.flowforge.infrastructure.persistence;

import com.flowforge.domain.execution.NodeExecutionStatus;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NodeExecutionRepository extends JpaRepository<NodeExecutionEntity, UUID> {

    List<NodeExecutionEntity> findByExecutionIdOrderBySequenceAsc(UUID executionId);

    List<NodeExecutionEntity> findByExecutionIdAndStatusInOrderBySequenceAsc(UUID executionId,
                                                                            Collection<NodeExecutionStatus> statuses);

    /** Light projection (no input/output payloads) used to summarise many executions at once. */
    interface StatusRow {
        UUID getExecutionId();

        String getNodeId();

        int getSequence();

        NodeExecutionStatus getStatus();
    }

    @Query("select n.executionId as executionId, n.nodeId as nodeId, n.sequence as sequence, n.status as status "
            + "from NodeExecutionEntity n where n.executionId in :ids order by n.sequence")
    List<StatusRow> findStatusRows(@Param("ids") Collection<UUID> ids);
}
