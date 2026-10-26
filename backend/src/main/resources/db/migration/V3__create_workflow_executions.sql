-- Module 2: one row per workflow run. `workflow_snapshot` is an immutable copy of the definition that was executed,
-- so history stays accurate after the workflow is edited.
CREATE TABLE workflow_executions (
    id                 UUID          PRIMARY KEY,
    workflow_id        UUID          NOT NULL,
    workflow_version   INTEGER       NOT NULL,
    workflow_snapshot  JSONB         NOT NULL,
    status             VARCHAR(20)   NOT NULL DEFAULT 'QUEUED',
    error_code         VARCHAR(100),
    error_message      VARCHAR(2000),
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    started_at         TIMESTAMPTZ,
    finished_at        TIMESTAMPTZ,
    lock_version       BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT fk_workflow_executions_workflow
        FOREIGN KEY (workflow_id) REFERENCES workflows (id) ON DELETE CASCADE,
    CONSTRAINT chk_workflow_executions_status
        CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT chk_workflow_executions_version_positive CHECK (workflow_version >= 1),
    CONSTRAINT chk_workflow_executions_snapshot_shape CHECK (COALESCE(
        -- COALESCE: a missing key yields NULL, and a NULL check result would otherwise pass.
        jsonb_typeof(workflow_snapshot) = 'object'
        AND jsonb_typeof(workflow_snapshot -> 'schemaVersion') = 'number'
        AND jsonb_typeof(workflow_snapshot -> 'nodes') = 'array'
        AND jsonb_typeof(workflow_snapshot -> 'edges') = 'array',
        false)),
    CONSTRAINT chk_workflow_executions_snapshot_size CHECK (octet_length(workflow_snapshot::text) <= 1048576),
    -- Lifecycle invariants.
    CONSTRAINT chk_workflow_executions_running_started
        CHECK (status <> 'RUNNING' OR started_at IS NOT NULL),
    CONSTRAINT chk_workflow_executions_queued_not_finished
        CHECK (status <> 'QUEUED' OR (started_at IS NULL AND finished_at IS NULL)),
    CONSTRAINT chk_workflow_executions_terminal_finished
        CHECK (status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED') OR finished_at IS NOT NULL),
    CONSTRAINT chk_workflow_executions_time_order
        CHECK (started_at IS NULL OR finished_at IS NULL OR finished_at >= started_at),
    -- Errors: FAILED requires a code; a message never appears without a code.
    CONSTRAINT chk_workflow_executions_failed_has_error
        CHECK (status <> 'FAILED' OR error_code IS NOT NULL),
    CONSTRAINT chk_workflow_executions_error_message_needs_code
        CHECK (error_message IS NULL OR error_code IS NOT NULL)
);

CREATE INDEX idx_workflow_executions_workflow_created ON workflow_executions (workflow_id, created_at DESC);
-- Small partial index for "what is currently queued/running".
CREATE INDEX idx_workflow_executions_active ON workflow_executions (status, created_at)
    WHERE status IN ('QUEUED', 'RUNNING');
-- Supports retention cleanup (delete finished executions older than a cutoff).
CREATE INDEX idx_workflow_executions_finished_at ON workflow_executions (finished_at)
    WHERE finished_at IS NOT NULL;
