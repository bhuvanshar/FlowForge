package com.flowforge.application;

/** Thrown for a semantically invalid request that Bean Validation cannot express (e.g. unknown sort field). Mapped to HTTP 400. */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
