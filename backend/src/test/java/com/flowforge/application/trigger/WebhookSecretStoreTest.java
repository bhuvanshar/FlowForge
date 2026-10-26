package com.flowforge.application.trigger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.flowforge.application.credential.CredentialCryptoException;
import com.flowforge.config.CredentialProperties;
import com.flowforge.infrastructure.persistence.WebhookTriggerEntity;
import com.flowforge.infrastructure.persistence.WebhookTriggerRepository;
import com.flowforge.infrastructure.security.AesGcmSecretCipher;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

class WebhookSecretStoreTest {

    private static final String SECRET = "whsec_0123456789abcdefghijklmnopqrstuvwxyzABCD";

    private final WebhookTriggerRepository repository = mock(WebhookTriggerRepository.class);
    private final String k1 = key();
    private final String k2 = key();

    private static String key() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private WebhookSecretStore store(String keys) {
        return new WebhookSecretStore(new AesGcmSecretCipher(new CredentialProperties(keys, null, null)), repository,
                mock(PlatformTransactionManager.class));
    }

    private static WebhookTriggerEntity webhook() {
        return new WebhookTriggerEntity(UUID.randomUUID(), "hook_" + UUID.randomUUID().toString().replace("-", ""));
    }

    @Test
    void secretsAreEncryptedWithTheKeyringAndRevealedOnDemand() {
        WebhookTriggerEntity webhook = webhook();
        store("k1:" + k1).store(webhook, SECRET);

        assertThat(webhook.isSecretEncrypted()).isTrue();
        assertThat(webhook.getPlaintextSecret()).isNull();
        assertThat(webhook.getSecretKeyId()).isEqualTo("k1");
        assertThat(webhook.getSecretNonce()).hasSize(12);
        assertThat(new String(webhook.getSecretCiphertext(), StandardCharsets.ISO_8859_1)).doesNotContain("whsec_");
        assertThat(store("k1:" + k1).reveal(webhook)).isEqualTo(SECRET);
    }

    @Test
    void withoutAKeyringSecretsStayPlaintextSoWebhooksKeepWorking() {
        WebhookSecretStore store = store("");
        WebhookTriggerEntity webhook = webhook();
        store.store(webhook, SECRET);

        assertThat(webhook.isSecretEncrypted()).isFalse();
        assertThat(store.reveal(webhook)).isEqualTo(SECRET);
        when(repository.findBySecretIsNotNull()).thenReturn(List.of(webhook));
        assertThat(store.pendingCount()).isEqualTo(1);
        assertThat(store.reEncrypt()).isZero();
        store.encryptLegacyAtStartup(); // only warns
        assertThat(webhook.isSecretEncrypted()).isFalse();
    }

    @Test
    void legacyPlaintextIsEncryptedAtStartupOnceAKeyringExists() {
        WebhookTriggerEntity legacy = webhook();
        legacy.storePlaintextSecret(SECRET);
        when(repository.findBySecretIsNotNull()).thenReturn(List.of(legacy));
        WebhookSecretStore store = store("k1:" + k1);
        assertThat(store.pendingCount()).isEqualTo(1);

        store.encryptLegacyAtStartup();

        assertThat(legacy.isSecretEncrypted()).isTrue();
        assertThat(legacy.getPlaintextSecret()).isNull();
        assertThat(store.reveal(legacy)).isEqualTo(SECRET);
    }

    @Test
    void keyRotationReEncryptsSoTheOldKeyCanBeRemoved() {
        WebhookTriggerEntity webhook = webhook();
        store("k1:" + k1).store(webhook, SECRET);
        when(repository.findBySecretKeyIdIsNotNullAndSecretKeyIdNot("k2")).thenReturn(List.of(webhook));

        assertThat(store("k1:" + k1 + ",k2:" + k2).reEncrypt()).isEqualTo(1);

        assertThat(webhook.getSecretKeyId()).isEqualTo("k2");
        assertThat(store("k2:" + k2).reveal(webhook)).isEqualTo(SECRET);
        assertThatThrownBy(() -> store("k1:" + k1).reveal(webhook)).isInstanceOf(CredentialCryptoException.class)
                .hasMessageContaining("'k2'");
    }

    @Test
    void ciphertextIsBoundToItsWebhook() {
        WebhookSecretStore store = store("k1:" + k1);
        WebhookTriggerEntity a = webhook();
        WebhookTriggerEntity b = webhook();
        store.store(a, SECRET);
        b.sealSecret(a.getSecretKeyId(), a.getSecretNonce(), a.getSecretCiphertext());

        assertThatThrownBy(() -> store.reveal(b)).isInstanceOf(CredentialCryptoException.class)
                .hasMessageNotContaining("whsec_");
    }
}
