package com.flowforge.application;

/** Thrown when a payload exceeds an application limit. Mapped to HTTP 413. */
public class PayloadTooLargeException extends RuntimeException {

    public PayloadTooLargeException(String message) {
        super(message);
    }
}
