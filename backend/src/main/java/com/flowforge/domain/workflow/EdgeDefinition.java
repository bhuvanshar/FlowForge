package com.flowforge.domain.workflow;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * A directed connection from an output handle of one node to an input handle of another.
 * Branching nodes expose several output handles (e.g. {@code true} / {@code false}); most nodes use
 * {@link #DEFAULT_HANDLE}.
 */
public record EdgeDefinition(
        @NotBlank @Pattern(regexp = WorkflowDefinition.ID_PATTERN) String id,
        @NotBlank @Pattern(regexp = WorkflowDefinition.ID_PATTERN) String source,
        @NotBlank @Pattern(regexp = WorkflowDefinition.HANDLE_PATTERN) String sourceHandle,
        @NotBlank @Pattern(regexp = WorkflowDefinition.ID_PATTERN) String target,
        @NotBlank @Pattern(regexp = WorkflowDefinition.HANDLE_PATTERN) String targetHandle) {

    public static final String DEFAULT_HANDLE = "main";
}
