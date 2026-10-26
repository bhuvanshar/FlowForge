package com.flowforge.application.credential;

/** A credential cannot be used by a run. {@link #code()} becomes the node's error code. */
public class CredentialAccessException extends RuntimeException {

    public static final String NOT_FOUND = "CREDENTIAL_NOT_FOUND";
    public static final String NOT_PERMITTED = "CREDENTIAL_NOT_PERMITTED";
    public static final String UNAVAILABLE = "CREDENTIAL_UNAVAILABLE";

    private final String code;

    public CredentialAccessException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
