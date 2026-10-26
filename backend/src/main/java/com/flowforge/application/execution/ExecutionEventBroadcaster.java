package com.flowforge.application.execution;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * In-memory fan-out of execution events to live subscribers (the SSE connections). Single-instance only: events exist
 * solely in this JVM, nothing is stored or replayed, and a restart drops all subscriptions. That is acceptable because
 * the database is the source of truth: a (re)connecting client first receives a snapshot read from the database, and
 * the UI refetches persisted results.
 *
 * <p>Delivery never blocks the engine: each subscriber has a bounded queue; a subscriber that falls too far behind is
 * dropped ({@link Subscription#isOverflowed()}) and must reconnect, which resynchronizes it from the database.
 *
 * <p>Race-free recovery protocol for consumers: {@linkplain #subscribe subscribe} <em>first</em>, then read the persisted
 * state, then consume events. Events are published after their database write, so the snapshot is never older than an
 * event received afterwards; consumers must apply events idempotently (statuses only move forward).
 */
@Component
public class ExecutionEventBroadcaster implements ExecutionEventSink {

    public static final int QUEUE_CAPACITY = 256;
    public static final int MAX_SUBSCRIPTIONS = 200;

    private final Map<UUID, Channel> channels = new ConcurrentHashMap<>();
    private final AtomicInteger subscriptionCount = new AtomicInteger();

    private static final class Channel {
        final AtomicLong seq = new AtomicLong();
        final Set<Subscription> subscriptions = ConcurrentHashMap.newKeySet();
    }

    /** One consumer's view of an execution's events. */
    public final class Subscription implements AutoCloseable {

        private static final ExecutionEvent WAKE = new ExecutionEvent(-1, ExecutionEvent.Type.NODE_SKIPPED, null, null,
                null, null, null, null, null);

        private final UUID executionId;
        private final BlockingQueue<ExecutionEvent> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
        private volatile boolean overflowed;
        private volatile boolean closed;

        private Subscription(UUID executionId) {
            this.executionId = executionId;
        }

        /**
         * Waits up to {@code timeout} for the next event. Returns empty on timeout, and also when the subscription
         * was closed or overflowed (check {@link #isClosed()} / {@link #isOverflowed()}).
         */
        public Optional<ExecutionEvent> next(Duration timeout) throws InterruptedException {
            if (closed || overflowed) {
                return Optional.empty();
            }
            ExecutionEvent event = queue.poll(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            return event == null || event == WAKE ? Optional.empty() : Optional.of(event);
        }

        public boolean isOverflowed() {
            return overflowed;
        }

        public boolean isClosed() {
            return closed;
        }

        private void offer(ExecutionEvent event) {
            if (closed || overflowed) {
                return;
            }
            if (!queue.offer(event)) {
                overflowed = true;
                queue.clear();
                queue.offer(WAKE);
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            Channel channel = channels.get(executionId);
            if (channel != null) {
                channel.subscriptions.remove(this);
                if (channel.subscriptions.isEmpty()) {
                    channels.remove(executionId, channel);
                }
            }
            subscriptionCount.decrementAndGet();
            queue.clear();
            queue.offer(WAKE);
        }
    }

    /** @throws ExecutionCapacityException when too many live connections are open */
    public Subscription subscribe(UUID executionId) {
        if (subscriptionCount.incrementAndGet() > MAX_SUBSCRIPTIONS) {
            subscriptionCount.decrementAndGet();
            throw new ExecutionCapacityException("Too many live connections. Try again later.");
        }
        Subscription subscription = new Subscription(executionId);
        channels.computeIfAbsent(executionId, id -> new Channel()).subscriptions.add(subscription);
        return subscription;
    }

    @Override
    public void publish(ExecutionEvent event) {
        Channel channel = channels.get(event.executionId());
        if (channel == null) {
            return; // nobody is watching; the database has the state
        }
        ExecutionEvent numbered = event.withSeq(channel.seq.incrementAndGet());
        for (Subscription subscription : channel.subscriptions) {
            subscription.offer(numbered);
        }
        if (numbered.isTerminal()) {
            channels.remove(event.executionId(), channel); // subscribers keep their queues until they read the end
        }
    }

    public int subscriptionCount() {
        return subscriptionCount.get();
    }

    public int channelCount() {
        return channels.size();
    }
}
