package com.flowforge.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * A scripted OpenAI-compatible server for tests: replies are consumed in order (the last one is repeated when
 * {@link #repeatLast} is set), every request is recorded. No network access, no real provider.
 */
public final class MockAiServer implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** One scripted reply. */
    public record Reply(int status, Map<String, String> headers, String body, long delayMillis) {

        public Reply withHeader(String name, String value) {
            Map<String, String> h = new LinkedHashMap<>(headers);
            h.put(name, value);
            return new Reply(status, h, body, delayMillis);
        }

        public Reply delayed(long millis) {
            return new Reply(status, headers, body, millis);
        }

        /** A 200 chat completion with the given assistant content. */
        public static Reply content(String content) {
            return content(content, "stop");
        }

        public static Reply content(String content, String finishReason) {
            return completion(content, finishReason, "\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":7,\"total_tokens\":19}");
        }

        /** Raw {@code usage} JSON (or null for none) and finish reason. */
        public static Reply completion(String content, String finishReason, String usageJson) {
            return new Reply(200, Map.of("Content-Type", "application/json"),
                    "{\"id\":\"chatcmpl-test\",\"model\":\"test-model-2026\",\"choices\":[{\"index\":0,\"message\":"
                            + "{\"role\":\"assistant\",\"content\":" + quote(content) + "},\"finish_reason\":\""
                            + finishReason + "\"}]" + (usageJson == null ? "" : "," + usageJson) + "}", 0);
        }

        public static Reply raw(int status, String body) {
            return new Reply(status, Map.of("Content-Type", "application/json"), body, 0);
        }

        public static Reply error(int status, String message) {
            return raw(status, "{\"error\":{\"message\":" + quote(message) + ",\"type\":\"test_error\"}}");
        }
    }

    /** A recorded request; {@code headers} have lower-case names. */
    public record Received(String method, String path, Map<String, String> headers, String body) {

        public JsonNode json() {
            try {
                return JSON.readTree(body);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private final HttpServer server;
    private final Deque<Reply> script = new ArrayDeque<>();
    private final List<Received> received = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean repeatLast;
    private volatile Reply last;

    public MockAiServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "mock-ai");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/", this::handle);
        server.start();
    }

    /** Base URL for the node's {@code baseUrl} setting (host name {@code localhost}). */
    public String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort() + "/v1";
    }

    public MockAiServer reply(Reply... replies) {
        synchronized (script) {
            script.addAll(List.of(replies));
        }
        return this;
    }

    /** Answer with the last scripted reply for every further request. */
    public MockAiServer repeatLast() {
        repeatLast = true;
        return this;
    }

    public void reset() {
        synchronized (script) {
            script.clear();
        }
        received.clear();
        repeatLast = false;
        last = null;
    }

    public List<Received> requests() {
        return List.copyOf(received);
    }

    public Received lastRequest() {
        return received.get(received.size() - 1);
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), String.join(", ", v)));
        received.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), headers,
                new String(body, StandardCharsets.UTF_8)));
        Reply reply;
        synchronized (script) {
            reply = script.poll();
            if (reply != null) {
                last = reply;
            } else if (repeatLast && last != null) {
                reply = last;
            }
        }
        if (reply == null) {
            reply = Reply.error(500, "no scripted reply left");
        }
        try {
            if (reply.delayMillis() > 0) {
                Thread.sleep(reply.delayMillis());
            }
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            reply.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
            exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
        } catch (InterruptedException | IOException e) {
            // The client gave up (timeout or cancellation).
        } finally {
            exchange.close();
        }
    }

    private static String quote(String s) {
        try {
            return JSON.writeValueAsString(s);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
