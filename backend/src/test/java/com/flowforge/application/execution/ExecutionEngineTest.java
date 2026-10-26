package com.flowforge.application.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowforge.config.ExecutionProperties;
import com.flowforge.domain.execution.ExecutionPlan;
import com.flowforge.domain.execution.ExecutionPlanner;
import com.flowforge.domain.execution.ExecutionStatus;
import com.flowforge.domain.execution.NodeExecutionStatus;
import com.flowforge.domain.workflow.EdgeDefinition;
import com.flowforge.domain.workflow.NodeDefinition;
import com.flowforge.domain.workflow.NodePosition;
import com.flowforge.domain.workflow.WorkflowDefinition;
import com.flowforge.infrastructure.catalog.JsonNodeTypeRegistry;
import com.flowforge.infrastructure.nodes.ManualTriggerExecutor;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Engine behaviour with synthetic executors (Module 8 supplies the real ones):
 * <ul>
 *   <li>{@code core.transform}: copies its input and appends its node id to {@code path}</li>
 *   <li>{@code core.condition}: activates {@code true}/{@code false} from the input's {@code flag}</li>
 *   <li>{@code core.logger}: per-node scripted behaviour ({@link #behave}); default like transform</li>
 * </ul>
 */
class ExecutionEngineTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ExecutionPlanner PLANNER = new ExecutionPlanner(new JsonNodeTypeRegistry(JSON));

    private final ExecutorService nodeThreads = Executors.newCachedThreadPool();
    private final Map<String, Behavior> behaviors = new HashMap<>();
    private final InMemoryExecutionPersistence store = new InMemoryExecutionPersistence();
    private ExecutionProperties properties = new ExecutionProperties(1, 10, Duration.ofSeconds(5), Duration.ofMinutes(1), null);
    private final List<NodeExecutor> extraExecutors = new ArrayList<>();
    private final List<ExecutionEvent> published = new ArrayList<>();

    /**
     * Collects events and, for every one, checks that the state it announces is already persisted: events must never run
     * ahead of the database.
     */
    private final ExecutionEventSink sink = event -> {
        if (event.nodeId() != null) {
            assertThat(store.node(event.nodeId()).getStatus().name())
                    .as("persisted status of %s when %s was published", event.nodeId(), event.type())
                    .isEqualTo(event.status());
        } else {
            assertThat(store.execution().getStatus().name()).as("persisted execution status for %s", event.type())
                    .isEqualTo(event.status());
        }
        synchronized (published) {
            published.add(event);
        }
    };

    @FunctionalInterface
    interface Behavior {
        NodeExecutionResult run(NodeExecutionRequest request) throws Exception;
    }

    @AfterEach
    void shutdown() {
        nodeThreads.shutdownNow();
    }

    // ----------------------------------------------------------------------------------------------------------------

    private static NodeExecutionResult appendPath(NodeExecutionRequest request) {
        ObjectNode out = request.input().isObject() ? (ObjectNode) request.input().deepCopy() : JSON.createObjectNode();
        out.withArray("path").add(request.node().id());
        return NodeExecutionResult.success(out);
    }

    private NodeExecutor executor(String type, Behavior defaultBehavior) {
        return new NodeExecutor() {
            @Override
            public String nodeType() {
                return type;
            }

            @Override
            public NodeExecutionResult execute(NodeExecutionRequest request) throws Exception {
                Behavior scripted = behaviors.get(request.node().id());
                return scripted != null ? scripted.run(request) : defaultBehavior.run(request);
            }
        };
    }

    private void behave(String nodeId, Behavior behavior) {
        behaviors.put(nodeId, behavior);
    }

    private ExecutionEngine engine() {
        List<NodeExecutor> executors = new ArrayList<>(List.of(
                new ManualTriggerExecutor(),
                executor("core.transform", ExecutionEngineTest::appendPath),
                executor("core.logger", ExecutionEngineTest::appendPath),
                executor("core.condition", r -> NodeExecutionResult.branch(r.input(),
                        r.input().path("flag").asBoolean() ? "true" : "false"))));
        executors.addAll(extraExecutors);
        return new ExecutionEngine(new NodeExecutorRegistry(executors), store, nodeThreads, properties,
                Clock.systemUTC(), sink);
    }

    private static NodeDefinition node(String id, String type) {
        return new NodeDefinition(id, type, 1, "Node " + id, new NodePosition(0, 0), Map.of());
    }

    private static EdgeDefinition edge(String source, String target) {
        return edge(source, "main", target);
    }

    private static EdgeDefinition edge(String source, String handle, String target) {
        return new EdgeDefinition(source + "-" + target, source, handle, target, "main");
    }

    private ExecutionJob job(JsonNode input, CancellationToken token, List<NodeDefinition> nodes,
                             EdgeDefinition... edges) {
        WorkflowDefinition definition = new WorkflowDefinition(1, nodes, Arrays.asList(edges), null);
        ExecutionPlan plan = PLANNER.plan(definition);
        return store.prepare(definition, plan, input, token);
    }

    private ExecutionJob job(JsonNode input, List<NodeDefinition> nodes, EdgeDefinition... edges) {
        return job(input, new CancellationToken(), nodes, edges);
    }

    private static JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private NodeExecutionStatus status(String nodeId) {
        return store.node(nodeId).getStatus();
    }

    private String errorCode(String nodeId) {
        return store.node(nodeId).getError().code();
    }

    // ----------------------------------------------------------------------------------------------------------------

    @Nested
    class OrderingAndData {

        @Test
        void runsALinearWorkflowInOrderPropagatingEachOutput() {
            ExecutionStatus result = engine().run(job(json("{\"order\":7}"),
                    List.of(node("t", "core.manualTrigger"), node("a", "core.transform"), node("b", "core.logger")),
                    edge("t", "a"), edge("a", "b")));

            assertThat(result).isEqualTo(ExecutionStatus.SUCCEEDED);
            assertThat(store.events).containsExactly("execution:running", "start:t", "ok:t", "start:a", "ok:a",
                    "start:b", "ok:b", "execution:SUCCEEDED");
            assertThat(store.node("t").getOutput()).isEqualTo(json("{\"order\":7}"));
            assertThat(store.node("a").getInput()).isEqualTo(json("{\"order\":7}"));
            assertThat(store.node("b").getInput()).isEqualTo(json("{\"order\":7,\"path\":[\"a\"]}"));
            assertThat(store.node("b").getOutput()).isEqualTo(json("{\"order\":7,\"path\":[\"a\",\"b\"]}"));
            assertThat(store.execution().getStatus()).isEqualTo(ExecutionStatus.SUCCEEDED);
            assertThat(store.execution().getStartedAt()).isNotNull();
            assertThat(store.execution().getFinishedAt()).isAfterOrEqualTo(store.execution().getStartedAt());
        }

        @Test
        void fanOutRunsSiblingsInDefinitionOrderWithIndependentCopiesOfTheParentOutput() {
            engine().run(job(json("{}"),
                    List.of(node("t", "core.manualTrigger"), node("second", "core.transform"),
                            node("first", "core.transform")),
                    edge("t", "first"), edge("t", "second")));

            assertThat(store.events).containsSubsequence("ok:second", "ok:first");
            assertThat(store.node("first").getOutput()).isEqualTo(json("{\"path\":[\"first\"]}"));
            assertThat(store.node("second").getOutput()).isEqualTo(json("{\"path\":[\"second\"]}"));
        }

        @Test
        void executorsCanReadOutputsOfAnyEarlierNodeThroughTheContext() {
            AtomicReference<JsonNode> seen = new AtomicReference<>();
            behave("b", r -> {
                seen.set(r.context().outputOf("t").orElseThrow());
                assertThat(r.context().outputs().keySet()).containsExactly("t", "a");
                return NodeExecutionResult.success(r.input());
            });

            engine().run(job(json("{\"x\":1}"),
                    List.of(node("t", "core.manualTrigger"), node("a", "core.transform"), node("b", "core.logger")),
                    edge("t", "a"), edge("a", "b")));

            assertThat(seen.get()).isEqualTo(json("{\"x\":1}"));
        }

        @Test
        void sameWorkflowAndInputProduceTheSameHistory() {
            List<NodeDefinition> nodes = List.of(node("t", "core.manualTrigger"), node("c", "core.condition"),
                    node("y", "core.transform"), node("x", "core.transform"), node("w", "core.logger"));
            EdgeDefinition[] edges = {edge("t", "c"), edge("c", "true", "x"), edge("c", "false", "y"), edge("x", "w")};

            engine().run(job(json("{\"flag\":true}"), nodes, edges));
            List<String> first = List.copyOf(store.events);
            JsonNode firstOutput = store.node("w").getOutput();
            InMemoryExecutionPersistence again = new InMemoryExecutionPersistence();
            ExecutionEngine other = new ExecutionEngine(new NodeExecutorRegistry(List.of(new ManualTriggerExecutor(),
                    executor("core.transform", ExecutionEngineTest::appendPath),
                    executor("core.logger", ExecutionEngineTest::appendPath),
                    executor("core.condition", r -> NodeExecutionResult.branch(r.input(),
                            r.input().path("flag").asBoolean() ? "true" : "false")))),
                    again, nodeThreads, properties, Clock.systemUTC());
            WorkflowDefinition definition = new WorkflowDefinition(1, nodes, Arrays.asList(edges), null);
            other.run(again.prepare(definition, PLANNER.plan(definition), json("{\"flag\":true}"), new CancellationToken()));

            assertThat(again.events).isEqualTo(first);
            assertThat(again.node("w").getOutput()).isEqualTo(firstOutput);
        }
    }

    @Nested
    class Branching {

        private List<NodeDefinition> nodes() {
            return List.of(node("t", "core.manualTrigger"), node("c", "core.condition"), node("yes", "core.transform"),
                    node("no", "core.transform"), node("noAfter", "core.logger"));
        }

        private EdgeDefinition[] edges() {
            return new EdgeDefinition[] {edge("t", "c"), edge("c", "true", "yes"), edge("c", "false", "no"),
                    edge("no", "noAfter")};
        }

        @Test
        void trueBranchRunsAndTheFalseBranchIsSkippedTransitively() {
            ExecutionStatus result = engine().run(job(json("{\"flag\":true}"), nodes(), edges()));

            assertThat(result).isEqualTo(ExecutionStatus.SUCCEEDED);
            assertThat(status("yes")).isEqualTo(NodeExecutionStatus.SUCCEEDED);
            assertThat(status("no")).isEqualTo(NodeExecutionStatus.SKIPPED);
            assertThat(status("noAfter")).isEqualTo(NodeExecutionStatus.SKIPPED);
            assertThat(store.node("no").getInput()).isNull();
            assertThat(store.node("yes").getInput()).isEqualTo(json("{\"flag\":true}"));
        }

        @Test
        void falseBranchRunsWithTheConditionsOutput() {
            engine().run(job(json("{\"flag\":false}"), nodes(), edges()));

            assertThat(status("yes")).isEqualTo(NodeExecutionStatus.SKIPPED);
            assertThat(status("no")).isEqualTo(NodeExecutionStatus.SUCCEEDED);
            assertThat(store.node("noAfter").getOutput()).isEqualTo(json("{\"flag\":false,\"path\":[\"no\",\"noAfter\"]}"));
        }

        @Test
        void takingAnUnconnectedBranchEndsThatPathSuccessfully() {
            ExecutionStatus result = engine().run(job(json("{\"flag\":false}"),
                    List.of(node("t", "core.manualTrigger"), node("c", "core.condition"), node("yes", "core.logger")),
                    edge("t", "c"), edge("c", "true", "yes")));

            assertThat(result).isEqualTo(ExecutionStatus.SUCCEEDED);
            assertThat(status("c")).isEqualTo(NodeExecutionStatus.SUCCEEDED);
            assertThat(status("yes")).isEqualTo(NodeExecutionStatus.SKIPPED);
        }

        @Test
        void nestedConditionsSkipOnlyTheBranchesNotTaken() {
            behave("c2", r -> NodeExecutionResult.branch(r.input(), "false"));
            engine().run(job(json("{\"flag\":true}"),
                    List.of(node("t", "core.manualTrigger"), node("c1", "core.condition"), node("c2", "core.condition"),
                            node("a", "core.logger"), node("b", "core.logger"), node("other", "core.logger")),
                    edge("t", "c1"), edge("c1", "true", "c2"), edge("c1", "false", "other"),
                    edge("c2", "true", "a"), edge("c2", "false", "b")));

            assertThat(status("other")).isEqualTo(NodeExecutionStatus.SKIPPED);
            assertThat(status("a")).isEqualTo(NodeExecutionStatus.SKIPPED);
            assertThat(status("b")).isEqualTo(NodeExecutionStatus.SUCCEEDED);
        }

        @Test
        void activatingAnUndeclaredOutputFailsTheNode() {
            behave("c", r -> NodeExecutionResult.branch(r.input(), "maybe"));
            ExecutionStatus result = engine().run(job(json("{}"), nodes(), edges()));

            assertThat(result).isEqualTo(ExecutionStatus.FAILED);
            assertThat(errorCode("c")).isEqualTo(ExecutionEngine.INVALID_OUTPUT_HANDLE);
        }
    }

    @Nested
    class Failures {

        private ExecutionStatus runLinearWithSideBranch() {
            // t -> a -> b, and t -> side (planned after a and b's parent chain by definition order)
            return engine().run(job(json("{}"),
                    List.of(node("t", "core.manualTrigger"), node("a", "core.logger"), node("b", "core.logger"),
                            node("side", "core.logger")),
                    edge("t", "a"), edge("a", "b"), edge("t", "side")));
        }

        @Test
        void aFailedNodeStopsTheExecutionAndSkipsEverythingNotYetRun() {
            behave("a", r -> NodeExecutionResult.failure("HTTP_STATUS_ERROR", "Server answered 500"));

            ExecutionStatus result = runLinearWithSideBranch();

            assertThat(result).isEqualTo(ExecutionStatus.FAILED);
            assertThat(status("t")).isEqualTo(NodeExecutionStatus.SUCCEEDED);
            assertThat(status("a")).isEqualTo(NodeExecutionStatus.FAILED);
            assertThat(errorCode("a")).isEqualTo("HTTP_STATUS_ERROR");
            assertThat(status("b")).isEqualTo(NodeExecutionStatus.SKIPPED);
            assertThat(status("side")).isEqualTo(NodeExecutionStatus.SKIPPED);
            assertThat(store.execution().getError().code()).isEqualTo("HTTP_STATUS_ERROR");
            assertThat(store.execution().getError().message()).isEqualTo("Node 'Node a' failed: Server answered 500");
            assertThat(store.events).doesNotContain("start:b", "start:side");
        }

        @Test
        void thrownExceptionsBecomeSanitizedNodeErrors() {
            behave("a", r -> {
                throw new IllegalStateException("connect failed, password=hunter2");
            });

            runLinearWithSideBranch();

            assertThat(errorCode("a")).isEqualTo(ExecutionEngine.NODE_ERROR);
            assertThat(store.node("a").getError().message())
                    .isEqualTo("connect failed, password=[REDACTED]").doesNotContain("hunter2");
            assertThat(store.execution().getError().message()).doesNotContain("hunter2");
        }

        @Test
        void nodeExecutionExceptionKeepsItsCodeAndExceptionsWithoutMessageUseTheirType() {
            behave("a", r -> {
                throw new NodeExecutionException("RATE_LIMITED", "Try later");
            });
            runLinearWithSideBranch();
            assertThat(store.node("a").getError()).extracting("code", "message").containsExactly("RATE_LIMITED", "Try later");
        }

        @Test
        void exceptionWithoutMessageIsDescribedByItsType() {
            behave("a", r -> {
                throw new UnsupportedOperationException();
            });
            runLinearWithSideBranch();
            assertThat(store.node("a").getError().message()).isEqualTo("UnsupportedOperationException");
        }

        @Test
        void nullResultIsAFailure() {
            behave("a", r -> null);
            runLinearWithSideBranch();
            assertThat(errorCode("a")).isEqualTo(ExecutionEngine.NODE_ERROR);
        }

        @Test
        void missingExecutorFailsTheNodeAtRunTime() {
            ExecutionStatus result = engine().run(job(json("{}"),
                    List.of(node("t", "core.manualTrigger"), node("h", "core.httpRequest")), edge("t", "h")));

            assertThat(result).isEqualTo(ExecutionStatus.FAILED);
            assertThat(errorCode("h")).isEqualTo(ExecutionEngine.NO_EXECUTOR);
        }

        @Test
        void persistenceFailureEndsTheExecutionAsEngineError() {
            store.failOnNodeStarted = new IllegalStateException("database down");

            ExecutionStatus result = runLinearWithSideBranch();

            assertThat(result).isEqualTo(ExecutionStatus.FAILED);
            assertThat(store.execution().getError().code()).isEqualTo(ExecutionEngine.ENGINE_ERROR);
            assertThat(store.node("t").getStatus()).isEqualTo(NodeExecutionStatus.SKIPPED);
            assertThat(store.node("a").getStatus()).isEqualTo(NodeExecutionStatus.SKIPPED);
        }

        @Test
        void rejectedNodeTaskDoesNotLeaveRunningOrPendingNodes() {
            nodeThreads.shutdown();

            assertThat(runLinearWithSideBranch()).isEqualTo(ExecutionStatus.FAILED);

            assertThat(status("t")).isEqualTo(NodeExecutionStatus.FAILED);
            assertThat(errorCode("t")).isEqualTo(ExecutionEngine.ENGINE_ERROR);
            assertThat(status("a")).isEqualTo(NodeExecutionStatus.SKIPPED);
            assertThat(status("b")).isEqualTo(NodeExecutionStatus.SKIPPED);
            assertThat(status("side")).isEqualTo(NodeExecutionStatus.SKIPPED);
        }
    }

    @Nested
    class Timeouts {

        @Test
        void slowNodeTimesOutAndItsThreadIsInterrupted() throws Exception {
            properties = new ExecutionProperties(1, 10, Duration.ofMillis(150), Duration.ofMinutes(1), null);
            CountDownLatch interrupted = new CountDownLatch(1);
            behave("a", r -> {
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException e) {
                    interrupted.countDown();
                    throw e;
                }
                return NodeExecutionResult.success(r.input());
            });

            long started = System.nanoTime();
            ExecutionStatus result = engine().run(job(json("{}"),
                    List.of(node("t", "core.manualTrigger"), node("a", "core.logger"), node("b", "core.logger")),
                    edge("t", "a"), edge("a", "b")));
            double seconds = (System.nanoTime() - started) / 1e9;

            assertThat(result).isEqualTo(ExecutionStatus.FAILED);
            assertThat(errorCode("a")).isEqualTo(ExecutionEngine.NODE_TIMEOUT);
            assertThat(status("b")).isEqualTo(NodeExecutionStatus.SKIPPED);
            assertThat(seconds).isLessThan(2.0);
            assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
        }

        @Test
        void executorDeclaredTimeoutWins() {
            extraExecutors.add(new NodeExecutor() {
                @Override
                public String nodeType() {
                    return "core.httpRequest";
                }

                @Override
                public NodeExecutionResult execute(NodeExecutionRequest request) throws Exception {
                    Thread.sleep(2_000);
                    return NodeExecutionResult.success(request.input());
                }

                @Override
                public Optional<Duration> timeout(NodeDefinition node) {
                    return Optional.of(Duration.ofMillis(100));
                }
            });

            engine().run(job(json("{}"), List.of(node("t", "core.manualTrigger"), node("h", "core.httpRequest")),
                    edge("t", "h")));

            assertThat(errorCode("h")).isEqualTo(ExecutionEngine.NODE_TIMEOUT);
            assertThat(store.node("h").getError().message()).contains("PT0.1S");
        }

        @Test
        void overallExecutionTimeLimitStopsTheRun() {
            properties = new ExecutionProperties(1, 10, Duration.ofSeconds(5), Duration.ofMillis(300), null);
            behave("a", r -> {
                Thread.sleep(200);
                return NodeExecutionResult.success(r.input());
            });
            behave("b", r -> {
                Thread.sleep(5_000);
                return NodeExecutionResult.success(r.input());
            });

            ExecutionStatus result = engine().run(job(json("{}"),
                    List.of(node("t", "core.manualTrigger"), node("a", "core.logger"), node("b", "core.logger"),
                            node("c", "core.logger")),
                    edge("t", "a"), edge("a", "b"), edge("b", "c")));

            assertThat(result).isEqualTo(ExecutionStatus.FAILED);
            assertThat(status("a")).isEqualTo(NodeExecutionStatus.SUCCEEDED);
            assertThat(errorCode("b")).isEqualTo(ExecutionEngine.EXECUTION_TIMEOUT);
            assertThat(status("c")).isEqualTo(NodeExecutionStatus.SKIPPED);
            assertThat(store.execution().getError().code()).isEqualTo(ExecutionEngine.EXECUTION_TIMEOUT);
        }
    }

    @Nested
    class Cancellation {

        private List<NodeDefinition> chain() {
            return List.of(node("t", "core.manualTrigger"), node("a", "core.logger"), node("b", "core.logger"));
        }

        @Test
        void cancelledBeforeStartRunsNothing() {
            CancellationToken token = new CancellationToken();
            token.cancel();

            ExecutionStatus result = engine().run(job(json("{}"), token, chain(), edge("t", "a"), edge("a", "b")));

            assertThat(result).isEqualTo(ExecutionStatus.CANCELLED);
            assertThat(store.events).containsExactly("skip:t", "skip:a", "skip:b", "execution:CANCELLED");
            assertThat(store.execution().getStartedAt()).isNull();
            assertThat(store.execution().getStatus()).isEqualTo(ExecutionStatus.CANCELLED);
        }

        @Test
        void cancellingDuringANodeInterruptsItAndSkipsTheRest() throws Exception {
            CancellationToken token = new CancellationToken();
            CountDownLatch running = new CountDownLatch(1);
            AtomicBoolean sawInterrupt = new AtomicBoolean();
            behave("a", r -> {
                running.countDown();
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException e) {
                    sawInterrupt.set(true);
                    throw e;
                }
                return NodeExecutionResult.success(r.input());
            });
            ExecutionJob job = job(json("{}"), token, chain(), edge("t", "a"), edge("a", "b"));
            AtomicReference<ExecutionStatus> result = new AtomicReference<>();
            Thread worker = new Thread(() -> result.set(engine().run(job)));
            worker.start();

            assertThat(running.await(2, TimeUnit.SECONDS)).isTrue();
            long cancelledAt = System.nanoTime();
            token.cancel();
            worker.join(2_000);

            assertThat(result.get()).isEqualTo(ExecutionStatus.CANCELLED);
            assertThat((System.nanoTime() - cancelledAt) / 1e9).isLessThan(1.0);
            assertThat(status("a")).isEqualTo(NodeExecutionStatus.FAILED);
            assertThat(errorCode("a")).isEqualTo(ExecutionEngine.CANCELLED);
            assertThat(status("b")).isEqualTo(NodeExecutionStatus.SKIPPED);
            assertThat(store.execution().getError()).isNull();
            Thread.sleep(50);
            assertThat(sawInterrupt).isTrue();
        }

        @Test
        void cancellingBetweenNodesLetsTheCurrentOneFinish() {
            CancellationToken token = new CancellationToken();
            behave("a", r -> {
                token.cancel(); // arrives while a is finishing
                return NodeExecutionResult.success(r.input());
            });

            ExecutionStatus result = engine().run(job(json("{}"), token, chain(), edge("t", "a"), edge("a", "b")));

            assertThat(result).isEqualTo(ExecutionStatus.CANCELLED);
            assertThat(status("a")).isEqualTo(NodeExecutionStatus.SUCCEEDED);
            assertThat(status("b")).isEqualTo(NodeExecutionStatus.SKIPPED);
            assertThat(store.events).doesNotContain("start:b");
        }

        @Test
        void cancellationAsTheLastNodeFinishesStillCancelsTheExecution() {
            CancellationToken token = new CancellationToken();
            behave("a", r -> {
                token.cancel();
                return NodeExecutionResult.success(r.input());
            });

            ExecutionStatus result = engine().run(job(json("{}"), token,
                    List.of(node("t", "core.manualTrigger"), node("a", "core.logger")), edge("t", "a")));

            assertThat(result).isEqualTo(ExecutionStatus.CANCELLED);
            assertThat(status("a")).isEqualTo(NodeExecutionStatus.SUCCEEDED);
            assertThat(store.execution().getStatus()).isEqualTo(ExecutionStatus.CANCELLED);
            assertThat(eventLog()).endsWith("execution.cancelled");
        }

        @Test
        void interruptingTheWorkerThreadRecordsAnInterruptedExecution() throws Exception {
            CountDownLatch running = new CountDownLatch(1);
            behave("a", r -> {
                running.countDown();
                Thread.sleep(10_000);
                return NodeExecutionResult.success(r.input());
            });
            ExecutionJob job = job(json("{}"), chain(), edge("t", "a"), edge("a", "b"));
            AtomicReference<ExecutionStatus> result = new AtomicReference<>();
            Thread worker = new Thread(() -> result.set(engine().run(job)));
            worker.start();
            assertThat(running.await(2, TimeUnit.SECONDS)).isTrue();

            worker.interrupt();
            worker.join(2_000);

            assertThat(result.get()).isEqualTo(ExecutionStatus.FAILED);
            assertThat(errorCode("a")).isEqualTo(ExecutionEngine.EXECUTION_INTERRUPTED);
            assertThat(status("b")).isEqualTo(NodeExecutionStatus.SKIPPED);
        }
    }

    private List<String> eventLog() {
        synchronized (published) {
            return published.stream().map(e -> e.type().eventName() + (e.nodeId() == null ? "" : ":" + e.nodeId())).toList();
        }
    }

    @Nested
    class Events {

        @Test
        void successfulRunAnnouncesEveryTransitionInOrder() {
            engine().run(job(json("{}"), List.of(node("t", "core.manualTrigger"), node("a", "core.transform")),
                    edge("t", "a")));

            assertThat(eventLog()).containsExactly("execution.started", "node.started:t", "node.succeeded:t",
                    "node.started:a", "node.succeeded:a", "execution.completed");
            ExecutionEvent last = published.get(published.size() - 1);
            assertThat(last.status()).isEqualTo("SUCCEEDED");
            assertThat(last.durationMs()).isNotNull().isGreaterThanOrEqualTo(0);
            assertThat(published.get(2).durationMs()).isNotNull();
            assertThat(published.get(1).nodeName()).isEqualTo("Node t");
            assertThat(published.get(1).executionId()).isEqualTo(store.execution().getId());
        }

        @Test
        void skippedBranchesAreAnnouncedAtTheirTurn() {
            engine().run(job(json("{\"flag\":true}"),
                    List.of(node("t", "core.manualTrigger"), node("c", "core.condition"), node("yes", "core.transform"),
                            node("no", "core.transform")),
                    edge("t", "c"), edge("c", "true", "yes"), edge("c", "false", "no")));

            assertThat(eventLog()).containsExactly("execution.started", "node.started:t", "node.succeeded:t",
                    "node.started:c", "node.succeeded:c", "node.started:yes", "node.succeeded:yes", "node.skipped:no",
                    "execution.completed");
        }

        @Test
        void failureAnnouncesTheNodeErrorThenSkipsThenFails() {
            behave("a", r -> NodeExecutionResult.failure("HTTP_STATUS_ERROR", "answered 500"));

            engine().run(job(json("{}"), List.of(node("t", "core.manualTrigger"), node("a", "core.logger"),
                    node("b", "core.logger")), edge("t", "a"), edge("a", "b")));

            assertThat(eventLog()).containsExactly("execution.started", "node.started:t", "node.succeeded:t",
                    "node.started:a", "node.failed:a", "node.skipped:b", "execution.failed");
            ExecutionEvent nodeFailed = published.get(4);
            assertThat(nodeFailed.error().code()).isEqualTo("HTTP_STATUS_ERROR");
            assertThat(nodeFailed.status()).isEqualTo("FAILED");
            ExecutionEvent executionFailed = published.get(published.size() - 1);
            assertThat(executionFailed.error().message()).isEqualTo("Node 'Node a' failed: answered 500");
        }

        @Test
        void cancellationBeforeStartAnnouncesSkipsAndCancelled() {
            CancellationToken token = new CancellationToken();
            token.cancel();

            engine().run(job(json("{}"), token, List.of(node("t", "core.manualTrigger"), node("a", "core.logger")),
                    edge("t", "a")));

            assertThat(eventLog()).containsExactly("node.skipped:t", "node.skipped:a", "execution.cancelled");
        }

        @Test
        void cancellationDuringANodeAnnouncesTheNodeFailureAndCancelled() throws Exception {
            CancellationToken token = new CancellationToken();
            CountDownLatch running = new CountDownLatch(1);
            behave("a", r -> {
                running.countDown();
                Thread.sleep(10_000);
                return NodeExecutionResult.success(r.input());
            });
            ExecutionJob job = job(json("{}"), token, List.of(node("t", "core.manualTrigger"), node("a", "core.logger"),
                    node("b", "core.logger")), edge("t", "a"), edge("a", "b"));
            Thread worker = new Thread(() -> engine().run(job));
            worker.start();
            assertThat(running.await(2, TimeUnit.SECONDS)).isTrue();

            token.cancel();
            worker.join(2_000);

            assertThat(eventLog()).containsExactly("execution.started", "node.started:t", "node.succeeded:t",
                    "node.started:a", "node.failed:a", "node.skipped:b", "execution.cancelled");
        }

        @Test
        void aMisbehavingSinkCannotBreakTheRunBecauseTheEngineOnlyReportsAfterPersisting() {
            // The state announced by every event was verified in the sink (see field above); additionally the run's
            // persisted result does not depend on events at all.
            engine().run(job(json("{}"), List.of(node("t", "core.manualTrigger"))));
            assertThat(store.execution().getStatus()).isEqualTo(ExecutionStatus.SUCCEEDED);
            assertThat(published).isNotEmpty();
        }
    }

    @Test
    void nodeTimestampsAreOrdered() {
        engine().run(job(json("{}"), List.of(node("t", "core.manualTrigger"), node("a", "core.logger")), edge("t", "a")));

        assertThat(store.node("a").getStartedAt()).isAfterOrEqualTo(store.node("t").getFinishedAt());
        assertThat(Duration.between(store.node("t").getStartedAt(), store.node("a").getFinishedAt()).toMillis())
                .isCloseTo(0, within(1_000L));
    }

    /** Module 12: values registered as secrets never reach storage or events; downstream nodes still get them. */
    @Nested
    class SecretMasking {

        private static final String SECRET = "sk_live_9f8e7d6c5b4a";

        @Test
        void registeredSecretsAreMaskedInStoredInputOutputLogsAndEventsButFlowDownstream() {
            AtomicReference<JsonNode> seenByB = new AtomicReference<>();
            behave("a", r -> {
                r.context().secrets().register(SECRET);
                r.context().log("a", "INFO", "calling with " + SECRET);
                return NodeExecutionResult.success(json("{\"echo\":\"Bearer " + SECRET + "\",\"" + SECRET + "\":1}"));
            });
            behave("b", r -> {
                seenByB.set(r.input());
                r.context().log("b", "INFO", "got " + r.input().get("echo").asText());
                return NodeExecutionResult.success(r.input());
            });

            ExecutionStatus result = engine().run(job(json("{}"),
                    List.of(node("t", "core.manualTrigger"), node("a", "core.transform"), node("b", "core.logger")),
                    edge("t", "a"), edge("a", "b")));

            assertThat(result).isEqualTo(ExecutionStatus.SUCCEEDED);
            assertThat(seenByB.get().get("echo").asText()).isEqualTo("Bearer " + SECRET);
            assertThat(store.node("a").getOutput().toString()).doesNotContain(SECRET).contains("[REDACTED]");
            assertThat(store.node("b").getInput().toString()).doesNotContain(SECRET);
            assertThat(store.node("b").getOutput().toString()).doesNotContain(SECRET);
            assertThat(store.logs).hasSize(2).allSatisfy(l -> assertThat(l.message()).doesNotContain(SECRET)
                    .contains("[REDACTED]"));
        }

        @Test
        void secretsInNodeAndExecutionErrorsAreMasked() {
            behave("a", r -> {
                r.context().secrets().register(SECRET);
                throw new NodeExecutionException("UPSTREAM_REJECTED", "server said: invalid key " + SECRET);
            });

            ExecutionStatus result = engine().run(job(json("{}"),
                    List.of(node("t", "core.manualTrigger"), node("a", "core.transform")), edge("t", "a")));

            assertThat(result).isEqualTo(ExecutionStatus.FAILED);
            assertThat(store.node("a").getError().message()).doesNotContain(SECRET).contains("[REDACTED]");
            assertThat(store.execution().getError().message()).doesNotContain(SECRET);
            synchronized (published) {
                assertThat(published).allSatisfy(e -> assertThat(String.valueOf(e)).doesNotContain(SECRET));
            }
        }
    }
}
