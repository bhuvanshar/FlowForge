package com.flowforge.application.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowforge.domain.execution.ErrorDetails;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Run-scoped state shared by all nodes of one execution. Created and owned by the engine thread; executors receive it
 * through {@link NodeExecutionRequest} and must treat everything except {@link #variables()} as read-only.
 */
public final class ExecutionContext {

    private final UUID executionId;
    private final UUID workflowId;
    private final int workflowVersion;
    private final JsonNode triggerInput;
    private final Instant deadline;
    private final CancellationToken cancellation;
    private final Map<String, JsonNode> outputs = new LinkedHashMap<>();
    private final ObjectNode variables = JsonNodeFactory.instance.objectNode();
    private final List<LogEntry> pendingLogs = new ArrayList<>();
    private final SecretMask secrets = new SecretMask();
    private final String ownerId;
    private int loggedCount;

    /** Maximum log lines kept per execution; further lines are dropped (one warning is recorded). */
    public static final int MAX_LOG_LINES = 500;

    /** A sanitized log line waiting to be persisted. */
    public record LogEntry(String nodeId, String level, String message, Instant at) {
    }

    public ExecutionContext(UUID executionId, UUID workflowId, int workflowVersion, JsonNode triggerInput,
                            Instant deadline, CancellationToken cancellation) {
        this(executionId, workflowId, workflowVersion, triggerInput, deadline, cancellation,
                com.flowforge.application.security.OwnerContext.LOCAL_OWNER);
    }

    public ExecutionContext(UUID executionId, UUID workflowId, int workflowVersion, JsonNode triggerInput,
                            Instant deadline, CancellationToken cancellation, String ownerId) {
        this.ownerId = ownerId;
        this.executionId = executionId;
        this.workflowId = workflowId;
        this.workflowVersion = workflowVersion;
        this.triggerInput = triggerInput;
        this.deadline = deadline;
        this.cancellation = cancellation;
    }

    public UUID executionId() {
        return executionId;
    }

    public UUID workflowId() {
        return workflowId;
    }

    public int workflowVersion() {
        return workflowVersion;
    }

    /** Owner of the workflow; executors act as this owner (credential access). */
    public String ownerId() {
        return ownerId;
    }

    /**
     * Secrets used by this run. Executors must {@link SecretMask#register register} every secret value they send so the
     * engine can remove it from stored inputs, outputs, errors and logs.
     */
    public SecretMask secrets() {
        return secrets;
    }

    /** The data the execution was started with (request body or the trigger's test input). */
    public JsonNode triggerInput() {
        return triggerInput;
    }

    /** The execution must finish by this instant (overall timeout). */
    public Instant deadline() {
        return deadline;
    }

    public boolean isCancelled() {
        return cancellation.isCancelled();
    }

    /** Output of an already executed node, by node id. */
    public Optional<JsonNode> outputOf(String nodeId) {
        return Optional.ofNullable(outputs.get(nodeId));
    }

    /** Outputs of all nodes executed so far, in execution order. */
    public Map<String, JsonNode> outputs() {
        return Collections.unmodifiableMap(outputs);
    }

    /** Workflow-scoped variables; writable by executors (e.g. Set Variables in Module 8). */
    public ObjectNode variables() {
        return variables;
    }

    /**
     * Adds a line to the execution log. The message is sanitized (control characters removed, obvious credentials
     * redacted, truncated to 2000 characters) before it is stored. Thread-safe.
     *
     * @param level DEBUG, INFO, WARN or ERROR
     */
    public synchronized void log(String nodeId, String level, String message) {
        if (loggedCount > MAX_LOG_LINES) {
            return;
        }
        loggedCount++;
        if (loggedCount > MAX_LOG_LINES) {
            pendingLogs.add(new LogEntry(null, "WARN", "Log limit of " + MAX_LOG_LINES + " lines reached; further lines "
                    + "are dropped.", Instant.now()));
            return;
        }
        String safe = ErrorDetails.sanitized("LOG", secrets.mask(message)).message();
        pendingLogs.add(new LogEntry(nodeId, level, safe == null ? "" : safe, Instant.now()));
    }

    /** Returns and clears the log lines written since the last call (used by the engine to persist them). */
    synchronized List<LogEntry> drainLogs() {
        List<LogEntry> drained = List.copyOf(pendingLogs);
        pendingLogs.clear();
        return drained;
    }

    void recordOutput(String nodeId, JsonNode output) {
        outputs.put(nodeId, output);
    }
}
