-- Webhook secrets encrypted with the credential keyring (AES-256-GCM, like credentials).
--
-- Migration path: existing rows keep their plaintext in "secret" until the application starts with a keyring; it then
-- encrypts them (WebhookSecretStore, at startup and on POST /api/v1/credentials/re-encrypt) and clears the plaintext.
-- Servers without a keyring keep storing new secrets in plaintext (legacy mode, logged as a warning).
ALTER TABLE webhook_triggers
    ALTER COLUMN secret DROP NOT NULL,
    ADD COLUMN secret_key_id     VARCHAR(64),
    ADD COLUMN secret_nonce      BYTEA,
    ADD COLUMN secret_ciphertext BYTEA,
    -- COALESCE: a CHECK that evaluates to NULL would pass, so every branch must be definitely true.
    ADD CONSTRAINT chk_webhook_triggers_secret_storage CHECK (COALESCE(
        (secret IS NOT NULL AND secret_key_id IS NULL AND secret_nonce IS NULL AND secret_ciphertext IS NULL)
        OR (secret IS NULL AND secret_key_id IS NOT NULL AND secret_nonce IS NOT NULL AND secret_ciphertext IS NOT NULL
            AND octet_length(secret_nonce) = 12 AND octet_length(secret_ciphertext) BETWEEN 17 AND 1024),
        false));
