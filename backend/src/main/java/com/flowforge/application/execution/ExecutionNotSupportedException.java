package com.flowforge.application.execution;

import java.util.List;

/** The workflow uses node types this server cannot execute yet. Mapped to HTTP 501. */
public class ExecutionNotSupportedException extends RuntimeException {

    private final List<String> nodeTypes;

    public ExecutionNotSupportedException(List<String> nodeTypes) {
        super("These node types cannot be executed yet: " + String.join(", ", nodeTypes) + ".");
        this.nodeTypes = List.copyOf(nodeTypes);
    }

    public List<String> nodeTypes() {
        return nodeTypes;
    }
}
