package com.flowforge.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface ScheduleTriggerRepository extends JpaRepository<ScheduleTriggerEntity, UUID> {

    /**
     * Due schedules, locked {@code FOR UPDATE SKIP LOCKED}: a schedule being handled by one scheduler pass is invisible
     * to any concurrent pass, so an occurrence can only be claimed once.
     */
    @Query("select s.workflowId from ScheduleTriggerEntity s where s.active = true and s.nextFireAt <= :now "
            + "order by s.nextFireAt")
    List<UUID> findDue(@Param("now") Instant now, Pageable pageable);

    /**
     * Locks one schedule {@code FOR UPDATE SKIP LOCKED} if it is still active and due. A schedule being handled by one
     * scheduler pass is invisible to every concurrent pass, so an occurrence can only be claimed once.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select s from ScheduleTriggerEntity s where s.workflowId = :id and s.active = true and s.nextFireAt <= :now")
    List<ScheduleTriggerEntity> lockIfDue(@Param("id") UUID workflowId, @Param("now") Instant now);
}
