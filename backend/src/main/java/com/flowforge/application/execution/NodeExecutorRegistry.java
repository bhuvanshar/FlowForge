package com.flowforge.application.execution;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** All available node executors, by node type. Two executors for one type is a startup error. */
@Component
public class NodeExecutorRegistry {

    private final Map<String, NodeExecutor> executors;

    public NodeExecutorRegistry(List<NodeExecutor> executors) {
        Map<String, NodeExecutor> byType = new TreeMap<>();
        for (NodeExecutor executor : executors) {
            NodeExecutor previous = byType.putIfAbsent(executor.nodeType(), executor);
            if (previous != null) {
                throw new IllegalStateException("Two executors for node type " + executor.nodeType() + ": "
                        + previous.getClass().getName() + " and " + executor.getClass().getName());
            }
        }
        this.executors = Collections.unmodifiableMap(byType);
    }

    public Optional<NodeExecutor> find(String nodeType) {
        return Optional.ofNullable(executors.get(nodeType));
    }

    public Set<String> supportedTypes() {
        return executors.keySet();
    }

    /** Node types in {@code types} without an executor, sorted. */
    public List<String> missing(Collection<String> types) {
        return types.stream().filter(t -> !executors.containsKey(t)).distinct().sorted().collect(Collectors.toList());
    }
}
