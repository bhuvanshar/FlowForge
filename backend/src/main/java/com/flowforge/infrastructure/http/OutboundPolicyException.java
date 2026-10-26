package com.flowforge.infrastructure.http;

/** A request (or redirect) was refused by the outbound policy. */
public class OutboundPolicyException extends RuntimeException {

    private final String code;

    public OutboundPolicyException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
