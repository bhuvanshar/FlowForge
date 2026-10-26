-- Module 11: automatic triggers.

-- How an execution was started; scheduled runs also record the occurrence they belong to.
ALTER TABLE workflow_executions
    ADD COLUMN trigger_type  VARCHAR(20) NOT NULL DEFAULT 'MANUAL',
    ADD COLUMN scheduled_for TIMESTAMPTZ,
    ADD CONSTRAINT chk_workflow_executions_trigger_type CHECK (trigger_type IN ('MANUAL', 'WEBHOOK', 'SCHEDULE')),
    ADD CONSTRAINT chk_workflow_executions_scheduled_for
        CHECK ((trigger_type = 'SCHEDULE') = (scheduled_for IS NOT NULL));

-- Duplicate prevention: one execution per workflow and scheduled occurrence, whatever happens to the scheduler.
CREATE UNIQUE INDEX uq_workflow_executions_schedule_occurrence
    ON workflow_executions (workflow_id, scheduled_for) WHERE scheduled_for IS NOT NULL;

-- One webhook registration per workflow. Kept (inactive) when the workflow is deactivated or the trigger node changes,
-- so the public webhook id and secret stay stable across deactivate/activate.
CREATE TABLE webhook_triggers (
    workflow_id     UUID          PRIMARY KEY,
    webhook_id      VARCHAR(64)   NOT NULL,
    node_id         VARCHAR(64)   NOT NULL,
    http_method     VARCHAR(10)   NOT NULL,
    authentication  VARCHAR(20)   NOT NULL,
    input_mode      VARCHAR(20)   NOT NULL,
    secret          VARCHAR(128)  NOT NULL,
    active          BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    lock_version    BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT fk_webhook_triggers_workflow FOREIGN KEY (workflow_id) REFERENCES workflows (id) ON DELETE CASCADE,
    CONSTRAINT uq_webhook_triggers_webhook_id UNIQUE (webhook_id),
    CONSTRAINT chk_webhook_triggers_method CHECK (http_method IN ('GET', 'POST', 'PUT', 'PATCH', 'DELETE')),
    CONSTRAINT chk_webhook_triggers_authentication CHECK (authentication IN ('signature', 'token', 'none')),
    CONSTRAINT chk_webhook_triggers_input_mode CHECK (input_mode IN ('body', 'request')),
    CONSTRAINT chk_webhook_triggers_webhook_id CHECK (webhook_id ~ '^[A-Za-z0-9_-]{16,64}$')
);

-- One schedule per workflow. next_fire_at is the next occurrence to run; the scheduler advances it in the same
-- transaction that creates the execution.
CREATE TABLE schedule_triggers (
    workflow_id         UUID          PRIMARY KEY,
    node_id             VARCHAR(64)   NOT NULL,
    cron                VARCHAR(120)  NOT NULL,
    timezone            VARCHAR(64)   NOT NULL,
    missed_run_policy   VARCHAR(20)   NOT NULL,
    next_fire_at        TIMESTAMPTZ   NOT NULL,
    last_fire_at        TIMESTAMPTZ,
    last_execution_id   UUID,
    last_missed_at      TIMESTAMPTZ,
    last_error          VARCHAR(500),
    active              BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    lock_version        BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT fk_schedule_triggers_workflow FOREIGN KEY (workflow_id) REFERENCES workflows (id) ON DELETE CASCADE,
    CONSTRAINT chk_schedule_triggers_policy CHECK (missed_run_policy IN ('skip', 'runOnce'))
);

CREATE INDEX idx_schedule_triggers_due ON schedule_triggers (next_fire_at) WHERE active;
