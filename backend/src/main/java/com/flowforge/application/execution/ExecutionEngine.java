package com.flowforge.application.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.flowforge.config.ExecutionProperties;
import com.flowforge.domain.execution.ErrorDetails;
import com.flowforge.domain.execution.ExecutionPlan;
import com.flowforge.domain.execution.ExecutionStatus;
import com.flowforge.domain.execution.NodeExecutionStatus;
import com.flowforge.domain.workflow.EdgeDefinition;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs one {@link ExecutionJob} sequentially in plan order on the calling (worker) thread.
 *
 * <p><b>Semantics.</b>
 * <ul>
 *   <li>The trigger receives the trigger input. Every other node receives the output of its single parent.</li>
 *   <li>A node runs only if its parent SUCCEEDED and activated the output handle its incoming edge starts from.
 *       Otherwise it is SKIPPED, and so, transitively, is everything below it (skipped branch).</li>
 *   <li>The first failure ends the execution: the failed node is FAILED, every node not yet run is SKIPPED and the
 *       execution is FAILED with the node's error (prefixed with the node name).</li>
 *   <li>Cancellation is checked before each node and while a node runs (the node thread is interrupted). The running
 *       node is FAILED with {@code CANCELLED}, the rest SKIPPED, the execution CANCELLED.</li>
 *   <li>Timeouts: per node ({@code NODE_TIMEOUT}) and per execution ({@code EXECUTION_TIMEOUT}); both interrupt the
 *       node thread and fail the execution.</li>
 *   <li>Joins and loops never reach the engine: the planner rejects them.</li>
 * </ul>
 * Each state change is persisted immediately in its own transaction; no transaction spans a node call.
 */
public class ExecutionEngine {

    public static final String CANCELLED = "CANCELLED";
    public static final String NODE_TIMEOUT = "NODE_TIMEOUT";
    public static final String EXECUTION_TIMEOUT = "EXECUTION_TIMEOUT";
    public static final String NODE_ERROR = "NODE_ERROR";
    public static final String NO_EXECUTOR = "NO_EXECUTOR";
    public static final String INVALID_OUTPUT_HANDLE = "INVALID_OUTPUT_HANDLE";
    public static final String EXECUTION_INTERRUPTED = "EXECUTION_INTERRUPTED";
    public static final String ENGINE_ERROR = "ENGINE_ERROR";

    private static final Logger log = LoggerFactory.getLogger(ExecutionEngine.class);
    private static final long POLL_MILLIS = 25;

    private final NodeExecutorRegistry executors;
    private final ExecutionPersistence persistence;
    private final ExecutorService nodeThreads;
    private final ExecutionProperties properties;
    private final Clock clock;
    private final ExecutionEventSink events;

    public ExecutionEngine(NodeExecutorRegistry executors, ExecutionPersistence persistence,
                           ExecutorService nodeThreads, ExecutionProperties properties, Clock clock) {
        this(executors, persistence, nodeThreads, properties, clock, ExecutionEventSink.NOOP);
    }

    public ExecutionEngine(NodeExecutorRegistry executors, ExecutionPersistence persistence,
                           ExecutorService nodeThreads, ExecutionProperties properties, Clock clock,
                           ExecutionEventSink events) {
        this.executors = executors;
        this.persistence = persistence;
        this.nodeThreads = nodeThreads;
        this.properties = properties;
        this.clock = clock;
        this.events = events;
    }

    /** Runs the job to a terminal state. Never throws; problems are recorded on the execution. */
    public ExecutionStatus run(ExecutionJob job) {
        try {
            return execute(job);
        } catch (RuntimeException e) {
            log.error("Execution {} failed unexpectedly", job.executionId(), e);
            try {
                ErrorDetails error = ErrorDetails.sanitized(ENGINE_ERROR, "The execution engine failed unexpectedly.");
                persistence.abandon(job.executionId(), ExecutionStatus.FAILED, error);
                events.publish(ExecutionEvent.execution(ExecutionEvent.Type.EXECUTION_FAILED, job.executionId(),
                        ExecutionStatus.FAILED, clock.instant(), null, error));
            } catch (RuntimeException ignored) {
                log.error("Could not record failure of execution {}", job.executionId(), ignored);
            }
            return ExecutionStatus.FAILED;
        }
    }

