package com.flowforge.application.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowforge.domain.execution.ExecutionStatus;
import com.flowforge.domain.execution.NodeExecutionStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExecutionEventBroadcasterTest {

    private static final Duration SHORT = Duration.ofMillis(50);
    private final ExecutionEventBroadcaster broadcaster = new ExecutionEventBroadcaster();

    private static ExecutionEvent nodeEvent(UUID id, String node) {
        return ExecutionEvent.node(ExecutionEvent.Type.NODE_STARTED, id, node, node, NodeExecutionStatus.RUNNING,
                Instant.now(), null, null);
    }

    private static ExecutionEvent terminal(UUID id) {
        return ExecutionEvent.execution(ExecutionEvent.Type.EXECUTION_COMPLETED, id, ExecutionStatus.SUCCEEDED,
                Instant.now(), 5L, null);
    }

    private List<ExecutionEvent> drain(ExecutionEventBroadcaster.Subscription subscription) throws Exception {
        List<ExecutionEvent> out = new ArrayList<>();
        for (var next = subscription.next(SHORT); next.isPresent(); next = subscription.next(SHORT)) {
            out.add(next.get());
        }
        return out;
    }

    @Test
    void deliversEventsInOrderWithIncreasingSequenceNumbers() throws Exception {
        UUID id = UUID.randomUUID();
        try (var subscription = broadcaster.subscribe(id)) {
            broadcaster.publish(nodeEvent(id, "a"));
            broadcaster.publish(nodeEvent(id, "b"));
            broadcaster.publish(terminal(id));

            List<ExecutionEvent> events = drain(subscription);

            assertThat(events).extracting(ExecutionEvent::nodeId).containsExactly("a", "b", null);
            assertThat(events).extracting(ExecutionEvent::seq).containsExactly(1L, 2L, 3L);
            assertThat(events.get(2).isTerminal()).isTrue();
        }
    }

    @Test
    void everySubscriberOfAnExecutionGetsEveryEventAndOtherExecutionsAreIsolated() throws Exception {
        UUID one = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        try (var a = broadcaster.subscribe(one); var b = broadcaster.subscribe(one); var c = broadcaster.subscribe(other)) {
            broadcaster.publish(nodeEvent(one, "x"));

            assertThat(drain(a)).hasSize(1);
            assertThat(drain(b)).hasSize(1);
            assertThat(drain(c)).isEmpty();
        }
    }

    @Test
    void publishingWithoutSubscribersDoesNothingAndKeepsNoState() {
        UUID id = UUID.randomUUID();
        broadcaster.publish(nodeEvent(id, "a"));
        broadcaster.publish(terminal(id));

        assertThat(broadcaster.channelCount()).isZero();
        assertThat(broadcaster.subscriptionCount()).isZero();
    }

    @Test
    void lateSubscribersOnlySeeLaterEvents() throws Exception {
        UUID id = UUID.randomUUID();
        try (var early = broadcaster.subscribe(id)) {
            broadcaster.publish(nodeEvent(id, "before"));
            try (var late = broadcaster.subscribe(id)) {
                broadcaster.publish(nodeEvent(id, "after"));

                assertThat(drain(early)).extracting(ExecutionEvent::nodeId).containsExactly("before", "after");
                assertThat(drain(late)).extracting(ExecutionEvent::nodeId).containsExactly("after");
            }
        }
    }

    @Test
    void channelsAreRemovedAfterTheTerminalEventAndWhenTheLastSubscriberLeaves() throws Exception {
        UUID finished = UUID.randomUUID();
        var subscription = broadcaster.subscribe(finished);
        broadcaster.publish(terminal(finished));
        assertThat(broadcaster.channelCount()).isZero();
        assertThat(drain(subscription)).hasSize(1); // still readable after the channel was dropped
        subscription.close();
        assertThat(broadcaster.subscriptionCount()).isZero();

        UUID abandoned = UUID.randomUUID();
        broadcaster.subscribe(abandoned).close();
        assertThat(broadcaster.channelCount()).isZero();
        assertThat(broadcaster.subscriptionCount()).isZero();
    }

    @Test
    void closingTwiceIsHarmlessAndWakesAWaitingConsumer() throws Exception {
        UUID id = UUID.randomUUID();
        var subscription = broadcaster.subscribe(id);
        Thread closer = new Thread(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
                // ignore
            }
            subscription.close();
        });
        closer.start();

        long started = System.nanoTime();
        assertThat(subscription.next(Duration.ofSeconds(5))).isEmpty();
        assertThat((System.nanoTime() - started) / 1e9).isLessThan(2.0);
        assertThat(subscription.isClosed()).isTrue();
        subscription.close();
        assertThat(broadcaster.subscriptionCount()).isZero();
    }

    @Test
    void aSubscriberThatFallsBehindIsDroppedInsteadOfBlockingTheEngine() throws Exception {
        UUID id = UUID.randomUUID();
        var slow = broadcaster.subscribe(id);
        var healthy = broadcaster.subscribe(id);

        long started = System.nanoTime();
        for (int i = 0; i < ExecutionEventBroadcaster.QUEUE_CAPACITY + 10; i++) {
            broadcaster.publish(nodeEvent(id, "n" + i));
            if (i % 50 == 0) {
                drain(healthy);
            }
        }
        assertThat((System.nanoTime() - started) / 1e9).isLessThan(1.0); // publishing never waited

        assertThat(slow.isOverflowed()).isTrue();
        assertThat(slow.next(SHORT)).isEmpty();
        assertThat(healthy.isOverflowed()).isFalse();
        slow.close();
        healthy.close();
    }

    @Test
    void connectionCountIsCapped() {
        UUID id = UUID.randomUUID();
        List<ExecutionEventBroadcaster.Subscription> open = new ArrayList<>();
        for (int i = 0; i < ExecutionEventBroadcaster.MAX_SUBSCRIPTIONS; i++) {
            open.add(broadcaster.subscribe(id));
        }

        assertThatThrownBy(() -> broadcaster.subscribe(id)).isInstanceOf(ExecutionCapacityException.class);
        assertThat(broadcaster.subscriptionCount()).isEqualTo(ExecutionEventBroadcaster.MAX_SUBSCRIPTIONS);
        open.get(0).close();
        broadcaster.subscribe(id).close();
        open.forEach(ExecutionEventBroadcaster.Subscription::close);
    }
}
