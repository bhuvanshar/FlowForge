package com.flowforge.domain.execution;

import com.flowforge.domain.validation.NodeTypeDefinition;
import com.flowforge.domain.workflow.EdgeDefinition;
import com.flowforge.domain.workflow.NodeDefinition;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Deterministic, sequential plan for one workflow snapshot.
 *
 * @param steps    every node in execution order; the first step is the trigger
 * @param outgoing edges leaving each node id, in definition order
 */
public record ExecutionPlan(List<Step> steps, Map<String, List<EdgeDefinition>> outgoing) {

    public ExecutionPlan {
        steps = List.copyOf(steps);
        outgoing = Map.copyOf(outgoing);
    }

    /**
     * @param incoming the single edge feeding this node; empty only for the trigger
     */
    public record Step(int index, NodeDefinition node, NodeTypeDefinition type, Optional<EdgeDefinition> incoming) {

        public boolean isTrigger() {
            return incoming.isEmpty();
        }
    }

    public Step trigger() {
        return steps.get(0);
    }

    public List<String> nodeIds() {
        return steps.stream().map(s -> s.node().id()).toList();
    }
}
