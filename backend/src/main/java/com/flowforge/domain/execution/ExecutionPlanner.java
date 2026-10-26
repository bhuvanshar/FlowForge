package com.flowforge.domain.execution;

import com.flowforge.domain.validation.NodeTypeDefinition;
import com.flowforge.domain.validation.NodeTypeRegistry;
import com.flowforge.domain.workflow.EdgeDefinition;
import com.flowforge.domain.workflow.NodeDefinition;
import com.flowforge.domain.workflow.WorkflowDefinition;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;

/**
 * Builds the sequential execution plan with Kahn's algorithm, breaking ties by position in the definition, so the same
 * snapshot always runs in the same order.
 *
 * <p>Supported shape (MVP): exactly one trigger; every other node has exactly one incoming edge; no cycles; every node
 * reachable from the trigger. That makes the graph a tree rooted at the trigger: each node receives the output of its
 * single parent. Anything else (joins, loops, orphans) is rejected with {@link PlanningException} instead of being
 * executed with guessed semantics.
 */
public final class ExecutionPlanner {

    private final NodeTypeRegistry registry;

    public ExecutionPlanner(NodeTypeRegistry registry) {
        this.registry = registry;
    }

    public ExecutionPlan plan(WorkflowDefinition definition) {
        List<NodeDefinition> nodes = definition.nodes();
        Map<String, Integer> index = new HashMap<>();
        Map<String, NodeTypeDefinition> types = new HashMap<>();
        String trigger = null;
        for (int i = 0; i < nodes.size(); i++) {
            NodeDefinition node = nodes.get(i);
            if (index.putIfAbsent(node.id(), i) != null) {
                throw new PlanningException("Duplicate node id '" + node.id() + "'.");
            }
            NodeTypeDefinition type = registry.find(node.type())
                    .orElseThrow(() -> new PlanningException("Unknown node type '" + node.type() + "'."));
            types.put(node.id(), type);
            if (type.isTrigger()) {
                if (trigger != null) {
                    throw new PlanningException("More than one trigger.");
                }
                trigger = node.id();
            }
        }
        if (trigger == null) {
            throw new PlanningException("The workflow has no trigger.");
        }

        Map<String, EdgeDefinition> incoming = new HashMap<>();
        Map<String, List<EdgeDefinition>> outgoing = new LinkedHashMap<>();
        nodes.forEach(n -> outgoing.put(n.id(), new ArrayList<>()));
        for (EdgeDefinition edge : definition.edges()) {
            if (!index.containsKey(edge.source()) || !index.containsKey(edge.target())) {
                throw new PlanningException("Edge '" + edge.id() + "' references a missing node.");
            }
            if (incoming.putIfAbsent(edge.target(), edge) != null) {
                throw new PlanningException("Node '" + edge.target() + "' has more than one incoming edge; joins are "
                        + "not supported.");
            }
            outgoing.get(edge.source()).add(edge);
        }
        if (incoming.containsKey(trigger)) {
            throw new PlanningException("The trigger cannot have incoming edges.");
        }

        PriorityQueue<Integer> ready = new PriorityQueue<>();
        ready.add(index.get(trigger));
        List<ExecutionPlan.Step> steps = new ArrayList<>();
        while (!ready.isEmpty()) {
            NodeDefinition node = nodes.get(ready.poll());
            steps.add(new ExecutionPlan.Step(steps.size(), node, types.get(node.id()),
                    Optional.ofNullable(incoming.get(node.id()))));
            // Single incoming edge per node: a child becomes ready as soon as its parent is planned.
            outgoing.get(node.id()).forEach(e -> ready.add(index.get(e.target())));
        }
        if (steps.size() != nodes.size()) {
            throw new PlanningException("Some nodes are unreachable from the trigger or form a loop.");
        }
        return new ExecutionPlan(steps, outgoing);
    }
}
