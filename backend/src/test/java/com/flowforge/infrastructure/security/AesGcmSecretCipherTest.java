package com.flowforge.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowforge.application.credential.CredentialCryptoException;
import com.flowforge.application.credential.SecretCipher;
import com.flowforge.config.CredentialProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AesGcmSecretCipherTest {

    private static final byte[] AAD = "flowforge:credential:v1|id-1|local|httpBearer".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SECRET = "{\"token\":\"tok_live_0123456789\"}".getBytes(StandardCharsets.UTF_8);

    private static String key() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private static AesGcmSecretCipher cipher(String keys, String active) {
        return new AesGcmSecretCipher(new CredentialProperties(keys, null, active));
    }

    private final String k1 = key();
    private final String k2 = key();

    @Test
    void roundTripsAndUsesAFreshNonceEveryTime() {
        AesGcmSecretCipher cipher = cipher("k1:" + k1, null);
        SecretCipher.Sealed a = cipher.seal(SECRET, AAD);
        SecretCipher.Sealed b = cipher.seal(SECRET, AAD);

        assertThat(cipher.open(a, AAD)).isEqualTo(SECRET);
        assertThat(a.keyId()).isEqualTo("k1");
        assertThat(a.nonce()).hasSize(12).isNotEqualTo(b.nonce());
        assertThat(a.ciphertext()).isNotEqualTo(b.ciphertext()).hasSize(SECRET.length + 16);
        assertThat(new String(a.ciphertext(), StandardCharsets.ISO_8859_1)).doesNotContain("tok_live");
    }

    @Test
    void anyChangeToCiphertextNonceOrAssociatedDataIsDetected() {
        AesGcmSecretCipher cipher = cipher("k1:" + k1, null);
        SecretCipher.Sealed sealed = cipher.seal(SECRET, AAD);

        byte[] flipped = sealed.ciphertext();
        flipped[3] ^= 1;
        assertThatThrownBy(() -> cipher.open(new SecretCipher.Sealed("k1", sealed.nonce(), flipped), AAD))
                .isInstanceOf(CredentialCryptoException.class).hasMessageContaining("failed authentication");
        byte[] nonce = sealed.nonce();
        nonce[0] ^= 1;
        assertThatThrownBy(() -> cipher.open(new SecretCipher.Sealed("k1", nonce, sealed.ciphertext()), AAD))
                .isInstanceOf(CredentialCryptoException.class);
        // Ciphertext moved to another credential (different id in the associated data).
        byte[] otherRow = "flowforge:credential:v1|id-2|local|httpBearer".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> cipher.open(sealed, otherRow)).isInstanceOf(CredentialCryptoException.class);
    }

    @Test
    void keyRotationDecryptsWithOldKeysAndEncryptsWithTheActiveOne() {
        SecretCipher.Sealed old = cipher("k1:" + k1, null).seal(SECRET, AAD);

        AesGcmSecretCipher rotated = cipher("k1:" + k1 + ",k2:" + k2, null); // default active: last listed
        assertThat(rotated.activeKeyId()).isEqualTo("k2");
        assertThat(rotated.open(old, AAD)).isEqualTo(SECRET);
        SecretCipher.Sealed fresh = rotated.seal(rotated.open(old, AAD), AAD);
        assertThat(fresh.keyId()).isEqualTo("k2");

        AesGcmSecretCipher withoutOldKey = cipher("k2:" + k2, null);
        assertThat(withoutOldKey.open(fresh, AAD)).isEqualTo(SECRET);
        assertThatThrownBy(() -> withoutOldKey.open(old, AAD)).isInstanceOf(CredentialCryptoException.class)
                .hasMessageContaining("'k1'").hasMessageNotContaining(k1);
        assertThat(cipher("k1:" + k1 + ",k2:" + k2, "k1").activeKeyId()).isEqualTo("k1");
    }

    @Test
    void aWrongKeyCannotDecrypt() {
        SecretCipher.Sealed sealed = cipher("k1:" + k1, null).seal(SECRET, AAD);
        assertThatThrownBy(() -> cipher("k1:" + k2, null).open(sealed, AAD)).isInstanceOf(CredentialCryptoException.class);
    }

    @Test
    void withoutKeysCredentialsAreUnavailable() {
        AesGcmSecretCipher none = cipher("", null);
        assertThat(none.available()).isFalse();
        assertThatThrownBy(() -> none.seal(SECRET, AAD)).isInstanceOf(CredentialCryptoException.class)
                .hasMessageContaining("not configured");
    }

    @Test
    void malformedKeyringsStopStartupWithoutPrintingKeys() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> cipher("k1:" + shortKey, null)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be 32 bytes").hasMessageNotContaining(shortKey);
        assertThatThrownBy(() -> cipher("k1:not base64!", null)).hasMessageContaining("not valid base64");
        assertThatThrownBy(() -> cipher(k1, null)).hasMessageContaining("keyId:base64Key");
        assertThatThrownBy(() -> cipher("bad id:" + k1, null)).hasMessageContaining("keyId:base64Key");
        assertThatThrownBy(() -> cipher("k1:" + k1 + ",k1:" + k2, null)).hasMessageContaining("defined twice");
        assertThatThrownBy(() -> cipher("k1:" + k1, "k9")).hasMessageContaining("not in the keyring");
    }

    @Test
    void readsKeysFromAKeyFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("keys");
        Files.writeString(file, "# FlowForge credential keys\nk1:" + k1 + "\n\nk2:" + k2 + "\n");
        AesGcmSecretCipher cipher = new AesGcmSecretCipher(new CredentialProperties(null, file.toString(), "k1"));
        assertThat(cipher.keyIds()).containsExactly("k1", "k2");
        assertThat(cipher.activeKeyId()).isEqualTo("k1");
        assertThatThrownBy(() -> new AesGcmSecretCipher(new CredentialProperties(null, dir.resolve("missing").toString(),
                null))).hasMessageContaining("cannot be read");
    }
}
