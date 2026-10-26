package com.flowforge.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.api.dto.ExecutionResponse;
import com.flowforge.application.execution.ExecutionEvent;
import com.flowforge.application.execution.ExecutionEventBroadcaster;
import com.flowforge.application.execution.ExecutionPersistenceService;
import com.flowforge.domain.execution.ExecutionStatus;
import com.flowforge.infrastructure.persistence.WorkflowExecutionEntity;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Serves one Server-Sent Events connection per call.
 *
 * <p>Protocol: (1) subscribe to live events, (2) send a {@code snapshot} event read from the database (execution status
 * and every node's status, no payloads), (3) forward live events until a terminal one, then end the stream. A
 * client that connects, reconnects or missed events therefore always starts from persisted state; {@code Last-Event-ID}
 * is not needed. If the execution already finished, the stream is the snapshot only. A {@code : keepalive} comment is sent
 * every {@value #HEARTBEAT_SECONDS} s so proxies keep the connection and dead clients are detected.
 *
 * <p>The loop runs on a virtual thread, so a slow client can never block the engine.
 */
@Component
public class ExecutionEventStream {

    static final int HEARTBEAT_SECONDS = 15;
    static final Duration MAX_STREAM_DURATION = Duration.ofMinutes(30);
    public static final String SNAPSHOT = "snapshot";

    private static final Logger log = LoggerFactory.getLogger(ExecutionEventStream.class);

    private final ExecutionEventBroadcaster broadcaster;
    private final ExecutionPersistenceService persistence;
    private final ObjectMapper objectMapper;

    public ExecutionEventStream(ExecutionEventBroadcaster broadcaster, ExecutionPersistenceService persistence,
                                ObjectMapper objectMapper) {
        this.broadcaster = broadcaster;
        this.persistence = persistence;
        this.objectMapper = objectMapper;
    }

    /** Persisted state of an execution without payloads: what a client needs to (re)build its live view. */
    public record Snapshot(UUID executionId, String status, ExecutionResponse.Error error, Instant startedAt,
                           Instant finishedAt, Long durationMs, List<NodeState> nodes, Instant at) {

        public record NodeState(String nodeId, String nodeName, int sequence, String status, Long durationMs,
                                ExecutionResponse.Error error) {
        }
    }

    /** @throws com.flowforge.application.ResourceNotFoundException (404) if the execution does not exist */
    public SseEmitter open(UUID executionId) {
        ExecutionEventBroadcaster.Subscription subscription = broadcaster.subscribe(executionId);
        Snapshot snapshot;
        try {
            snapshot = snapshotOf(executionId);
        } catch (RuntimeException e) {
            subscription.close();
            throw e;
        }
        SseEmitter emitter = new SseEmitter(MAX_STREAM_DURATION.toMillis() + 60_000);
        emitter.onCompletion(subscription::close);
        emitter.onTimeout(subscription::close);
        emitter.onError(error -> subscription.close());
        Thread.ofVirtual().name("ff-sse-" + executionId).start(() -> pump(emitter, subscription, snapshot));
        return emitter;
    }

    Snapshot snapshotOf(UUID executionId) {
        var view = persistence.find(executionId);
        WorkflowExecutionEntity e = view.execution();
        Map<String, String> names = ExecutionResponse.names(e);
        List<Snapshot.NodeState> nodes = view.nodes().stream().map(n -> new Snapshot.NodeState(n.getNodeId(),
                names.getOrDefault(n.getNodeId(), n.getNodeId()), n.getSequence(), n.getStatus().name(),
                ExecutionResponse.millis(n.getStartedAt(), n.getFinishedAt()),
                n.getError() == null ? null : new ExecutionResponse.Error(n.getError().code(), n.getError().message())))
                .toList();
        return new Snapshot(e.getId(), e.getStatus().name(),
                e.getError() == null ? null : new ExecutionResponse.Error(e.getError().code(), e.getError().message()),
                e.getStartedAt(), e.getFinishedAt(), ExecutionResponse.millis(e.getStartedAt(), e.getFinishedAt()), nodes,
                Instant.now());
    }

    private void pump(SseEmitter emitter, ExecutionEventBroadcaster.Subscription subscription, Snapshot snapshot) {
        try {
            send(emitter, SseEmitter.event().name(SNAPSHOT).data(objectMapper.writeValueAsString(snapshot),
                    MediaType.APPLICATION_JSON));
            if (ExecutionStatus.valueOf(snapshot.status()).isTerminal()) {
                emitter.complete();
                return;
            }
            Instant giveUp = Instant.now().plus(MAX_STREAM_DURATION);
            while (!subscription.isClosed() && Instant.now().isBefore(giveUp)) {
                var next = subscription.next(Duration.ofSeconds(HEARTBEAT_SECONDS));
                if (subscription.isOverflowed()) {
                    log.debug("Dropping slow SSE subscriber of execution {}", snapshot.executionId());
                    break; // the client reconnects and resynchronizes from a fresh snapshot
                }
                if (next.isPresent()) {
                    ExecutionEvent event = next.get();
                    send(emitter, SseEmitter.event().id(Long.toString(event.seq())).name(event.type().eventName())
                            .data(objectMapper.writeValueAsString(event), MediaType.APPLICATION_JSON));
                    if (event.isTerminal()) {
                        break;
                    }
                } else if (!subscription.isClosed()) {
                    emitter.send(SseEmitter.event().comment("keepalive"));
                }
            }
            emitter.complete();
        } catch (JsonProcessingException | RuntimeException e) {
            log.warn("SSE stream for execution {} failed", snapshot.executionId(), e);
            emitter.completeWithError(e);
        } catch (IOException e) {
            // client disconnected
            emitter.complete();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            emitter.complete();
        } finally {
            subscription.close();
        }
    }

    private static void send(SseEmitter emitter, SseEmitter.SseEventBuilder event) throws IOException {
        emitter.send(event);
    }
}
