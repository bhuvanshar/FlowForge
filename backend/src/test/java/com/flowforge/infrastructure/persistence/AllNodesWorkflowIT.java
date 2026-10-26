package com.flowforge.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowforge.domain.execution.ExecutionStatus;
import com.flowforge.support.LocalHttpServer;
import com.flowforge.support.PostgresIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Acceptance test: one workflow using all six node types, executed through the API with the real executors.
 *
 * <pre>
 * trigger ─► vars ─► check ─true─► create (POST /users) ─► shape (transform) ─► done (logger)
 *                         └false─► tooYoung (logger)
 * </pre>
 */
class AllNodesWorkflowIT extends PostgresIntegrationTest {

    private static LocalHttpServer server;

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeAll
    static void startServer() throws Exception {
        server = new LocalHttpServer();
    }

    @AfterAll
    static void stopServer() {
        server.close();
    }

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE workflows CASCADE");
    }

    private ObjectNode node(String id, String type, Object configuration) {
        ObjectNode node = json.createObjectNode();
        node.put("id", id).put("type", type).put("typeVersion", 1).put("name", id);
        node.putObject("position").put("x", 0).put("y", 0);
        node.set("configuration", json.valueToTree(configuration));
        return node;
    }

    private ObjectNode edge(String source, String handle, String target) {
        return json.createObjectNode().put("id", source + "_" + target).put("source", source).put("sourceHandle", handle)
                .put("target", target).put("targetHandle", "main");
    }

    private String workflow(String createPath) throws Exception {
        ArrayNode nodes = json.createArrayNode();
        nodes.add(node("trigger", "core.manualTrigger",
                Map.of("testInput", Map.of("user", Map.of("email", "ada@example.com", "age", 31)))));
        nodes.add(node("vars", "core.setVariables", Map.of("variables", List.of(
                Map.of("name", "email", "value", "{{ trigger.body.user.email }}"),
                Map.of("name", "minAge", "value", "18")))));
        nodes.add(node("check", "core.condition", Map.of("field", "{{ trigger.body.user.age }}",
                "operator", "greaterThanOrEqual", "value", "{{ vars.minAge }}")));
        // Authentication comes from a stored, encrypted credential, never from the workflow JSON (Module 12).
        String credentialId = json.readTree(mvc.perform(post("/api/v1/credentials").contentType(MediaType.APPLICATION_JSON)
                        .content(com.flowforge.support.Credentials.bearer("Users API " + java.util.UUID.randomUUID(),
                                "super-secret", "localhost")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
        nodes.add(node("create", "core.httpRequest", Map.of("method", "POST", "url", server.url("localhost", createPath),
                "headers", List.of(), "credentialId", credentialId,
                "query", List.of(Map.of("name", "source", "value", "flowforge")),
                "body", Map.of("email", "{{ vars.email }}", "age", "{{ trigger.body.user.age }}"),
                "timeoutSeconds", 10)));
        nodes.add(node("shape", "core.transform", Map.of("operations", List.of(
                Map.of("field", "id", "source", "{{ input.body.id }}", "operation", "copy"),
                Map.of("field", "status", "source", "{{ nodes.create.output.status }}", "operation", "copy"),
                Map.of("field", "email", "source", "{{ vars.email }}", "operation", "toUpperCase")))));
        nodes.add(node("done", "core.logger", Map.of("message", "Created {{ input.id }} ({{ input.status }}) for {{ input.email }}")));
        nodes.add(node("tooYoung", "core.logger",
                Map.of("message", "Rejected {{ trigger.body.user.email }}: age {{ trigger.body.user.age }}")));
        ArrayNode edges = json.createArrayNode();
        edges.add(edge("trigger", "main", "vars"));
        edges.add(edge("vars", "main", "check"));
        edges.add(edge("check", "true", "create"));
        edges.add(edge("create", "main", "shape"));
        edges.add(edge("shape", "main", "done"));
        edges.add(edge("check", "false", "tooYoung"));
        ObjectNode definition = json.createObjectNode().put("schemaVersion", 1);
        definition.set("nodes", nodes);
        definition.set("edges", edges);

        ObjectNode body = json.createObjectNode().put("name", "All nodes");
        body.set("definition", definition);
        String response = mvc.perform(post("/api/v1/workflows").contentType(MediaType.APPLICATION_JSON)
                .content(body.toString())).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = json.readTree(response).get("id").asText();
        mvc.perform(post("/api/v1/workflows/" + id + "/validate")).andExpect(jsonPath("$.valid").value(true));
        return id;
    }

    private JsonNode run(String workflowId, String input) throws Exception {
        var request = post("/api/v1/workflows/" + workflowId + "/execute");
        if (input != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content("{\"input\":" + input + "}");
        }
        String started = mvc.perform(request).andExpect(status().isAccepted()).andReturn().getResponse()
                .getContentAsString();
        String executionId = json.readTree(started).get("executionId").asText();
        long until = System.currentTimeMillis() + 15_000;
        while (true) {
            JsonNode execution = json.readTree(mvc.perform(get("/api/v1/executions/" + executionId))
                    .andReturn().getResponse().getContentAsString());
            if (ExecutionStatus.valueOf(execution.get("status").asText()).isTerminal()) {
                return execution;
            }
            if (System.currentTimeMillis() > until) {
                throw new AssertionError("Execution did not finish: " + execution);
            }
            Thread.sleep(25);
        }
    }

    private static Map<String, String> statuses(JsonNode execution) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        execution.get("nodes").forEach(n -> out.put(n.get("nodeId").asText(), n.get("status").asText()));
        return out;
    }

    private static JsonNode nodeRun(JsonNode execution, String nodeId) {
        for (JsonNode n : execution.get("nodes")) {
            if (n.get("nodeId").asText().equals(nodeId)) {
                return n;
            }
        }
        throw new AssertionError(nodeId);
    }

    private static List<String> logMessages(JsonNode execution) {
        List<String> out = new ArrayList<>();
        execution.get("logs").forEach(l -> out.add(l.get("nodeId").asText() + ": " + l.get("message").asText()));
        return out;
    }

    @Test
    void truePathRunsAllSixNodeTypesAndPropagatesData() throws Exception {
        int before = server.received().size();
        JsonNode execution = run(workflow("/users"), null);

        assertThat(execution.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(statuses(execution)).containsExactly(
                Map.entry("trigger", "SUCCEEDED"), Map.entry("vars", "SUCCEEDED"), Map.entry("check", "SUCCEEDED"),
                Map.entry("create", "SUCCEEDED"), Map.entry("shape", "SUCCEEDED"), Map.entry("done", "SUCCEEDED"),
                Map.entry("tooYoung", "SKIPPED"));

        // The HTTP node really called the server with resolved values.
        assertThat(server.received()).hasSize(before + 1);
        LocalHttpServer.Received call = server.last();
        assertThat(call.method()).isEqualTo("POST");
        assertThat(call.query()).isEqualTo("source=flowforge");
        assertThat(call.headers().get("authorization")).isEqualTo("Bearer super-secret");
        assertThat(json.readTree(call.body())).isEqualTo(json.readTree("{\"email\":\"ada@example.com\",\"age\":31}"));

        JsonNode created = nodeRun(execution, "create").get("output");
        assertThat(created.get("status").asInt()).isEqualTo(201);
        assertThat(created.get("body").get("id").asText()).isEqualTo("u-42");
        assertThat(created.toString()).doesNotContain("super-secret");

        assertThat(nodeRun(execution, "shape").get("output"))
                .isEqualTo(json.readTree("{\"id\":\"u-42\",\"status\":201,\"email\":\"ADA@EXAMPLE.COM\"}"));
        assertThat(logMessages(execution)).containsExactly("done: Created u-42 (201) for ADA@EXAMPLE.COM");
    }

    @Test
    void falsePathRunsOnlyTheOtherBranch() throws Exception {
        int before = server.received().size();
        JsonNode execution = run(workflow("/users"), "{\"user\":{\"email\":\"kid@example.com\",\"age\":12}}");

        assertThat(execution.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(statuses(execution)).containsExactly(
                Map.entry("trigger", "SUCCEEDED"), Map.entry("vars", "SUCCEEDED"), Map.entry("check", "SUCCEEDED"),
                Map.entry("create", "SKIPPED"), Map.entry("shape", "SKIPPED"), Map.entry("done", "SKIPPED"),
                Map.entry("tooYoung", "SUCCEEDED"));
        assertThat(server.received()).hasSize(before);
        assertThat(logMessages(execution)).containsExactly("tooYoung: Rejected kid@example.com: age 12");
    }

    @Test
    void httpErrorFailsTheExecutionAndSkipsTheRest() throws Exception {
        JsonNode execution = run(workflow("/status?code=500"), null);

        assertThat(execution.get("status").asText()).isEqualTo("FAILED");
        assertThat(execution.get("error").get("code").asText()).isEqualTo("HTTP_STATUS_ERROR");
        assertThat(execution.get("error").get("message").asText()).startsWith("Node 'create' failed: POST")
                .contains("answered 500");
        assertThat(statuses(execution)).containsEntry("create", "FAILED").containsEntry("shape", "SKIPPED")
                .containsEntry("done", "SKIPPED").containsEntry("tooYoung", "SKIPPED");
    }

    @Test
    void typeErrorsInConditionsFailTheExecution() throws Exception {
        JsonNode execution = run(workflow("/users"), "{\"user\":{\"email\":\"x@example.com\",\"age\":\"unknown\"}}");

        assertThat(execution.get("status").asText()).isEqualTo("FAILED");
        assertThat(nodeRun(execution, "check").get("error").get("code").asText()).isEqualTo("TYPE_MISMATCH");
        assertThat(statuses(execution)).containsEntry("create", "SKIPPED").containsEntry("tooYoung", "SKIPPED");
    }

    @Test
    void urlsBuiltFromInputDataCannotReachUnlistedHosts() throws Exception {
        ObjectNode trigger = node("trigger", "core.manualTrigger", Map.of());
        ObjectNode call = node("call", "core.httpRequest", Map.of("method", "GET", "url", "{{ trigger.body.callback }}",
                "headers", List.of(), "query", List.of(), "timeoutSeconds", 5));
        ObjectNode definition = json.createObjectNode().put("schemaVersion", 1);
        definition.putArray("nodes").add(trigger).add(call);
        definition.putArray("edges").add(edge("trigger", "main", "call"));
        ObjectNode body = json.createObjectNode().put("name", "SSRF attempt");
        body.set("definition", definition);
        String id = json.readTree(mvc.perform(post("/api/v1/workflows").contentType(MediaType.APPLICATION_JSON)
                .content(body.toString())).andReturn().getResponse().getContentAsString()).get("id").asText();

        JsonNode execution = run(id, "{\"callback\":\"http://169.254.169.254/latest/meta-data/\"}");

        assertThat(execution.get("status").asText()).isEqualTo("FAILED");
        assertThat(execution.get("error").get("code").asText()).isEqualTo("HTTP_HOST_NOT_ALLOWED");
    }
}
