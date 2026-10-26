package com.flowforge.domain.validation;

import com.flowforge.domain.workflow.EdgeDefinition;
import com.flowforge.domain.workflow.NodeDefinition;
import com.flowforge.domain.workflow.WorkflowDefinition;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validates a workflow graph. Pure and deterministic: the same definition always yields the same issues in the same
 * order (checks run in a fixed sequence and iterate nodes and edges in definition order).
 *
 * <p>Supported graph model for this version:
 * <ul>
 *   <li>exactly one trigger node; every other node must be reachable from it</li>
 *   <li>no cycles (looping requires a dedicated loop node, not available yet)</li>
 *   <li>at most one incoming edge per input handle (joining branches requires a merge node, not available yet)</li>
 *   <li>a branching node (Condition) must connect at least one branch; an unconnected branch is a warning</li>
 *   <li>at most {@value #MAX_OUTGOING_EDGES_PER_HANDLE} edges leave one output handle</li>
 * </ul>
 * Edges whose endpoints exist (and are not self-loops or duplicate connections) take part in the graph analysis even if
 * a handle is invalid, so one mistake does not cascade into many unrelated reachability errors.
 */
public final class GraphValidator {

    public static final int MAX_OUTGOING_EDGES_PER_HANDLE = 20;

    private final NodeTypeRegistry registry;
    private final ConfigurationValidator configurationValidator;

    public GraphValidator(NodeTypeRegistry registry, ConfigurationValidator configurationValidator) {
        this.registry = registry;
        this.configurationValidator = configurationValidator;
    }

    public ValidationResult validate(WorkflowDefinition definition) {
        return new Run(definition).execute();
    }

    /** State of one validation run. */
    private final class Run {

        private final List<NodeDefinition> nodes;
        private final List<EdgeDefinition> edges;
        private final List<ValidationIssue> issues = new ArrayList<>();

        /** First occurrence of each node id, mapped to its index in {@link #nodes}. */
        private final Map<String, Integer> nodeIndex = new LinkedHashMap<>();
        private final Map<Integer, NodeTypeDefinition> types = new HashMap<>();
        /** Indexes (into {@link #edges}) of edges that take part in graph analysis. */
        private final List<Integer> graphEdges = new ArrayList<>();
        private final List<List<Integer>> outgoing = new ArrayList<>();
        private final List<List<Integer>> incoming = new ArrayList<>();
        private boolean hasCycle;

        Run(WorkflowDefinition definition) {
            this.nodes = definition.nodes();
            this.edges = definition.edges();
        }

        ValidationResult execute() {
            checkSize();
            checkNodes();
            checkEdges();
            buildAdjacency();
            checkConnectionCounts();
            Integer trigger = checkTriggers();
            checkBranches();
            checkCycles();
            checkConnectivity(trigger);
            checkConfigurations();
            return new ValidationResult(issues, executionOrder(trigger));
        }

        // ---------------------------------------------------------------------------------------------------------
        // Checks
        // ---------------------------------------------------------------------------------------------------------

        private void checkSize() {
            if (nodes.size() > WorkflowDefinition.MAX_NODES) {
                add(ValidationCode.TOO_MANY_NODES, "The workflow has " + nodes.size() + " nodes; the maximum is "
                        + WorkflowDefinition.MAX_NODES + ".", null, null, "nodes");
            }
            if (edges.size() > WorkflowDefinition.MAX_EDGES) {
                add(ValidationCode.TOO_MANY_EDGES, "The workflow has " + edges.size() + " connections; the maximum is "
                        + WorkflowDefinition.MAX_EDGES + ".", null, null, "edges");
            }
        }

        private void checkNodes() {
            Map<String, String> names = new HashMap<>();
            for (int i = 0; i < nodes.size(); i++) {
                NodeDefinition node = nodes.get(i);
                if (nodeIndex.putIfAbsent(node.id(), i) != null) {
                    add(ValidationCode.DUPLICATE_NODE_ID, "Node id '" + node.id() + "' is used more than once.",
                            node.id(), null, "nodes[" + i + "].id");
                    continue;
                }
                String previous = names.putIfAbsent(node.name(), node.id());
                if (previous != null) {
                    add(ValidationCode.DUPLICATE_NODE_NAME, "Another node is already named '" + node.name() + "'.",
                            node.id(), null, "nodes[" + i + "].name");
                }
                Optional<NodeTypeDefinition> type = registry.find(node.type());
                if (type.isEmpty()) {
                    add(ValidationCode.UNKNOWN_NODE_TYPE, "Unknown node type '" + node.type() + "'.", node.id(), null,
                            "nodes[" + i + "].type");
                } else if (type.get().typeVersion() != node.typeVersion()) {
                    add(ValidationCode.UNSUPPORTED_NODE_TYPE_VERSION, "Node type '" + node.type() + "' version "
                            + node.typeVersion() + " is not supported (supported: " + type.get().typeVersion() + ").",
                            node.id(), null, "nodes[" + i + "].typeVersion");
                } else {
                    types.put(i, type.get());
                }
            }
        }

        private void checkEdges() {
            Set<String> edgeIds = new HashSet<>();
            Set<String> connections = new HashSet<>();
            for (int i = 0; i < edges.size(); i++) {
                EdgeDefinition edge = edges.get(i);
                String at = "edges[" + i + "]";
                if (!edgeIds.add(edge.id())) {
                    add(ValidationCode.DUPLICATE_EDGE_ID, "Connection id '" + edge.id() + "' is used more than once.",
                            null, edge.id(), at + ".id");
                }
                Integer source = nodeIndex.get(edge.source());
                Integer target = nodeIndex.get(edge.target());
                if (source == null) {
                    add(ValidationCode.EDGE_SOURCE_NOT_FOUND, "Connection starts at missing node '" + edge.source()
                            + "'.", null, edge.id(), at + ".source");
                }
                if (target == null) {
                    add(ValidationCode.EDGE_TARGET_NOT_FOUND, "Connection ends at missing node '" + edge.target()
                            + "'.", null, edge.id(), at + ".target");
                }
                if (source == null || target == null) {
                    continue;
                }
                if (source.equals(target)) {
                    add(ValidationCode.SELF_LOOP, "Node '" + name(source) + "' is connected to itself.",
                            edge.source(), edge.id(), at);
                    continue;
                }
                NodeTypeDefinition sourceType = types.get(source);
                if (sourceType != null && !sourceType.outputs().contains(edge.sourceHandle())) {
                    add(ValidationCode.INVALID_SOURCE_HANDLE, "'" + name(source) + "' has no output '"
                            + edge.sourceHandle() + "'" + handles(" (outputs: ", sourceType.outputs()) + ".",
                            edge.source(), edge.id(), at + ".sourceHandle");
                }
                NodeTypeDefinition targetType = types.get(target);
                if (targetType != null && !targetType.inputs().contains(edge.targetHandle())) {
                    add(ValidationCode.INVALID_TARGET_HANDLE, targetType.inputs().isEmpty()
                                    ? "'" + name(target) + "' is a trigger and cannot have incoming connections."
                                    : "'" + name(target) + "' has no input '" + edge.targetHandle() + "'"
                                    + handles(" (inputs: ", targetType.inputs()) + ".",
                            edge.target(), edge.id(), at + ".targetHandle");
                }
                String key = edge.source() + '\u0000' + edge.sourceHandle() + '\u0000' + edge.target() + '\u0000'
                        + edge.targetHandle();
                if (!connections.add(key)) {
                    add(ValidationCode.DUPLICATE_CONNECTION, "'" + name(source) + "' is already connected to '"
                            + name(target) + "' this way.", edge.target(), edge.id(), at);
                    continue;
                }
                graphEdges.add(i);
            }
        }

        private void buildAdjacency() {
            for (int i = 0; i < nodes.size(); i++) {
                outgoing.add(new ArrayList<>());
                incoming.add(new ArrayList<>());
            }
            for (int e : graphEdges) {
                outgoing.get(source(e)).add(e);
                incoming.get(target(e)).add(e);
            }
        }

        private void checkConnectionCounts() {
            for (int n : nodeIndex.values()) {
                Map<String, List<Integer>> byHandle = new LinkedHashMap<>();
                for (int e : outgoing.get(n)) {
                    byHandle.computeIfAbsent(edges.get(e).sourceHandle(), h -> new ArrayList<>()).add(e);
                }
                byHandle.forEach((handle, list) -> {
                    if (list.size() > MAX_OUTGOING_EDGES_PER_HANDLE) {
                        add(ValidationCode.TOO_MANY_OUTGOING_EDGES, "Output '" + handle + "' of '" + name(n) + "' has "
                                + list.size() + " connections; the maximum is " + MAX_OUTGOING_EDGES_PER_HANDLE + ".",
                                nodes.get(n).id(), null, "nodes[" + n + "]");
                    }
                });
                Map<String, Integer> perInput = new HashMap<>();
                for (int e : incoming.get(n)) {
                    int count = perInput.merge(edges.get(e).targetHandle(), 1, Integer::sum);
                    if (count > 1) {
                        add(ValidationCode.UNSUPPORTED_MERGE, "'" + name(n) + "' already has an incoming connection. "
                                        + "Joining branches into one node is not supported yet.",
                                nodes.get(n).id(), edges.get(e).id(), "edges[" + e + "]");
                    }
                }
            }
        }

        /** @return index of the entry trigger, or null when there is none */
        private Integer checkTriggers() {
            List<Integer> triggers = nodeIndex.values().stream()
                    .filter(n -> types.containsKey(n) && types.get(n).isTrigger())
                    .toList();
            if (triggers.isEmpty()) {
                add(ValidationCode.NO_TRIGGER, nodes.isEmpty()
                        ? "The workflow is empty. Add a trigger to start it."
                        : "The workflow has no trigger. Add a trigger node to start it.", null, null, "nodes");
                return null;
            }
            for (int extra : triggers.subList(1, triggers.size())) {
                add(ValidationCode.MULTIPLE_TRIGGERS, "Only one trigger is allowed; '" + name(triggers.get(0))
                        + "' is already the trigger.", nodes.get(extra).id(), null, "nodes[" + extra + "]");
            }
            return triggers.get(0);
        }

        private void checkBranches() {
            for (int n : nodeIndex.values()) {
                NodeTypeDefinition type = types.get(n);
                if (type == null || !type.isBranching()) {
                    continue;
                }
                Set<String> connected = outgoing.get(n).stream().map(e -> edges.get(e).sourceHandle())
                        .collect(Collectors.toSet());
                List<String> missing = type.outputs().stream().filter(h -> !connected.contains(h)).toList();
                if (missing.size() == type.outputs().size()) {
                    add(ValidationCode.NO_BRANCH_CONNECTED, "'" + name(n) + "' has no connected branch. Connect "
                            + String.join(" and/or ", type.outputs()) + ".", nodes.get(n).id(), null,
                            "nodes[" + n + "]");
                } else {
                    for (String handle : missing) {
                        add(ValidationCode.BRANCH_NOT_CONNECTED, "The '" + handle + "' branch of '" + name(n)
                                + "' is not connected; nothing runs when it is taken.", nodes.get(n).id(), null,
                                "nodes[" + n + "]");
                    }
                }
            }
        }

        /** Iterative depth-first search from every node in definition order; each back edge closes one cycle. */
        private void checkCycles() {
            int[] state = new int[nodes.size()]; // 0 = unvisited, 1 = on the current path, 2 = finished
            for (int start : nodeIndex.values()) {
                if (state[start] != 0) {
                    continue;
                }
                Deque<int[]> stack = new ArrayDeque<>(); // {node, next outgoing position}
                List<Integer> path = new ArrayList<>();
                stack.push(new int[] {start, 0});
                path.add(start);
                state[start] = 1;
                while (!stack.isEmpty()) {
                    int[] frame = stack.peek();
                    List<Integer> out = outgoing.get(frame[0]);
                    if (frame[1] < out.size()) {
                        int e = out.get(frame[1]++);
                        int next = target(e);
                        if (state[next] == 0) {
                            state[next] = 1;
                            stack.push(new int[] {next, 0});
                            path.add(next);
                        } else if (state[next] == 1) {
                            reportCycle(path.subList(path.indexOf(next), path.size()), e);
                        }
                    } else {
                        state[frame[0]] = 2;
                        stack.pop();
                        path.remove(path.size() - 1);
                    }
                }
            }
        }

        private void reportCycle(List<Integer> cycle, int closingEdge) {
            hasCycle = true;
            String route = cycle.stream().map(this::name).map(n -> "'" + n + "'").collect(Collectors.joining(" → "))
                    + " → '" + name(cycle.get(0)) + "'";
            add(ValidationCode.CYCLE_DETECTED, "Loop detected: " + route + ". Loops are not supported yet.",
                    nodes.get(cycle.get(0)).id(), edges.get(closingEdge).id(), "edges[" + closingEdge + "]");
        }

        private void checkConnectivity(Integer trigger) {
            Set<Integer> reachable = trigger == null ? Set.of() : reachableFrom(trigger);
            for (int n : nodeIndex.values()) {
                boolean isTrigger = types.containsKey(n) && types.get(n).isTrigger();
                if (isTrigger) {
                    if (trigger != null && n == trigger && outgoing.get(n).isEmpty()) {
                        add(ValidationCode.TRIGGER_NOT_CONNECTED, "The trigger '" + name(n)
                                + "' is not connected to anything, so the workflow does nothing.",
                                nodes.get(n).id(), null, "nodes[" + n + "]");
                    }
                    continue;
                }
                if (outgoing.get(n).isEmpty() && incoming.get(n).isEmpty()) {
                    add(ValidationCode.DISCONNECTED_NODE, "'" + name(n) + "' is not connected to any other node.",
                            nodes.get(n).id(), null, "nodes[" + n + "]");
                } else if (trigger != null && !reachable.contains(n)) {
                    add(ValidationCode.UNREACHABLE_NODE, "'" + name(n) + "' cannot be reached from the trigger '"
                            + name(trigger) + "'.", nodes.get(n).id(), null, "nodes[" + n + "]");
                }
            }
        }

        private void checkConfigurations() {
            for (int n : nodeIndex.values()) {
                if (!types.containsKey(n)) {
                    continue;
                }
                NodeDefinition node = nodes.get(n);
                for (ConfigurationValidator.Violation v : configurationValidator.validate(node.type(),
                        node.configuration())) {
                    String path = "nodes[" + n + "].configuration" + (v.path().isEmpty() ? "" : "." + v.path());
                    add(ValidationCode.CONFIGURATION_INVALID, "'" + node.name() + "': "
                            + (v.path().isEmpty() ? "" : v.path() + ": ") + v.message(), node.id(), null, path);
                }
            }
        }

        // ---------------------------------------------------------------------------------------------------------
        // Graph helpers
        // ---------------------------------------------------------------------------------------------------------

        private Set<Integer> reachableFrom(int start) {
            Set<Integer> seen = new HashSet<>();
            Deque<Integer> queue = new ArrayDeque<>();
            queue.add(start);
            seen.add(start);
            while (!queue.isEmpty()) {
                for (int e : outgoing.get(queue.poll())) {
                    if (seen.add(target(e))) {
                        queue.add(target(e));
                    }
                }
            }
            return seen;
        }

        /** Kahn's algorithm over the nodes reachable from the trigger; ties broken by definition order. */
        private List<String> executionOrder(Integer trigger) {
            if (trigger == null || hasCycle) {
                return List.of();
            }
            Set<Integer> reachable = reachableFrom(trigger);
            Map<Integer, Integer> inDegree = new HashMap<>();
            for (int n : reachable) {
                inDegree.put(n, (int) incoming.get(n).stream().filter(e -> reachable.contains(source(e))).count());
            }
            PriorityQueue<Integer> ready = new PriorityQueue<>();
            inDegree.forEach((n, degree) -> {
                if (degree == 0) {
                    ready.add(n);
                }
            });
            List<String> order = new ArrayList<>();
            while (!ready.isEmpty()) {
                int n = ready.poll();
                order.add(nodes.get(n).id());
                for (int e : outgoing.get(n)) {
                    if (inDegree.merge(target(e), -1, Integer::sum) == 0) {
                        ready.add(target(e));
                    }
                }
            }
            return order;
        }

        private int source(int edge) {
            return nodeIndex.get(edges.get(edge).source());
        }

        private int target(int edge) {
            return nodeIndex.get(edges.get(edge).target());
        }

        private String name(int node) {
            return nodes.get(node).name();
        }

        private String handles(String prefix, List<String> handles) {
            return handles.isEmpty() ? "" : prefix + String.join(", ", handles) + ")";
        }

        private void add(ValidationCode code, String message, String nodeId, String edgeId, String path) {
            issues.add(ValidationIssue.of(code, message, nodeId, edgeId, path));
        }
    }
}
