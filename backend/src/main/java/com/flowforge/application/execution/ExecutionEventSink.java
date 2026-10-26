package com.flowforge.application.execution;

/** Receives execution events from the engine. Must never block or throw: the engine thread calls it inline. */
public interface ExecutionEventSink {

    ExecutionEventSink NOOP = event -> { };

    void publish(ExecutionEvent event);
}
