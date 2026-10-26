package com.flowforge.domain.workflow;

/** Lifecycle state of a workflow. Persisted as its name; must match {@code chk_workflows_status}. */
public enum WorkflowStatus {
    DRAFT,
    ACTIVE,
    INACTIVE,
    ARCHIVED
}
