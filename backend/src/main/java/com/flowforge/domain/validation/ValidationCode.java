package com.flowforge.domain.validation;

/**
 * Stable machine-readable issue codes. Clients may switch on them; never rename or reuse a code.
 * The default severity is the one used by {@link GraphValidator}.
 */
public enum ValidationCode {
    // Size limits
    TOO_MANY_NODES(Severity.ERROR),
    TOO_MANY_EDGES(Severity.ERROR),
    TOO_MANY_OUTGOING_EDGES(Severity.ERROR),
    // Nodes
    DUPLICATE_NODE_ID(Severity.ERROR),
    DUPLICATE_NODE_NAME(Severity.ERROR),
    UNKNOWN_NODE_TYPE(Severity.ERROR),
    UNSUPPORTED_NODE_TYPE_VERSION(Severity.ERROR),
    CONFIGURATION_INVALID(Severity.ERROR),
    // Edges
    DUPLICATE_EDGE_ID(Severity.ERROR),
    EDGE_SOURCE_NOT_FOUND(Severity.ERROR),
    EDGE_TARGET_NOT_FOUND(Severity.ERROR),
    SELF_LOOP(Severity.ERROR),
    INVALID_SOURCE_HANDLE(Severity.ERROR),
    INVALID_TARGET_HANDLE(Severity.ERROR),
    DUPLICATE_CONNECTION(Severity.ERROR),
    UNSUPPORTED_MERGE(Severity.ERROR),
    // Graph shape
    NO_TRIGGER(Severity.ERROR),
    MULTIPLE_TRIGGERS(Severity.ERROR),
    CYCLE_DETECTED(Severity.ERROR),
    DISCONNECTED_NODE(Severity.ERROR),
    UNREACHABLE_NODE(Severity.ERROR),
    NO_BRANCH_CONNECTED(Severity.ERROR),
    BRANCH_NOT_CONNECTED(Severity.WARNING),
    TRIGGER_NOT_CONNECTED(Severity.WARNING);

    private final Severity severity;

    ValidationCode(Severity severity) {
        this.severity = severity;
    }

    public Severity severity() {
        return severity;
    }
}
