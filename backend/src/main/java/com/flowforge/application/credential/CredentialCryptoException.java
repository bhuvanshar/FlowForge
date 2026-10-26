package com.flowforge.application.credential;

/** Encryption is not configured, a key is missing, or a ciphertext failed authentication. Never carries key material. */
public class CredentialCryptoException extends RuntimeException {

    public CredentialCryptoException(String message) {
        super(message);
    }
}
