package com.flowforge.application;

import java.util.List;

/** Request fields failed validation in a service (400 {@code validation-failed} with an {@code errors} list). */
public class FieldValidationException extends RuntimeException {

    public record Violation(String field, String message) {
    }

    private final List<Violation> violations;

    public FieldValidationException(List<Violation> violations) {
        super("Request validation failed.");
        this.violations = List.copyOf(violations);
    }

    public List<Violation> violations() {
        return violations;
    }
}
