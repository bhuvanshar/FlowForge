package com.flowforge.application.execution;

import com.flowforge.domain.execution.ErrorDetails;
import com.flowforge.domain.execution.ExecutionStatus;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Tracks executions owned by this process (queued or running) and their cancellation tokens.
 *
 * <p>Exactly one writer per execution: once registered, only its engine thread changes its rows. Cancelling sets the
 * token; the engine notices it before the next node, or interrupts the running node, and records CANCELLED itself.
 * Executions without an owner (left over from a previous process) are finished here instead.
 */
@Component
public class ExecutionStateManager {

    private static final Logger log = LoggerFactory.getLogger(ExecutionStateManager.class);

    private final Map<UUID, CancellationToken> active = new ConcurrentHashMap<>();
    private final ExecutionPersistenceService persistence;
    private final ExecutionEventSink events;

    public ExecutionStateManager(ExecutionPersistenceService persistence, ExecutionEventSink events) {
        this.persistence = persistence;
        this.events = events;
    }

    public CancellationToken register(UUID executionId) {
        CancellationToken token = new CancellationToken();
        active.put(executionId, token);
        return token;
    }

    public void unregister(UUID executionId) {
        active.remove(executionId);
    }

    public boolean isActive(UUID executionId) {
        return active.containsKey(executionId);
    }

    public int activeCount() {
        return active.size();
    }

    /**
     * Requests cancellation. Returns true when an engine thread owns the execution (it will record CANCELLED shortly);
     * otherwise the unowned execution is cancelled directly.
     */
    public boolean cancel(UUID executionId) {
        CancellationToken token = active.get(executionId);
        if (token != null) {
            token.cancel();
            return true;
        }
        persistence.abandon(executionId, ExecutionStatus.CANCELLED, null);
        events.publish(ExecutionEvent.execution(ExecutionEvent.Type.EXECUTION_CANCELLED, executionId,
                ExecutionStatus.CANCELLED, java.time.Instant.now(), null, null));
        return false;
    }

    /**
     * Single-instance MVP: on startup, executions still QUEUED or RUNNING belong to a process that is gone, so they are
     * marked FAILED with {@code EXECUTION_INTERRUPTED}. Must not be used with several backend instances on one database.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(0) // before triggers start creating executions (ScheduleRunner: order 100)
    public void recoverInterruptedExecutions() {
        for (UUID id : persistence.findUnfinished()) {
            if (isActive(id)) {
                continue;
            }
            ErrorDetails interrupted = ErrorDetails.sanitized("EXECUTION_INTERRUPTED",
                    "The server stopped while this execution was in progress.");
            persistence.abandon(id, ExecutionStatus.FAILED, interrupted);
            events.publish(ExecutionEvent.execution(ExecutionEvent.Type.EXECUTION_FAILED, id, ExecutionStatus.FAILED,
                    java.time.Instant.now(), null, interrupted));
            log.warn("Marked interrupted execution {} as FAILED", id);
        }
    }
}
