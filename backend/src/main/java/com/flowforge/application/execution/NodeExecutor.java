package com.flowforge.application.execution;

import com.flowforge.domain.workflow.NodeDefinition;
import java.time.Duration;
import java.util.Optional;

/**
 * Runs one node type. Implementations are Spring beans collected by {@link NodeExecutorRegistry}; exactly one per
 * node type.
 *
 * <p>Contract: run synchronously on the calling thread; respond to thread interruption (timeouts and cancellation
 * interrupt the thread) and to {@link ExecutionContext#isCancelled()}; never evaluate configuration as code; return
 * {@link NodeExecutionResult#failure} or throw {@link NodeExecutionException} for expected failures. Executors must be
 * stateless and thread-safe: several executions may run concurrently.
 */
public interface NodeExecutor {

    /** The node type identifier this executor handles, e.g. {@code core.logger}. */
    String nodeType();

    NodeExecutionResult execute(NodeExecutionRequest request) throws Exception;

    /** Per-node time limit; empty uses the engine default ({@code flowforge.execution.node-timeout}). */
    default Optional<Duration> timeout(NodeDefinition node) {
        return Optional.empty();
    }
}
