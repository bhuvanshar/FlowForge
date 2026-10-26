package com.flowforge.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.application.execution.ExecutionEventBroadcaster;
import com.flowforge.support.LocalHttpServer;
import com.flowforge.support.SseClient;
import com.flowforge.support.StreamingIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Server-Sent Events against a real server, real executors and PostgreSQL. */
class ExecutionEventsSseIT extends StreamingIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(15);
    private static LocalHttpServer server;
    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ExecutionEventBroadcaster broadcaster;

    @BeforeAll
    static void start() throws Exception {
        server = new LocalHttpServer();
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE workflows CASCADE");
    }

    // ----------------------------------------------------------------------------------------------------------------

    private String post(String path, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl() + path));
        request = body == null ? request.POST(HttpRequest.BodyPublishers.noBody())
                : request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    private String get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(baseUrl() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    private static String node(String id, String type, String config) {
        return "{\"id\":\"" + id + "\",\"type\":\"" + type + "\",\"typeVersion\":1,\"name\":\"" + id
                + "\",\"position\":{\"x\":0,\"y\":0},\"configuration\":" + config + "}";
    }

    private static String edge(String source, String handle, String target) {
        return "{\"id\":\"" + source + "-" + target + "\",\"source\":\"" + source + "\",\"sourceHandle\":\"" + handle
                + "\",\"target\":\"" + target + "\",\"targetHandle\":\"main\"}";
    }

    private String credentialId;

    /** The HTTP node authenticates with a stored credential (secret "top-secret-value"), never with literal headers. */
    @org.junit.jupiter.api.BeforeEach
    void credential() throws Exception {
        credentialId = json.readTree(post("/credentials", com.flowforge.support.Credentials.bearer(
                "SSE " + java.util.UUID.randomUUID(), "top-secret-value", "localhost"))).get("id").asText();
    }

    private String http(String path) {
        return "{\"method\":\"GET\",\"url\":\"" + server.url("localhost", path)
                + "\",\"headers\":[],\"credentialId\":\"" + credentialId + "\",\"query\":[],\"timeoutSeconds\":20}";
    }

    /** trigger -> slow (HTTP, takes {@code ms}) -> log. */
    private String workflow(String callPath) throws Exception {
        String definition = "{\"schemaVersion\":1,\"nodes\":[" + node("t", "core.manualTrigger", "{}") + ","
                + node("slow", "core.httpRequest", http(callPath)) + ","
                + node("log", "core.logger", "{\"message\":\"done\"}") + "],\"edges\":[" + edge("t", "main", "slow") + ","
                + edge("slow", "main", "log") + "]}";
        return json.readTree(post("/workflows", "{\"name\":\"Live\",\"definition\":" + definition + "}")).get("id").asText();
    }

    private String execute(String workflowId) throws Exception {
        return json.readTree(post("/workflows/" + workflowId + "/execute", null)).get("executionId").asText();
    }

    private JsonNode data(SseClient.Event event) throws Exception {
        return json.readTree(event.data());
    }

    /** Folds a snapshot plus events into final node statuses, the way the UI does (statuses only move forward). */
    private Map<String, String> fold(List<SseClient.Event> events) throws Exception {
        Map<String, String> statuses = new LinkedHashMap<>();
        for (SseClient.Event event : events) {
            JsonNode d = data(event);
            if (event.name().equals("snapshot")) {
                d.get("nodes").forEach(n -> statuses.put(n.get("nodeId").asText(), n.get("status").asText()));
            } else if (d.hasNonNull("nodeId")) {
                statuses.put(d.get("nodeId").asText(), d.get("status").asText());
            }
        }
        return statuses;
    }

    // ----------------------------------------------------------------------------------------------------------------

    @Test
    void streamsASnapshotThenLiveEventsUntilTheExecutionCompletesAndThenCloses() throws Exception {
        String execution = execute(workflow("/delay?ms=600"));

        try (SseClient sse = new SseClient(baseUrl() + "/executions/" + execution + "/events")) {
            assertThat(sse.status).isEqualTo(200);
            assertThat(sse.contentType).startsWith("text/event-stream");
            List<SseClient.Event> events = sse.readToEnd(WAIT);

            assertThat(events.get(0).name()).isEqualTo("snapshot");
            JsonNode snapshot = data(events.get(0));
            assertThat(snapshot.get("executionId").asText()).isEqualTo(execution);
            assertThat(snapshot.get("nodes")).hasSize(3);
            assertThat(snapshot.get("nodes").get(1).get("nodeName").asText()).isEqualTo("slow");
            assertThat(snapshot.get("nodes").get(1).get("sequence").asInt()).isEqualTo(1);

            List<String> names = events.stream().skip(1).map(SseClient.Event::name).toList();
            assertThat(names.get(names.size() - 1)).isEqualTo("execution.completed");
            assertThat(names).doesNotContain("execution.failed", "execution.cancelled", "node.failed");
            assertThat(events.stream().filter(e -> !e.name().equals("snapshot")).map(SseClient.Event::id).toList())
                    .allMatch(id -> id != null && Long.parseLong(id) > 0);
            // Ids increase.
            List<Long> ids = events.stream().skip(1).map(e -> Long.parseLong(e.id())).toList();
            assertThat(ids).isSorted();
            assertThat(fold(events)).containsEntry("t", "SUCCEEDED").containsEntry("slow", "SUCCEEDED")
                    .containsEntry("log", "SUCCEEDED");
            JsonNode last = data(events.get(events.size() - 1));
            assertThat(last.get("status").asText()).isEqualTo("SUCCEEDED");
            assertThat(last.get("durationMs").asLong()).isGreaterThanOrEqualTo(0);
            assertThat(sse.failure()).isNull();
        }
        awaitNoSubscribers();
    }

    @Test
    void aClientThatConnectsMidRunRecoversPersistedStateThenFollowsTheRest() throws Exception {
        String execution = execute(workflow("/delay?ms=1500"));
        // Wait until the trigger is done and the slow node is running (persisted), then connect: events before this are "missed".
        awaitNodeStatus(execution, "slow", "RUNNING");

        try (SseClient sse = new SseClient(baseUrl() + "/executions/" + execution + "/events")) {
            List<SseClient.Event> events = sse.readToEnd(WAIT);

            JsonNode snapshot = data(events.get(0));
            assertThat(events.get(0).name()).isEqualTo("snapshot");
            assertThat(snapshot.get("status").asText()).isEqualTo("RUNNING");
            assertThat(snapshot.get("nodes").get(0).get("status").asText()).isEqualTo("SUCCEEDED");
            assertThat(snapshot.get("nodes").get(1).get("status").asText()).isEqualTo("RUNNING");
            assertThat(snapshot.get("nodes").get(2).get("status").asText()).isEqualTo("PENDING");
            // The missed events (execution.started, node t) are not replayed; the remaining ones follow.
            assertThat(events.stream().skip(1).map(SseClient.Event::name).toList()).contains("node.succeeded",
                    "node.started", "execution.completed").doesNotContain("execution.started");
            assertThat(fold(events)).containsEntry("slow", "SUCCEEDED").containsEntry("log", "SUCCEEDED");
        }
    }

    @Test
    void connectingAfterTheExecutionFinishedYieldsTheSnapshotAndEndsImmediately() throws Exception {
        String execution = execute(workflow("/json"));
        awaitExecutionStatus(execution, "SUCCEEDED");

        try (SseClient sse = new SseClient(baseUrl() + "/executions/" + execution + "/events")) {
            List<SseClient.Event> events = sse.readToEnd(Duration.ofSeconds(5));

            assertThat(events).hasSize(1);
            JsonNode snapshot = data(events.get(0));
            assertThat(snapshot.get("status").asText()).isEqualTo("SUCCEEDED");
            assertThat(fold(events)).containsEntry("t", "SUCCEEDED").containsEntry("slow", "SUCCEEDED")
                    .containsEntry("log", "SUCCEEDED");
            assertThat(snapshot.get("durationMs").asLong()).isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    void failuresAreStreamedWithSanitizedErrorsAndSkippedNodes() throws Exception {
        String execution = execute(workflow("/status?code=500"));

        try (SseClient sse = new SseClient(baseUrl() + "/executions/" + execution + "/events")) {
            List<SseClient.Event> events = sse.readToEnd(WAIT);

            List<String> names = events.stream().map(SseClient.Event::name).toList();
            assertThat(names.get(names.size() - 1)).isEqualTo("execution.failed");
            assertThat(fold(events)).containsEntry("t", "SUCCEEDED").containsEntry("slow", "FAILED")
                    .containsEntry("log", "SKIPPED");
            SseClient.Event nodeFailed = events.stream().filter(e -> e.name().equals("node.failed")).findFirst()
                    .orElseThrow(() -> new AssertionError("no node.failed in " + names));
            assertThat(data(nodeFailed).get("error").get("code").asText()).isEqualTo("HTTP_STATUS_ERROR");
            assertThat(data(events.get(events.size() - 1)).get("error").get("message").asText())
                    .startsWith("Node 'slow' failed:");
        }
    }

    @Test
    void cancellationIsStreamed() throws Exception {
        String execution = execute(workflow("/slow"));
        awaitNodeStatus(execution, "slow", "RUNNING");

        try (SseClient sse = new SseClient(baseUrl() + "/executions/" + execution + "/events")) {
            assertThat(sse.next(WAIT).name()).isEqualTo("snapshot");
            post("/executions/" + execution + "/cancel", null);

            List<SseClient.Event> events = sse.readToEnd(WAIT);

            List<String> names = events.stream().map(SseClient.Event::name).toList();
            assertThat(names).contains("node.failed", "node.skipped");
            assertThat(names.get(names.size() - 1)).isEqualTo("execution.cancelled");
            assertThat(fold(events)).containsEntry("slow", "FAILED").containsEntry("log", "SKIPPED");
        }
    }

    @Test
    void severalClientsReceiveTheSameEventsAndEachConnectionIsCleanedUp() throws Exception {
        String execution = execute(workflow("/delay?ms=500"));

        try (SseClient a = new SseClient(baseUrl() + "/executions/" + execution + "/events");
             SseClient b = new SseClient(baseUrl() + "/executions/" + execution + "/events")) {
            List<SseClient.Event> ea = a.readToEnd(WAIT);
            List<SseClient.Event> eb = b.readToEnd(WAIT);

            assertThat(fold(ea)).isEqualTo(fold(eb));
            assertThat(ea.get(ea.size() - 1).name()).isEqualTo("execution.completed");
            assertThat(eb.get(eb.size() - 1).name()).isEqualTo("execution.completed");
        }
        awaitNoSubscribers();
        assertThat(broadcaster.channelCount()).isZero();
    }

    @Test
    void aClientThatDisconnectsEarlyReleasesItsSubscription() throws Exception {
        String execution = execute(workflow("/slow"));
        awaitNodeStatus(execution, "slow", "RUNNING");
        SseClient sse = new SseClient(baseUrl() + "/executions/" + execution + "/events");
        assertThat(sse.next(WAIT).name()).isEqualTo("snapshot");
        assertThat(broadcaster.subscriptionCount()).isEqualTo(1);

        sse.close();
        post("/executions/" + execution + "/cancel", null); // forces a write so the server notices the dead client

        awaitNoSubscribers();
    }

    @Test
    void eventsNeverContainPayloadsOrCredentials() throws Exception {
        String execution = execute(workflow("/echo"));

        try (SseClient sse = new SseClient(baseUrl() + "/executions/" + execution + "/events")) {
            List<SseClient.Event> events = sse.readToEnd(WAIT);

            String all = events.stream().map(SseClient.Event::data).reduce("", String::concat);
            assertThat(all).doesNotContain("top-secret", "\"input\"", "\"output\"");
        }
    }

    @Test
    void unknownExecutionsAre404WithAProblemDetail() throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                URI.create(baseUrl() + "/executions/" + UUID.randomUUID() + "/events")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers().firstValue("content-type").orElse("")).contains("application/problem+json");
        assertThat(broadcaster.subscriptionCount()).isZero();
    }

    @Test
    void eventStreamIsDocumentedInOpenApi() throws Exception {
        JsonNode paths = json.readTree(http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v3/api-docs")).GET().build(), HttpResponse.BodyHandlers.ofString()).body()).path("paths");
        assertThat(paths.has("/api/v1/executions/{executionId}/events")).isTrue();
    }

    // ----------------------------------------------------------------------------------------------------------------

    private JsonNode fetchExecution(String execution) throws Exception {
        return json.readTree(get("/executions/" + execution));
    }

    private void awaitNodeStatus(String execution, String nodeId, String status) throws Exception {
        long until = System.currentTimeMillis() + 10_000;
        while (true) {
            for (JsonNode n : fetchExecution(execution).get("nodes")) {
                if (n.get("nodeId").asText().equals(nodeId) && n.get("status").asText().equals(status)) {
                    return;
                }
            }
            if (System.currentTimeMillis() > until) {
                throw new AssertionError("node " + nodeId + " never reached " + status);
            }
            Thread.sleep(20);
        }
    }

    private void awaitExecutionStatus(String execution, String status) throws Exception {
        long until = System.currentTimeMillis() + 10_000;
        while (!fetchExecution(execution).get("status").asText().equals(status)) {
            if (System.currentTimeMillis() > until) {
                throw new AssertionError("execution never reached " + status);
            }
            Thread.sleep(20);
        }
    }

    private void awaitNoSubscribers() throws Exception {
        long until = System.currentTimeMillis() + 10_000;
        while (broadcaster.subscriptionCount() != 0) {
            if (System.currentTimeMillis() > until) {
                throw new AssertionError("subscriptions leaked: " + broadcaster.subscriptionCount());
            }
            Thread.sleep(20);
        }
    }
}
