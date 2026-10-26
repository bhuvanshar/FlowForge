package com.flowforge.application.trigger;

import com.flowforge.application.credential.CredentialCryptoException;
import com.flowforge.application.credential.SecretCipher;
import com.flowforge.infrastructure.persistence.WebhookTriggerEntity;
import com.flowforge.infrastructure.persistence.WebhookTriggerRepository;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Webhook secrets at rest, encrypted with the credential keyring ({@link SecretCipher}, AES-256-GCM). The associated
 * data binds each ciphertext to its workflow and webhook id, so a ciphertext copied to another row does not decrypt.
 *
 * <p><b>Legacy plaintext.</b> Rows created before Module 12, or while the server had no keyring, hold the secret in
 * plaintext. When a keyring is available they are encrypted at startup ({@link #encryptLegacyAtStartup}) and by
 * {@link #reEncrypt} (key rotation), which also clears the plaintext column. Without a keyring new secrets are stored
 * in plaintext and a warning is logged, so webhooks keep working.
 */
@Component
public class WebhookSecretStore {

    private static final Logger log = LoggerFactory.getLogger(WebhookSecretStore.class);

    private final SecretCipher cipher;
    private final WebhookTriggerRepository webhooks;
    private final TransactionTemplate transactions;

    public WebhookSecretStore(SecretCipher cipher, WebhookTriggerRepository webhooks,
                              PlatformTransactionManager transactionManager) {
        this.cipher = cipher;
        this.webhooks = webhooks;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** Sets a new secret on the entity: encrypted with the active key, or plaintext without a keyring. */
    public void store(WebhookTriggerEntity webhook, String secret) {
        if (!cipher.available()) {
            log.warn("No credential encryption key configured: webhook secret of workflow {} is stored in plaintext",
                    webhook.getWorkflowId());
            webhook.storePlaintextSecret(secret);
            return;
        }
        byte[] plaintext = secret.getBytes(StandardCharsets.UTF_8);
        try {
            SecretCipher.Sealed sealed = cipher.seal(plaintext, aad(webhook));
            webhook.sealSecret(sealed.keyId(), sealed.nonce(), sealed.ciphertext());
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    /**
     * The secret in plaintext, for verifying deliveries and for the reveal endpoint.
     *
     * @throws CredentialCryptoException the key is not configured or the ciphertext was altered
     */
    public String reveal(WebhookTriggerEntity webhook) {
        if (!webhook.isSecretEncrypted()) {
            return webhook.getPlaintextSecret();
        }
        byte[] plaintext = cipher.open(new SecretCipher.Sealed(webhook.getSecretKeyId(), webhook.getSecretNonce(),
                webhook.getSecretCiphertext()), aad(webhook));
        try {
            return new String(plaintext, StandardCharsets.UTF_8);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    /** Rows that {@link #reEncrypt} would change: legacy plaintext plus ciphertext under an older key. */
    public long pendingCount() {
        if (!cipher.available()) {
            return webhooks.findBySecretIsNotNull().size();
        }
        return webhooks.findBySecretIsNotNull().size()
                + webhooks.findBySecretKeyIdIsNotNullAndSecretKeyIdNot(cipher.activeKeyId()).size();
    }

    /**
     * Encrypts legacy plaintext secrets and re-encrypts secrets on older keys with the active key. Call inside a
     * transaction (the entities are managed). Returns the number of rows changed; 0 without a keyring.
     */
    public int reEncrypt() {
        if (!cipher.available()) {
            return 0;
        }
        List<WebhookTriggerEntity> rows = new ArrayList<>(webhooks.findBySecretIsNotNull());
        rows.addAll(webhooks.findBySecretKeyIdIsNotNullAndSecretKeyIdNot(cipher.activeKeyId()));
        for (WebhookTriggerEntity webhook : rows) {
            store(webhook, reveal(webhook));
        }
        webhooks.flush();
        return rows.size();
    }

    /** Migration path: once a keyring is configured, no plaintext webhook secret survives a restart. */
    @EventListener(ApplicationReadyEvent.class)
    @Order(10)
    public void encryptLegacyAtStartup() {
        if (!cipher.available()) {
            long plaintext = webhooks.findBySecretIsNotNull().size();
            if (plaintext > 0) {
                log.warn("{} webhook secret(s) are stored in plaintext: configure FLOWFORGE_CREDENTIALS_KEYS to encrypt "
                        + "them", plaintext);
            }
            return;
        }
        try {
            Integer changed = transactions.execute(status -> reEncrypt());
            if (changed != null && changed > 0) {
                log.info("Encrypted {} webhook secret(s) with key '{}'", changed, cipher.activeKeyId());
            }
        } catch (CredentialCryptoException e) {
            // A secret under a key that is no longer configured: leave everything as it was and say so.
            log.error("Webhook secrets were not re-encrypted: {}", e.getMessage());
        }
    }

    private static byte[] aad(WebhookTriggerEntity webhook) {
        return ("flowforge:webhook-secret:v1|" + webhook.getWorkflowId() + "|" + webhook.getWebhookId())
                .getBytes(StandardCharsets.UTF_8);
    }
}
