package com.flowforge.application;

/** Thrown when the request conflicts with current state (stale version, archived workflow...). Mapped to HTTP 409. */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
