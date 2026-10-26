package com.flowforge.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Execution engine settings ({@code flowforge.execution.*}).
 *
 * @param workerThreads         executions running at the same time (each runs its nodes sequentially)
 * @param queueCapacity         accepted executions waiting for a worker; beyond this, execute returns 503
 * @param nodeTimeout           default per-node time limit (executors may declare their own)
 * @param maxDuration           overall time limit of one execution
 * @param maxStoredPayloadBytes node input/output larger than this (compact JSON) is stored truncated; the full value is
 *                              still passed to downstream nodes in memory
 * @param maxActivePerWorkflow  queued + running executions allowed per workflow; more are refused (503) so one busy
 *                              webhook or schedule cannot take every worker and queue slot
 */
@ConfigurationProperties(prefix = "flowforge.execution")
public record ExecutionProperties(Integer workerThreads, Integer queueCapacity, Duration nodeTimeout,
                                  Duration maxDuration, Integer maxStoredPayloadBytes, Integer maxActivePerWorkflow) {

    public ExecutionProperties(Integer workerThreads, Integer queueCapacity, Duration nodeTimeout,
                               Duration maxDuration, Integer maxStoredPayloadBytes) {
        this(workerThreads, queueCapacity, nodeTimeout, maxDuration, maxStoredPayloadBytes, null);
    }

    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public ExecutionProperties {
        workerThreads = workerThreads == null ? 2 : workerThreads;
        queueCapacity = queueCapacity == null ? 100 : queueCapacity;
        nodeTimeout = nodeTimeout == null ? Duration.ofSeconds(30) : nodeTimeout;
        maxDuration = maxDuration == null ? Duration.ofMinutes(10) : maxDuration;
        maxStoredPayloadBytes = maxStoredPayloadBytes == null ? 256 * 1024 : maxStoredPayloadBytes;
        maxActivePerWorkflow = maxActivePerWorkflow == null ? 10 : maxActivePerWorkflow;
    }

    public static ExecutionProperties defaults() {
        return new ExecutionProperties(null, null, null, null, null, null);
    }
}
