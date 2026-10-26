package com.flowforge.domain.execution;

/** How an execution was started. Persisted as its name; must match {@code chk_workflow_executions_trigger_type}. */
public enum TriggerType {
    MANUAL,
    WEBHOOK,
    SCHEDULE
}
