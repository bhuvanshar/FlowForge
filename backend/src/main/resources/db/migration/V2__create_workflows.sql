-- Module 2: workflow definitions.
-- `version`      = definition version, incremented by the application whenever the definition changes.
-- `lock_version` = JPA optimistic-locking counter, incremented on every update.
CREATE TABLE workflows (
    id            UUID          PRIMARY KEY,
    name          VARCHAR(200)  NOT NULL,
    description   VARCHAR(2000),
    status        VARCHAR(20)   NOT NULL DEFAULT 'DRAFT',
    version       INTEGER       NOT NULL DEFAULT 1,
    definition    JSONB         NOT NULL,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    lock_version  BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT chk_workflows_name_not_blank CHECK (length(btrim(name)) > 0),
    CONSTRAINT chk_workflows_status CHECK (status IN ('DRAFT', 'ACTIVE', 'INACTIVE', 'ARCHIVED')),
    CONSTRAINT chk_workflows_version_positive CHECK (version >= 1),
    CONSTRAINT chk_workflows_definition_shape CHECK (COALESCE(
        -- COALESCE: a missing key yields NULL, and a NULL check result would otherwise pass.
        jsonb_typeof(definition) = 'object'
        AND jsonb_typeof(definition -> 'schemaVersion') = 'number'
        AND jsonb_typeof(definition -> 'nodes') = 'array'
        AND jsonb_typeof(definition -> 'edges') = 'array',
        false)),
    -- Hard cap (1 MiB of JSON text); the application should reject large definitions earlier with a clear error.
    CONSTRAINT chk_workflows_definition_size CHECK (octet_length(definition::text) <= 1048576),
    CONSTRAINT chk_workflows_timestamps CHECK (updated_at >= created_at)
);

CREATE INDEX idx_workflows_status_updated_at ON workflows (status, updated_at DESC);
CREATE INDEX idx_workflows_updated_at ON workflows (updated_at DESC);
