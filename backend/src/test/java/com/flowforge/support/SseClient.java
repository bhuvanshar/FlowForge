package com.flowforge.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Minimal blocking Server-Sent Events client for tests. Reads on its own thread; {@link #next} waits with a timeout. */
public final class SseClient implements AutoCloseable {

    public record Event(String id, String name, String data) {
    }

    private static final Event END = new Event(null, "<end>", null);

    private final BlockingQueue<Event> events = new LinkedBlockingQueue<>();
    private final List<String> comments = new ArrayList<>();
    private final Thread reader;
    private volatile HttpResponse<Stream<String>> response;
    private volatile Throwable failure;
    public final int status;
    public final String contentType;

    public SseClient(String url) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).header("Accept", "text/event-stream").GET().build();
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofLines());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        status = response.statusCode();
        contentType = response.headers().firstValue("content-type").orElse("");
        reader = new Thread(this::read, "sse-test-reader");
        reader.setDaemon(true);
        reader.start();
    }

    private void read() {
        String id = null;
        String name = "message";
        StringBuilder data = new StringBuilder();
        try (Stream<String> lines = response.body()) {
            for (var it = lines.iterator(); it.hasNext(); ) {
                String line = it.next();
                if (line.isEmpty()) {
                    if (data.length() > 0) {
                        events.add(new Event(id, name, data.toString()));
                    }
                    id = null;
                    name = "message";
                    data.setLength(0);
                } else if (line.startsWith(":")) {
                    synchronized (comments) {
                        comments.add(line.substring(1).trim());
                    }
                } else if (line.startsWith("id:")) {
                    id = line.substring(3).trim();
                } else if (line.startsWith("event:")) {
                    name = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    if (data.length() > 0) {
                        data.append('\n');
                    }
                    data.append(line.substring(5).stripLeading());
                }
            }
        } catch (RuntimeException e) {
            failure = e;
        } finally {
            events.add(END);
        }
    }

    /** Next event, or null when the stream ended. Fails on timeout. */
    public Event next(Duration timeout) throws InterruptedException {
        Event event = events.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (event == null) {
            throw new AssertionError("No event within " + timeout);
        }
        if (event == END) {
            events.add(END);
            return null;
        }
        return event;
    }

    /** Reads until the stream ends (server closed it) or {@code timeout} passes. */
    public List<Event> readToEnd(Duration timeout) throws InterruptedException {
        List<Event> all = new ArrayList<>();
        long until = System.nanoTime() + timeout.toNanos();
        while (true) {
            long left = until - System.nanoTime();
            if (left <= 0) {
                throw new AssertionError("Stream did not end within " + timeout + "; got " + all);
            }
            Event event = next(Duration.ofNanos(left));
            if (event == null) {
                return all;
            }
            all.add(event);
        }
    }

    public List<String> comments() {
        synchronized (comments) {
            return List.copyOf(comments);
        }
    }

    public Throwable failure() {
        return failure;
    }

    @Override
    public void close() {
        try {
            response.body().close();
        } catch (RuntimeException ignored) {
            // already closed
        }
    }
}