    private ExecutionStatus execute(ExecutionJob job) {
        if (job.cancellation().isCancelled()) {
            skipAll(job, 0);
            finish(job, ExecutionStatus.CANCELLED, null, null);
            return ExecutionStatus.CANCELLED;
        }

        Instant startedAt = clock.instant();
        Instant deadline = startedAt.plus(properties.maxDuration());
        ExecutionContext context = new ExecutionContext(job.executionId(), job.workflowId(), job.workflowVersion(),
                job.triggerInput(), deadline, job.cancellation(), job.ownerId());
        SecretMask secrets = context.secrets();
        persistence.executionStarted(job.executionId(), startedAt);
        events.publish(ExecutionEvent.execution(ExecutionEvent.Type.EXECUTION_STARTED, job.executionId(),
                ExecutionStatus.RUNNING, startedAt, null, null));

        Map<String, Set<String>> activated = new HashMap<>();
        for (ExecutionPlan.Step step : job.plan().steps()) {
            UUID rowId = job.nodeExecutionIds().get(step.node().id());

            Optional<EdgeDefinition> incoming = step.incoming();
            if (incoming.isPresent() && !activated.getOrDefault(incoming.get().source(), Set.of())
                    .contains(incoming.get().sourceHandle())) {
                skipped(job, step); // branch not taken (or parent skipped)
                continue;
            }
            if (job.cancellation().isCancelled()) {
                return stop(job, step.index(), ExecutionStatus.CANCELLED, null, startedAt);
            }
            if (!clock.instant().isBefore(deadline)) {
                return stop(job, step.index(), ExecutionStatus.FAILED, ErrorDetails.sanitized(EXECUTION_TIMEOUT,
                        "The execution exceeded its time limit of " + properties.maxDuration() + "."), startedAt);
            }

            JsonNode input = incoming.map(e -> context.outputOf(e.source()).orElseThrow()).orElse(job.triggerInput());
            Instant nodeStart = clock.instant();
            persistence.nodeStarted(rowId, nodeStart, secrets.mask(input));
            events.publish(ExecutionEvent.node(ExecutionEvent.Type.NODE_STARTED, job.executionId(), step.node().id(),
                    step.node().name(), NodeExecutionStatus.RUNNING, nodeStart, null, null));
            NodeExecutionResult result = invoke(step, new NodeExecutionRequest(step.node(), input, context), deadline,
                    job.cancellation());

            if (result.succeeded() && !step.type().outputs().containsAll(result.activatedOutputs())) {
                result = NodeExecutionResult.failure(INVALID_OUTPUT_HANDLE, "The node activated outputs "
                        + result.activatedOutputs() + " but only declares " + step.type().outputs() + ".");
            }
            flushLogs(job, context);
            if (result.succeeded()) {
                context.recordOutput(step.node().id(), result.output());
                activated.put(step.node().id(), result.activatedOutputs());
                Instant done = clock.instant();
                // Stored and published copies never contain registered secrets; downstream nodes get the real value.
                persistence.nodeSucceeded(rowId, done, secrets.mask(result.output()));
                events.publish(ExecutionEvent.node(ExecutionEvent.Type.NODE_SUCCEEDED, job.executionId(),
                        step.node().id(), step.node().name(), NodeExecutionStatus.SUCCEEDED, done,
                        Duration.between(nodeStart, done).toMillis(), null));
                continue;
            }

            Instant failedAt = clock.instant();
            ErrorDetails nodeError = secrets.mask(result.error());
            persistence.nodeFailed(rowId, failedAt, nodeError);
            events.publish(ExecutionEvent.node(ExecutionEvent.Type.NODE_FAILED, job.executionId(), step.node().id(),
                    step.node().name(), NodeExecutionStatus.FAILED, failedAt,
                    Duration.between(nodeStart, failedAt).toMillis(), nodeError));
            if (CANCELLED.equals(nodeError.code())) {
                return stop(job, step.index() + 1, ExecutionStatus.CANCELLED, null, startedAt);
            }
            ErrorDetails error = ErrorDetails.sanitized(nodeError.code(),
                    "Node '" + step.node().name() + "' failed: " + nodeError.message());
            return stop(job, step.index() + 1, ExecutionStatus.FAILED, error, startedAt);
        }
        // Cancellation can arrive as the final node completes, or while the remaining branches are skipped.
        if (job.cancellation().isCancelled()) {
            finish(job, ExecutionStatus.CANCELLED, null, startedAt);
            return ExecutionStatus.CANCELLED;
        }
        finish(job, ExecutionStatus.SUCCEEDED, null, startedAt);
        return ExecutionStatus.SUCCEEDED;
    }

