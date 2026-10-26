-- Module 8: log lines written during an execution (e.g. by the Logger node). Messages are sanitized before storage.
CREATE TABLE execution_logs (
    id            BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    execution_id  UUID          NOT NULL,
    node_id       VARCHAR(64),
    level         VARCHAR(10)   NOT NULL,
    message       VARCHAR(4000) NOT NULL,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT fk_execution_logs_execution
        FOREIGN KEY (execution_id) REFERENCES workflow_executions (id) ON DELETE CASCADE,
    CONSTRAINT chk_execution_logs_level CHECK (level IN ('DEBUG', 'INFO', 'WARN', 'ERROR'))
);

CREATE INDEX idx_execution_logs_execution ON execution_logs (execution_id, id);
