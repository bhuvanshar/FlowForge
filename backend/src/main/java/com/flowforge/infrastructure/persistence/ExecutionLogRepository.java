package com.flowforge.infrastructure.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExecutionLogRepository extends JpaRepository<ExecutionLogEntity, Long> {

    List<ExecutionLogEntity> findByExecutionIdOrderByIdAsc(UUID executionId);
}
