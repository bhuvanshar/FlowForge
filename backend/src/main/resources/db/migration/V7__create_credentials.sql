-- Module 12: credentials and ownership.

-- Owner of a workflow. Single-user MVP: always 'local'. The column exists so that adding users/workspaces later is a
-- backfill-free change; queries do not filter workflows by owner yet (see docs/ARCHITECTURE.md, "Ownership").
ALTER TABLE workflows ADD COLUMN owner_id VARCHAR(64) NOT NULL DEFAULT 'local';

-- Credentials for external integrations. Non-secret fields (e.g. a user name or header name) are stored in
-- public_data; secret fields are only stored encrypted (AES-256-GCM) in secret_ciphertext. The key never lives in the
-- database: key_id names the key in the server's keyring (environment or key file).
CREATE TABLE credentials (
    id                 UUID          PRIMARY KEY,
    owner_id           VARCHAR(64)   NOT NULL DEFAULT 'local',
    name               VARCHAR(100)  NOT NULL,
    type               VARCHAR(40)   NOT NULL,
    description        VARCHAR(500),
    public_data        JSONB         NOT NULL DEFAULT '{}'::jsonb,
    allowed_hosts      JSONB         NOT NULL,
    key_id             VARCHAR(64)   NOT NULL,
    secret_nonce       BYTEA         NOT NULL,
    secret_ciphertext  BYTEA         NOT NULL,
    secret_version     INTEGER       NOT NULL DEFAULT 1,
    rotated_at         TIMESTAMPTZ,
    last_used_at       TIMESTAMPTZ,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    lock_version       BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT chk_credentials_type CHECK (type IN ('httpBearer', 'httpBasic', 'httpHeader')),
    CONSTRAINT chk_credentials_name CHECK (length(btrim(name)) > 0),
    CONSTRAINT chk_credentials_public_data CHECK (jsonb_typeof(public_data) = 'object'),
    CONSTRAINT chk_credentials_allowed_hosts CHECK (jsonb_typeof(allowed_hosts) = 'array'
        AND jsonb_array_length(allowed_hosts) BETWEEN 1 AND 20),
    CONSTRAINT chk_credentials_nonce CHECK (octet_length(secret_nonce) = 12),
    CONSTRAINT chk_credentials_ciphertext CHECK (octet_length(secret_ciphertext) BETWEEN 17 AND 16384),
    CONSTRAINT chk_credentials_secret_version CHECK (secret_version >= 1)
);

CREATE UNIQUE INDEX uq_credentials_owner_name ON credentials (owner_id, lower(name));
