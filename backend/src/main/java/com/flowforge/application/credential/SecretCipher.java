package com.flowforge.application.credential;

import java.util.Set;

/**
 * Authenticated encryption of credential secrets. Keys live outside the database and the repository (environment or
 * key file); only the key id is stored next to the ciphertext.
 */
public interface SecretCipher {

    /** Ciphertext includes the authentication tag. */
    record Sealed(String keyId, byte[] nonce, byte[] ciphertext) {
    }

    /** False when no key is configured: credentials cannot be created or used. */
    boolean available();

    /** Key used for new encryptions. */
    String activeKeyId();

    Set<String> keyIds();

    /** Encrypts with the active key and a fresh random nonce; {@code aad} is authenticated but not stored. */
    Sealed seal(byte[] plaintext, byte[] aad);

    /**
     * Decrypts and verifies. Fails with {@link CredentialCryptoException} when the key is unknown, or the ciphertext,
     * nonce or associated data were changed.
     */
    byte[] open(Sealed sealed, byte[] aad);
}
