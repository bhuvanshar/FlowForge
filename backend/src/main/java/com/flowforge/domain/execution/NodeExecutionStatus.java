package com.flowforge.domain.execution;

/** Status of a single node run. Persisted as its name; must match {@code chk_node_executions_status}. */
public enum NodeExecutionStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    SKIPPED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == SKIPPED;
    }
}
