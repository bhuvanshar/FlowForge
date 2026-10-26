package com.flowforge.api.dto;

import com.flowforge.domain.validation.Severity;
import com.flowforge.domain.validation.ValidationCode;
import com.flowforge.domain.validation.ValidationIssue;
import com.flowforge.domain.validation.ValidationResult;
import java.util.List;

/**
 * @param executionOrder node ids reachable from the trigger in topological order; empty when there is no trigger or
 *                       the graph has a cycle
 */
public record ValidationResultResponse(boolean valid, long errorCount, long warningCount, List<Issue> issues,
                                       List<String> executionOrder) {

    /** {@code nodeId}, {@code edgeId} and {@code path} are omitted when not applicable. */
    public record Issue(ValidationCode code, Severity severity, String message, String nodeId, String edgeId,
                        String path) {

        static Issue from(ValidationIssue i) {
            return new Issue(i.code(), i.severity(), i.message(), i.nodeId(), i.edgeId(), i.path());
        }
    }

    public static ValidationResultResponse from(ValidationResult result) {
        return new ValidationResultResponse(result.valid(), result.errorCount(), result.warningCount(),
                result.issues().stream().map(Issue::from).toList(), result.executionOrder());
    }
}
