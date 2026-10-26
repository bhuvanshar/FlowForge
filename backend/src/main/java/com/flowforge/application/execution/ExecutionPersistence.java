package com.flowforge.application.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.flowforge.domain.execution.ErrorDetails;
import com.flowforge.domain.execution.ExecutionStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * State changes the engine records while running. Every call is its own short transaction: nothing is held open while a
 * node runs (nodes may call external systems). Implemented by {@link ExecutionPersistenceService}.
 */
public interface ExecutionPersistence {

    void executionStarted(UUID executionId, Instant at);

    void nodeStarted(UUID nodeExecutionId, Instant at, JsonNode input);

    void nodeSucceeded(UUID nodeExecutionId, Instant at, JsonNode output);

    void nodeFailed(UUID nodeExecutionId, Instant at, ErrorDetails error);

    void nodeSkipped(UUID nodeExecutionId, Instant at);

    void appendLogs(UUID executionId, List<ExecutionContext.LogEntry> entries);

    /** @param error required for FAILED, ignored otherwise */
    void executionFinished(UUID executionId, ExecutionStatus status, Instant at, ErrorDetails error);

    /** Atomically finishes an aborted execution and closes any RUNNING/PENDING nodes; preserves terminal states. */
    void abandon(UUID executionId, ExecutionStatus status, ErrorDetails error);
}
