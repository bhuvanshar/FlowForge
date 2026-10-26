package com.flowforge.domain.execution;

/** Status of a workflow execution. Persisted as its name; must match {@code chk_workflow_executions_status}. */
public enum ExecutionStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
