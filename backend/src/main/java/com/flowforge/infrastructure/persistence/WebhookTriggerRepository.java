package com.flowforge.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WebhookTriggerRepository extends JpaRepository<WebhookTriggerEntity, UUID> {

    Optional<WebhookTriggerEntity> findByWebhookId(String webhookId);

    /** Rows still holding a legacy plaintext secret. */
    List<WebhookTriggerEntity> findBySecretIsNotNull();

    /** Encrypted rows whose key is not {@code keyId} (to re-encrypt after key rotation). */
    List<WebhookTriggerEntity> findBySecretKeyIdIsNotNullAndSecretKeyIdNot(String keyId);
}
