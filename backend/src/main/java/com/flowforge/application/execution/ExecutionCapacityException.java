package com.flowforge.application.execution;

/** The execution queue is full. Mapped to HTTP 503; the client may retry later. */
public class ExecutionCapacityException extends RuntimeException {

    public ExecutionCapacityException(String message) {
        super(message);
    }
}
