package com.flowforge.domain.validation;

import java.util.List;

/**
 * Outcome of validating a workflow definition.
 *
 * @param issues         errors and warnings in a deterministic order: by validation pass (size, nodes, edges,
 *                       connection counts, triggers, branches, cycles, connectivity, configuration), then by position
 *                       in the definition
 * @param executionOrder topological order of the nodes reachable from the trigger, or an empty list when the graph has
 *                       no trigger or contains a cycle. Ties are broken by position in the definition.
 */
public record ValidationResult(List<ValidationIssue> issues, List<String> executionOrder) {

    public ValidationResult {
        issues = List.copyOf(issues);
        executionOrder = List.copyOf(executionOrder);
    }

    public boolean valid() {
        return errorCount() == 0;
    }

    public long errorCount() {
        return issues.stream().filter(i -> i.severity() == Severity.ERROR).count();
    }

    public long warningCount() {
        return issues.stream().filter(i -> i.severity() == Severity.WARNING).count();
    }
}
