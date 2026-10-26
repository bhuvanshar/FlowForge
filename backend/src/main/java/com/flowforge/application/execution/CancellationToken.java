package com.flowforge.application.execution;

import java.util.concurrent.atomic.AtomicBoolean;

/** Cooperative cancellation flag shared between the API (cancel request) and the engine thread. */
public final class CancellationToken {

    private final AtomicBoolean cancelled = new AtomicBoolean();

    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }
}
