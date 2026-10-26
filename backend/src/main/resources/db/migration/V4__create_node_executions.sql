-- Module 2: one row per node run inside a workflow execution. `sequence` is the order in which node runs were
-- started within the execution (a node may run more than once, e.g. inside a loop).
CREATE TABLE node_executions (
    id             UUID          PRIMARY KEY,
    execution_id   UUID          NOT NULL,
    node_id        VARCHAR(64)   NOT NULL,
    node_type      VARCHAR(100)  NOT NULL,
    sequence       INTEGER       NOT NULL,
    status         VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    input          JSONB,
    output         JSONB,
    error_code     VARCHAR(100),
    error_message  VARCHAR(2000),
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    started_at     TIMESTAMPTZ,
    finished_at    TIMESTAMPTZ,

    CONSTRAINT fk_node_executions_execution
        FOREIGN KEY (execution_id) REFERENCES workflow_executions (id) ON DELETE CASCADE,
    CONSTRAINT uq_node_executions_execution_sequence UNIQUE (execution_id, sequence),
    CONSTRAINT chk_node_executions_status
        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'SKIPPED')),
    CONSTRAINT chk_node_executions_node_id_format CHECK (node_id ~ '^[A-Za-z0-9_-]{1,64}$'),
    CONSTRAINT chk_node_executions_sequence_non_negative CHECK (sequence >= 0),
    -- Hard caps (1 MiB of JSON text each). The execution engine must truncate or summarise larger payloads first.
    CONSTRAINT chk_node_executions_input_size CHECK (input IS NULL OR octet_length(input::text) <= 1048576),
    CONSTRAINT chk_node_executions_output_size CHECK (output IS NULL OR octet_length(output::text) <= 1048576),
    CONSTRAINT chk_node_executions_running_started
        CHECK (status <> 'RUNNING' OR started_at IS NOT NULL),
    CONSTRAINT chk_node_executions_terminal_finished
        CHECK (status NOT IN ('SUCCEEDED', 'FAILED', 'SKIPPED') OR finished_at IS NOT NULL),
    CONSTRAINT chk_node_executions_time_order
        CHECK (started_at IS NULL OR finished_at IS NULL OR finished_at >= started_at),
    CONSTRAINT chk_node_executions_failed_has_error
        CHECK (status <> 'FAILED' OR error_code IS NOT NULL),
    CONSTRAINT chk_node_executions_error_message_needs_code
        CHECK (error_message IS NULL OR error_code IS NOT NULL)
);

CREATE INDEX idx_node_executions_execution_node ON node_executions (execution_id, node_id);
