package com.flowforge.config;

import com.flowforge.application.execution.ExecutionEngine;
import com.flowforge.application.execution.ExecutionEventSink;
import com.flowforge.application.execution.ExecutionPersistenceService;
import com.flowforge.application.execution.NodeExecutorRegistry;
import com.flowforge.domain.execution.ExecutionPlanner;
import com.flowforge.domain.validation.NodeTypeRegistry;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Two thread pools: bounded <em>execution workers</em> (one execution each, nodes run sequentially) and unbounded
 * <em>node threads</em> on which each node call runs, so a worker can enforce timeouts and cancellation by interrupting
 * the node thread. At most {@code workerThreads} node calls are in flight at once (one per worker).
 */
@Configuration
@EnableConfigurationProperties(ExecutionProperties.class)
public class ExecutionConfig {

    @Bean
    public ExecutionPlanner executionPlanner(NodeTypeRegistry registry) {
        return new ExecutionPlanner(registry);
    }

    @Bean
    public TaskExecutor executionWorkers(ExecutionProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("ff-exec-");
        executor.setCorePoolSize(properties.workerThreads());
        executor.setMaxPoolSize(properties.workerThreads());
        executor.setQueueCapacity(properties.queueCapacity());
        // On shutdown the executor first waits for running executions (spring.lifecycle.timeout-per-shutdown-phase),
        // then interrupts them; they record EXECUTION_INTERRUPTED (or are recovered as such on the next start).
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }

    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService nodeThreads() {
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "ff-node-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newCachedThreadPool(factory);
    }

    @Bean
    public ExecutionEngine executionEngine(NodeExecutorRegistry executors, ExecutionPersistenceService persistence,
                                           ExecutorService nodeThreads, ExecutionProperties properties, Clock clock,
                                           ExecutionEventSink events) {
        return new ExecutionEngine(executors, persistence, nodeThreads, properties, clock, events);
    }
}
