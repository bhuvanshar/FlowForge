package com.flowforge.application.execution;

import com.flowforge.domain.execution.ErrorDetails;
import com.flowforge.domain.execution.ExecutionStatus;
import com.flowforge.domain.execution.NodeExecutionStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * A status change of an execution or one of its nodes, published <em>after</em> it was persisted. Events are hints for live
 * displays; the database stays the source of truth, so events carry no node input/output.
 *
 * @param seq        order of delivery within the broadcast channel of this execution (starts at 1); not persisted
 * @param nodeId     set for node events
 * @param status     the new {@link NodeExecutionStatus} (node events) or {@link ExecutionStatus} (execution events)
 * @param durationMs for node succeeded/failed and for terminal execution events, when known
 * @param error      sanitized error for node failed / execution failed
 */
public record ExecutionEvent(long seq, Type type, UUID executionId, String nodeId, String nodeName, String status,
                             Instant at, Long durationMs, Error error) {

    /** The SSE event names are the {@link #eventName()} values. */
    public enum Type {
        EXECUTION_STARTED("execution.started"),
        NODE_STARTED("node.started"),
        NODE_SUCCEEDED("node.succeeded"),
        NODE_FAILED("node.failed"),
        NODE_SKIPPED("node.skipped"),
        EXECUTION_COMPLETED("execution.completed"),
        EXECUTION_FAILED("execution.failed"),
        EXECUTION_CANCELLED("execution.cancelled");

        private final String eventName;

        Type(String eventName) {
            this.eventName = eventName;
        }

        public String eventName() {
            return eventName;
        }

        public boolean isTerminal() {
            return this == EXECUTION_COMPLETED || this == EXECUTION_FAILED || this == EXECUTION_CANCELLED;
        }
    }

    public record Error(String code, String message) {

        static Error of(ErrorDetails details) {
            return details == null ? null : new Error(details.code(), details.message());
        }
    }

    public ExecutionEvent withSeq(long newSeq) {
        return new ExecutionEvent(newSeq, type, executionId, nodeId, nodeName, status, at, durationMs, error);
    }

    public boolean isTerminal() {
        return type.isTerminal();
    }

    public static ExecutionEvent execution(Type type, UUID executionId, ExecutionStatus status, Instant at,
                                           Long durationMs, ErrorDetails error) {
        return new ExecutionEvent(0, type, executionId, null, null, status.name(), at, durationMs, Error.of(error));
    }

    public static ExecutionEvent node(Type type, UUID executionId, String nodeId, String nodeName,
                                      NodeExecutionStatus status, Instant at, Long durationMs, ErrorDetails error) {
        return new ExecutionEvent(0, type, executionId, nodeId, nodeName, status.name(), at, durationMs,
                Error.of(error));
    }

    /** The terminal event type matching an execution status. */
    public static Type terminalTypeOf(ExecutionStatus status) {
        return switch (status) {
            case SUCCEEDED -> Type.EXECUTION_COMPLETED;
            case FAILED -> Type.EXECUTION_FAILED;
            case CANCELLED -> Type.EXECUTION_CANCELLED;
            default -> throw new IllegalArgumentException("Not a terminal status: " + status);
        };
    }
}
