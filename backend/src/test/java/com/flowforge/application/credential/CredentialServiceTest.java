package com.flowforge.application.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.application.FieldValidationException;
import com.flowforge.application.ResourceNotFoundException;
import com.flowforge.application.security.OwnerContext;
import com.flowforge.config.CredentialProperties;
import com.flowforge.infrastructure.persistence.CredentialEntity;
import com.flowforge.infrastructure.persistence.CredentialRepository;
import com.flowforge.infrastructure.persistence.WorkflowRepository;
import com.flowforge.infrastructure.security.AesGcmSecretCipher;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CredentialServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final CredentialTypeRegistry TYPES = new CredentialTypeRegistry(JSON);

    @Mock
    CredentialRepository repository;

    @Mock
    WorkflowRepository workflows;

    @Mock
    com.flowforge.infrastructure.persistence.WebhookTriggerRepository webhookTriggers;

    private final List<CredentialEntity> saved = new ArrayList<>();
    private final String k1 = key();
    private final String k2 = key();

    private static String key() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private CredentialService service(String keys, String owner) {
        AesGcmSecretCipher cipher = new AesGcmSecretCipher(new CredentialProperties(keys, null, null));
        return new CredentialService(repository, workflows, TYPES, cipher, () -> owner, JSON, Clock.systemUTC(),
                new com.flowforge.application.trigger.WebhookSecretStore(cipher, webhookTriggers,
                        org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class)));
    }

    @BeforeEach
    void repository() {
        lenient().when(repository.saveAndFlush(any())).thenAnswer(i -> {
            CredentialEntity e = i.getArgument(0);
            ReflectionTestUtils.setField(e, "lockVersion", 0L);
            saved.add(e);
            return e;
        });
        lenient().when(repository.findByIdAndOwnerId(any(), anyString())).thenAnswer(i -> saved.stream()
                .filter(e -> e.getId().equals(i.getArgument(0)) && e.getOwnerId().equals(i.getArgument(1))).findFirst());
    }

    private CredentialEntity createBearer(CredentialService service, String token) {
        return service.create("httpBearer", new CredentialService.Details("API", null, List.of("API.example.com"),
                null), Map.of("token", token));
    }

    @Test
    void storesOnlyCiphertextAndResolvesForTheOwner() {
        CredentialService service = service("k1:" + k1, OwnerContext.LOCAL_OWNER);
        CredentialEntity entity = createBearer(service, "tok_live_abcdef123456");

        assertThat(entity.getKeyId()).isEqualTo("k1");
        assertThat(new String(entity.getSecretCiphertext(), StandardCharsets.ISO_8859_1)).doesNotContain("tok_live");
        assertThat(entity.getPublicData()).isEmpty();
        assertThat(entity.getAllowedHosts()).containsExactly("api.example.com");

        ResolvedCredential resolved = service.resolve(entity.getId(), "local", "core.httpRequest");
        assertThat(resolved.secrets()).containsEntry("token", "tok_live_abcdef123456");
        assertThat(resolved.toString()).doesNotContain("tok_live");
        assertThat(resolved.permitsHost("API.example.com")).isTrue();
        assertThat(resolved.permitsHost("evil.example.com")).isFalse();
    }

    @Test
    void credentialsOfOtherOwnersAreInvisibleAndUnusable() {
        CredentialEntity entity = createBearer(service("k1:" + k1, "alice"), "tok_live_abcdef123456");
        CredentialService bob = service("k1:" + k1, "bob");

        assertThatThrownBy(() -> bob.get(entity.getId())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> bob.resolve(entity.getId(), "bob", "core.httpRequest"))
                .isInstanceOfSatisfying(CredentialAccessException.class,
                        e -> assertThat(e.code()).isEqualTo(CredentialAccessException.NOT_FOUND));
        assertThat(bob.problem(entity.getId().toString(), "core.httpRequest")).contains(
                "refers to a credential that does not exist");
        // Its owner may use it, but only from node types the credential type allows.
        assertThatThrownBy(() -> service("k1:" + k1, "alice").resolve(entity.getId(), "alice", "core.logger"))
                .isInstanceOfSatisfying(CredentialAccessException.class,
                        e -> assertThat(e.code()).isEqualTo(CredentialAccessException.NOT_PERMITTED));
    }

    @Test
    void rotationReplacesTheSecretAndKeyRotationReEncrypts() {
        CredentialService service = service("k1:" + k1, "local");
        CredentialEntity entity = createBearer(service, "tok_live_first_0001");
        byte[] firstCiphertext = entity.getSecretCiphertext();

        service.rotate(entity.getId(), Map.of("token", "tok_live_second_0002"), null);
        assertThat(entity.getSecretVersion()).isEqualTo(2);
        assertThat(entity.getRotatedAt()).isNotNull();
        assertThat(entity.getSecretCiphertext()).isNotEqualTo(firstCiphertext);
        assertThat(service.resolve(entity.getId(), "local", "core.httpRequest").secrets().get("token"))
                .isEqualTo("tok_live_second_0002");

        CredentialService withNewKey = service("k1:" + k1 + ",k2:" + k2, "local");
        when(repository.findByKeyIdNot(eq("k2"))).thenReturn(List.of(entity));
        assertThat(withNewKey.reEncrypt().credentials()).isEqualTo(1);
        assertThat(entity.getKeyId()).isEqualTo("k2");
        // The old key can now be removed from the keyring.
        assertThat(service("k2:" + k2, "local").resolve(entity.getId(), "local", "core.httpRequest").secrets())
                .containsEntry("token", "tok_live_second_0002");
    }

    @Test
    void tamperedOrMisplacedCiphertextIsReportedWithoutDetails() {
        CredentialService service = service("k1:" + k1, "local");
        CredentialEntity a = createBearer(service, "tok_live_aaaaaaaaaa");
        CredentialEntity b = service.create("httpBearer", new CredentialService.Details("B", null,
                List.of("api.example.com"), Map.of()), Map.of("token", "tok_live_bbbbbbbbbb"));
        // Copy a's ciphertext into b: the associated data (credential id) no longer matches.
        b.seal(a.getKeyId(), a.getSecretNonce(), a.getSecretCiphertext());

        assertThatThrownBy(() -> service.resolve(b.getId(), "local", "core.httpRequest"))
                .isInstanceOfSatisfying(CredentialAccessException.class, e -> {
                    assertThat(e.code()).isEqualTo(CredentialAccessException.UNAVAILABLE);
                    assertThat(e.getMessage()).doesNotContain("tok_live");
                });
    }

    @Test
    void validatesTypeFieldsSecretsAndHosts() {
        CredentialService service = service("k1:" + k1, "local");
        assertThatThrownBy(() -> service.create("httpHeader", new CredentialService.Details(" ", null,
                List.of("https://api.example.com/x", "*.ok.example.com"), Map.of("headerName", "Host", "extra", "1")),
                Map.of("value", "short", "other", "x")))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.violations())
                        .extracting(FieldValidationException.Violation::field)
                        .containsExactlyInAnyOrder("name", "allowedHosts[0]", "fields.headerName", "fields.extra",
                                "secret.value", "secret.other"));
        assertThatThrownBy(() -> service.create("ssh", new CredentialService.Details("x", null, List.of("a.b"),
                null), Map.of())).isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> service.create("httpBasic", new CredentialService.Details("x", null, List.of(),
                Map.of("username", "a:b")), Map.of("password", "line\nbreak1")))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.violations())
                        .extracting(FieldValidationException.Violation::field)
                        .containsExactlyInAnyOrder("allowedHosts", "fields.username", "secret.password"));
    }

    @Test
    void withoutAKeyNothingCanBeStored() {
        CredentialService service = service("", "local");
        assertThatThrownBy(() -> createBearer(service, "tok_live_abcdef123456"))
                .isInstanceOf(CredentialsUnavailableException.class);
        assertThat(service.encryptionStatus().available()).isFalse();
        assertThat(saved).isEmpty();
        assertThat(Optional.empty()).isEmpty();
    }
}
