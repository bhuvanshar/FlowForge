package com.flowforge.application.credential;

/** Credential storage needs an encryption key and none is configured (503). */
public class CredentialsUnavailableException extends RuntimeException {

    public CredentialsUnavailableException() {
        super("Credentials are disabled: no encryption key is configured on the server "
                + "(set FLOWFORGE_CREDENTIALS_KEYS or FLOWFORGE_CREDENTIALS_KEY_FILE).");
    }
}
