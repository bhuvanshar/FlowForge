package com.flowforge.domain.execution;

/**
 * The graph cannot be turned into a sequential execution plan. Validation (Module 6) rejects these graphs first; the
 * planner re-checks so an unsupported pattern can never be executed incorrectly.
 */
public class PlanningException extends RuntimeException {

    public PlanningException(String message) {
        super(message);
    }
}