    /** Runs one node on a node thread, enforcing its time limit and watching for cancellation. */
    private NodeExecutionResult invoke(ExecutionPlan.Step step, NodeExecutionRequest request, Instant deadline,
                                       CancellationToken cancellation) {
        Optional<NodeExecutor> executor = executors.find(step.node().type());
        if (executor.isEmpty()) {
            return NodeExecutionResult.failure(NO_EXECUTOR, "No executor is available for node type '"
                    + step.node().type() + "'.");
        }
        Duration nodeLimit = executor.get().timeout(step.node()).orElse(properties.nodeTimeout());
        Duration untilDeadline = Duration.between(clock.instant(), deadline);
        boolean limitedByExecution = untilDeadline.compareTo(nodeLimit) < 0;
        long limitNanos = Math.max(0, (limitedByExecution ? untilDeadline : nodeLimit).toNanos());

        Future<NodeExecutionResult> future = nodeThreads.submit(() -> executor.get().execute(request));
        long endNanos = System.nanoTime() + limitNanos;
        try {
            while (true) {
                long remaining = endNanos - System.nanoTime();
                if (remaining <= 0) {
                    future.cancel(true);
                    return limitedByExecution
                            ? NodeExecutionResult.failure(EXECUTION_TIMEOUT, "The execution exceeded its time limit of "
                                    + properties.maxDuration() + ".")
                            : NodeExecutionResult.failure(NODE_TIMEOUT, "The node did not finish within " + nodeLimit
                                    + ".");
                }
                try {
                    NodeExecutionResult result = future.get(
                            Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(POLL_MILLIS)), TimeUnit.NANOSECONDS);
                    return result != null ? result
                            : NodeExecutionResult.failure(NODE_ERROR, "The node returned no result.");
                } catch (TimeoutException stillRunning) {
                    if (cancellation.isCancelled()) {
                        future.cancel(true);
                        return NodeExecutionResult.failure(CANCELLED, "The execution was cancelled.");
                    }
                }
            }
        } catch (ExecutionException e) {
            return fromThrowable(e.getCause());
        } catch (CancellationException e) {
            return NodeExecutionResult.failure(CANCELLED, "The execution was cancelled.");
        } catch (InterruptedException e) {
            // The worker itself is being shut down.
            future.cancel(true);
            Thread.currentThread().interrupt();
            return NodeExecutionResult.failure(EXECUTION_INTERRUPTED, "The server is shutting down.");
        }
    }

    private static NodeExecutionResult fromThrowable(Throwable error) {
        if (error instanceof NodeExecutionException known) {
            return NodeExecutionResult.failure(known.code(), known.getMessage());
        }
        if (error instanceof InterruptedException) {
            return NodeExecutionResult.failure(CANCELLED, "The node was interrupted.");
        }
        String message = error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
        log.debug("Node executor threw", error);
        return NodeExecutionResult.failure(NODE_ERROR, message);
    }

    private void flushLogs(ExecutionJob job, ExecutionContext context) {
        var entries = context.drainLogs();
        if (!entries.isEmpty()) {
            persistence.appendLogs(job.executionId(), entries);
        }
    }

    /** Skips every node from plan position {@code from} on and finishes the execution. */
    private ExecutionStatus stop(ExecutionJob job, int from, ExecutionStatus status, ErrorDetails error,
                                 Instant startedAt) {
        skipAll(job, from);
        finish(job, status, error, startedAt);
        return status;
    }

    /** Persists the terminal state, then announces it. */
    private void finish(ExecutionJob job, ExecutionStatus status, ErrorDetails error, Instant startedAt) {
        Instant now = clock.instant();
        persistence.executionFinished(job.executionId(), status, now, error);
        events.publish(ExecutionEvent.execution(ExecutionEvent.terminalTypeOf(status), job.executionId(), status, now,
                startedAt == null ? null : Duration.between(startedAt, now).toMillis(), error));
    }

    private void skipped(ExecutionJob job, ExecutionPlan.Step step) {
        Instant now = clock.instant();
        persistence.nodeSkipped(job.nodeExecutionIds().get(step.node().id()), now);
        events.publish(ExecutionEvent.node(ExecutionEvent.Type.NODE_SKIPPED, job.executionId(), step.node().id(),
                step.node().name(), NodeExecutionStatus.SKIPPED, now, null, null));
    }

    private void skipAll(ExecutionJob job, int from) {
        var steps = job.plan().steps();
        for (int i = from; i < steps.size(); i++) {
            skipped(job, steps.get(i));
        }
    }
}
