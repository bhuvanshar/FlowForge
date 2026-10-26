package com.flowforge.domain.validation;

import java.util.List;

/** What the validator needs to know about a node type (from the node type catalog). */
public record NodeTypeDefinition(String type, int typeVersion, Role role, List<String> inputs, List<String> outputs) {

    public enum Role {
        TRIGGER,
        ACTION
    }

    public NodeTypeDefinition {
        inputs = List.copyOf(inputs);
        outputs = List.copyOf(outputs);
    }

    public boolean isTrigger() {
        return role == Role.TRIGGER;
    }

    /** True when the node has more than one output handle, e.g. a condition with {@code true}/{@code false}. */
    public boolean isBranching() {
        return outputs.size() > 1;
    }
}
