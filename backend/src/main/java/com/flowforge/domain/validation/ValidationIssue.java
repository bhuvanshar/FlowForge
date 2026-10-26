package com.flowforge.domain.validation;

import java.util.Objects;

/**
 * One finding. {@code nodeId} and {@code edgeId} identify the affected element when there is one; {@code path} points
 * into the workflow definition JSON, e.g. {@code nodes[2].configuration.url} or {@code edges[0].sourceHandle}.
 */
public record ValidationIssue(ValidationCode code, Severity severity, String message, String nodeId, String edgeId,
                              String path) {

    public ValidationIssue {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(message, "message");
    }

    public static ValidationIssue of(ValidationCode code, String message, String nodeId, String edgeId, String path) {
        return new ValidationIssue(code, code.severity(), message, nodeId, edgeId, path);
    }
}
