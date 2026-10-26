package com.flowforge.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * A small real HTTP server on 127.0.0.1 for HTTP executor tests. Endpoints:
 * <ul>
 *   <li>{@code /json}: 200 {"id":"u-1","ok":true}; {@code /text}: text/plain; {@code /cookie}: sets a cookie</li>
 *   <li>{@code /echo}: 200 JSON describing the request (method, path, query, headers, body)</li>
 *   <li>{@code /users}: 201 {"id":"u-42","email":<email from the JSON body>}</li>
 *   <li>{@code /status?code=N}: answers N; {@code /fail}: 503</li>
 *   <li>{@code /delay?ms=N}: waits N ms then answers 200; {@code /slow}: waits 30 s</li>
 *   <li>{@code /big?bytes=N[&chunked=1]}: an N-byte JSON string</li>
 *   <li>{@code /redirect?to=URL[&code=302]}: redirect; {@code /loop}: redirects to itself</li>
 * </ul>
 */
public final class LocalHttpServer implements AutoCloseable {

    public record Received(String method, String path, String query, Map<String, String> headers, String body) {
    }

    private final HttpServer server;
    private final List<Received> received = Collections.synchronizedList(new ArrayList<>());

    public LocalHttpServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "local-http");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/", this::handle);
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    /** Base URL using {@code host} (e.g. localhost or 127.0.0.1). */
    public String url(String host, String path) {
        return "http://" + host + ":" + port() + path;
    }

    public List<Received> received() {
        return List.copyOf(received);
    }

    public Received last() {
        return received.get(received.size() - 1);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getRawQuery();
        Map<String, String> params = params(query);
        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), String.join(", ", v)));
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        received.add(new Received(exchange.getRequestMethod(), path, query, headers, body));
        try {
            switch (path) {
                case "/json" -> send(exchange, 200, "application/json", "{\"id\":\"u-1\",\"ok\":true}");
                case "/text" -> send(exchange, 200, "text/plain; charset=utf-8", "plain text");
                case "/cookie" -> {
                    exchange.getResponseHeaders().add("Set-Cookie", "session=abc123; HttpOnly");
                    send(exchange, 200, "application/json", "{}");
                }
                case "/echo" -> send(exchange, 200, "application/json", echo(exchange.getRequestMethod(), path, query,
                        headers, body));
                case "/users" -> {
                    String email = body.replaceAll("(?s).*\"email\"\\s*:\\s*\"([^\"]*)\".*", "$1");
                    send(exchange, 201, "application/json", "{\"id\":\"u-42\",\"email\":\"" + email + "\"}");
                }
                case "/status" -> send(exchange, Integer.parseInt(params.get("code")), "application/json",
                        "{\"error\":\"nope\"}");
                case "/fail" -> send(exchange, 503, "application/json", "{\"error\":\"down\"}");
                case "/delay" -> {
                    Thread.sleep(Long.parseLong(params.get("ms")));
                    send(exchange, 200, "application/json", "{\"late\":true}");
                }
                case "/slow" -> {
                    Thread.sleep(30_000);
                    send(exchange, 200, "application/json", "{}");
                }
                case "/big" -> {
                    int bytes = Integer.parseInt(params.get("bytes"));
                    byte[] payload = ("\"" + "x".repeat(Math.max(0, bytes - 2)) + "\"").getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, "1".equals(params.get("chunked")) ? 0 : payload.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(payload);
                    }
                }
                case "/redirect" -> {
                    exchange.getResponseHeaders().add("Location", params.get("to"));
                    send(exchange, Integer.parseInt(params.getOrDefault("code", "302")), "text/plain", "");
                }
                case "/loop" -> {
                    exchange.getResponseHeaders().add("Location", "/loop");
                    send(exchange, 302, "text/plain", "");
                }
                default -> send(exchange, 404, "text/plain", "not found");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // client went away (aborted request)
        } finally {
            exchange.close();
        }
    }

    private static void send(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    private static String echo(String method, String path, String query, Map<String, String> headers, String body) {
        StringBuilder h = new StringBuilder("{");
        headers.forEach((k, v) -> h.append(h.length() > 1 ? "," : "").append(quote(k)).append(':').append(quote(v)));
        h.append('}');
        return "{\"method\":" + quote(method) + ",\"path\":" + quote(path) + ",\"query\":" + quote(query == null ? "" : query)
                + ",\"headers\":" + h + ",\"body\":" + quote(body) + "}";
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static Map<String, String> params(String query) {
        Map<String, String> out = new LinkedHashMap<>();
        if (query == null) {
            return out;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return out;
    }
}
