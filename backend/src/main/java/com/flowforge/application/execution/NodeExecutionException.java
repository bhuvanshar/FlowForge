package com.flowforge.application.execution;

/**
 * Thrown by executors for expected failures with a stable code (e.g. {@code HTTP_STATUS_ERROR}). The message must be
 * safe to show to users; it is sanitized again before persisting. Any other exception becomes {@code NODE_ERROR}.
 */
public class NodeExecutionException extends RuntimeException {

    private final String code;

    public NodeExecutionException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
