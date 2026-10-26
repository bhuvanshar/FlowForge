package com.flowforge.application;

import com.flowforge.domain.validation.ValidationResult;

/** The workflow graph is invalid for the requested operation (e.g. activation). Mapped to HTTP 422. */
public class WorkflowValidationException extends RuntimeException {

    private final transient ValidationResult result;

    public WorkflowValidationException(String message, ValidationResult result) {
        super(message);
        this.result = result;
    }

    public ValidationResult result() {
        return result;
    }
}
