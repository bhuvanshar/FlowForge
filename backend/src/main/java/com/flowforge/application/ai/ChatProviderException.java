package com.flowforge.application.ai;

import java.time.Duration;

/** One failed provider attempt. Messages never contain credentials or prompts. */
public class ChatProviderException extends RuntimeException {

    /** What went wrong; decides the node error code and whether another attempt makes sense. */
    public enum Kind {
        AUTH(false, "AI_AUTH_FAILED"),
        BAD_REQUEST(false, "AI_BAD_REQUEST"),
        RATE_LIMITED(true, "AI_RATE_LIMITED"),
        SERVER_ERROR(true, "AI_PROVIDER_ERROR"),
        CONNECTION(true, "AI_CONNECTION_ERROR"),
        /** The time budget of the node is spent: nothing left to retry with. */
        TIMEOUT(false, "AI_TIMEOUT"),
        TOO_LARGE(false, "AI_RESPONSE_TOO_LARGE"),
        /** HTTP 200 but not a chat completion (garbled body, no choices). */
        BAD_RESPONSE(true, "AI_BAD_RESPONSE"),
        /** Outbound policy, redirect or similar: a configuration problem, not transient. */
        REFUSED_BY_POLICY(false, "AI_REQUEST_REFUSED");

        private final boolean retryable;
        private final String code;

        Kind(boolean retryable, String code) {
            this.retryable = retryable;
            this.code = code;
        }

        public boolean retryable() {
            return retryable;
        }

        public String code() {
            return code;
        }
    }

    private final Kind kind;
    private final Integer status;
    private final Duration retryAfter;
    private final String codeOverride;

    public ChatProviderException(Kind kind, String message) {
        this(kind, message, null, null, null);
    }

    public ChatProviderException(Kind kind, String message, Integer status, Duration retryAfter, String codeOverride) {
        super(message);
        this.kind = kind;
        this.status = status;
        this.retryAfter = retryAfter;
        this.codeOverride = codeOverride;
    }

    public Kind kind() {
        return kind;
    }

    /** HTTP status when there was a response. */
    public Integer status() {
        return status;
    }

    /** The provider's {@code Retry-After}, if it sent a usable one. */
    public Duration retryAfter() {
        return retryAfter;
    }

    /** Error code of the node failure: the policy's own code when there is one, else the kind's. */
    public String code() {
        return codeOverride != null ? codeOverride : kind.code();
    }
}
