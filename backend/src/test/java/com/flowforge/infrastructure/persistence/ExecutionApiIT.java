package com.flowforge.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.application.execution.ExecutionStateManager;
import com.flowforge.domain.execution.ExecutionStatus;
import com.flowforge.domain.workflow.WorkflowDefinition;
import com.flowforge.support.LocalHttpServer;
import com.flowforge.support.PostgresIntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** End to end: HTTP API, async workers, the built-in executors, a local HTTP server and PostgreSQL history. */
class ExecutionApiIT extends PostgresIntegrationTest {

    private static LocalHttpServer server;

    @BeforeAll
    static void startServer() throws Exception {
        server = new LocalHttpServer();
    }

    @AfterAll
    static void stopServer() {
        server.close();
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ExecutionStateManager states;

    @Autowired
    WorkflowRepository workflows;

    @Autowired
    WorkflowExecutionRepository executions;

    @Autowired
    NodeExecutionRepository nodeExecutions;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE workflows CASCADE");
    }

    // ----------------------------------------------------------------------------------------------------------------

    private static String node(String id, String type, String config) {
        return "{\"id\":\"" + id + "\",\"type\":\"" + type + "\",\"typeVersion\":1,\"name\":\"" + id
                + "\",\"position\":{\"x\":0,\"y\":0},\"configuration\":" + config + "}";
    }

    private static String edge(String source, String handle, String target) {
        return "{\"id\":\"" + source + "-" + target + "\",\"source\":\"" + source + "\",\"sourceHandle\":\"" + handle
                + "\",\"target\":\"" + target + "\",\"targetHandle\":\"main\"}";
    }

    private static String definition(List<String> nodes, List<String> edges) {
        return "{\"schemaVersion\":1,\"nodes\":[" + String.join(",", nodes) + "],\"edges\":[" + String.join(",", edges)
                + "]}";
    }

    private static String http(String path) {
        return "{\"method\":\"GET\",\"url\":\"" + server.url("localhost", path)
                + "\",\"headers\":[],\"query\":[],\"timeoutSeconds\":30}";
    }

    private static final String TRIGGER = node("t", "core.manualTrigger", "{}");
    private static final String LOG = "{\"message\":\"hi\"}";

    private String createWorkflow(String definition) throws Exception {
        String body = mvc.perform(post("/api/v1/workflows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Run me\",\"definition\":" + definition + "}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asText();
    }

    private String execute(String workflowId, String body) throws Exception {
        var request = post("/api/v1/workflows/" + workflowId + "/execute");
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        String response = mvc.perform(request)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.workflowId").value(workflowId))
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/v1/executions/")))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("executionId").asText();
    }

    private JsonNode fetch(String executionId) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/executions/" + executionId)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode awaitExecution(String executionId, Predicate<JsonNode> condition) throws Exception {
        long until = System.currentTimeMillis() + 10_000;
        JsonNode current = fetch(executionId);
        while (!condition.test(current)) {
            if (System.currentTimeMillis() > until) {
                throw new AssertionError("Timed out waiting; last state: " + current);
            }
            Thread.sleep(25);
            current = fetch(executionId);
        }
        return current;
    }

    private JsonNode awaitFinished(String executionId) throws Exception {
        return awaitExecution(executionId, e -> ExecutionStatus.valueOf(e.get("status").asText()).isTerminal());
    }

    private static JsonNode nodeRun(JsonNode execution, String nodeId) {
        for (JsonNode n : execution.get("nodes")) {
            if (n.get("nodeId").asText().equals(nodeId)) {
                return n;
            }
        }
        throw new AssertionError("No node run " + nodeId);
    }

    private static List<String> statuses(JsonNode execution) {
        List<String> out = new ArrayList<>();
        execution.get("nodes").forEach(n -> out.add(n.get("nodeId").asText() + "=" + n.get("status").asText()));
        return out;
    }

    // ----------------------------------------------------------------------------------------------------------------

    @Test
    void executesALinearWorkflowAndPersistsEveryStep() throws Exception {
        String id = createWorkflow(definition(
                List.of(TRIGGER, node("vars", "core.setVariables", "{\"variables\":[{\"name\":\"region\",\"value\":\"eu\"}]}"),
                        node("log", "core.logger", "{\"message\":\"Order {{ input.order }} in {{ vars.region }}\"}")),
                List.of(edge("t", "main", "vars"), edge("vars", "main", "log"))));

        String executionId = execute(id, "{\"input\":{\"order\":42}}");
        JsonNode done = awaitFinished(executionId);

        assertThat(done.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(done.get("workflowVersion").asInt()).isEqualTo(1);
        assertThat(done.get("startedAt").isTextual()).isTrue();
        assertThat(statuses(done)).containsExactly("t=SUCCEEDED", "vars=SUCCEEDED", "log=SUCCEEDED");
        assertThat(nodeRun(done, "t").get("output")).isEqualTo(json.readTree("{\"order\":42}"));
        assertThat(nodeRun(done, "vars").get("input")).isEqualTo(json.readTree("{\"order\":42}"));
        assertThat(nodeRun(done, "log").get("output")).isEqualTo(json.readTree("{\"order\":42}"));
        assertThat(done.get("logs")).hasSize(1);
        assertThat(done.get("logs").get(0).get("nodeId").asText()).isEqualTo("log");
        assertThat(done.get("logs").get(0).get("message").asText()).isEqualTo("Order 42 in eu");
        assertThat(nodeRun(done, "log").get("sequence").asInt()).isEqualTo(2);
        assertThat(done.has("error")).isFalse();
    }

    @Test
    void usesTheTriggersTestInputWhenNoInputIsGiven() throws Exception {
        String id = createWorkflow(definition(
                List.of(node("t", "core.manualTrigger", "{\"testInput\":{\"sample\":true}}"), node("log", "core.logger", LOG)),
                List.of(edge("t", "main", "log"))));

        JsonNode done = awaitFinished(execute(id, null));

        assertThat(nodeRun(done, "t").get("output")).isEqualTo(json.readTree("{\"sample\":true}"));
    }

    @Test
    void persistsSkippedBranches() throws Exception {
        String id = createWorkflow(definition(
                List.of(TRIGGER, node("c", "core.condition", "{\"field\":\"flag\",\"operator\":\"equals\",\"value\":\"true\"}"),
                        node("yes", "core.logger", LOG), node("no", "core.transform",
                                "{\"operations\":[{\"field\":\"a\",\"source\":\"b\",\"operation\":\"trim\"}]}"),
                        node("after", "core.logger", LOG)),
                List.of(edge("t", "main", "c"), edge("c", "true", "yes"), edge("c", "false", "no"),
                        edge("no", "main", "after"))));

        JsonNode done = awaitFinished(execute(id, "{\"input\":{\"flag\":false}}"));

        assertThat(done.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(statuses(done)).containsExactly("t=SUCCEEDED", "c=SUCCEEDED", "yes=SKIPPED", "no=SUCCEEDED",
                "after=SUCCEEDED");
        assertThat(nodeRun(done, "yes").has("input")).isFalse();
    }

    @Test
    void recordsFailuresWithTheNodesError() throws Exception {
        String id = createWorkflow(definition(
                List.of(TRIGGER, node("call", "core.httpRequest", http("/fail")),
                        node("log", "core.logger", LOG)),
                List.of(edge("t", "main", "call"), edge("call", "main", "log"))));

        JsonNode done = awaitFinished(execute(id, null));

        assertThat(done.get("status").asText()).isEqualTo("FAILED");
        assertThat(done.get("error").get("code").asText()).isEqualTo("HTTP_STATUS_ERROR");
        assertThat(done.get("error").get("message").asText())
                .startsWith("Node 'call' failed: GET http://localhost:").contains("/fail answered 503");
        assertThat(statuses(done)).containsExactly("t=SUCCEEDED", "call=FAILED", "log=SKIPPED");
    }

    @Test
    void cancelsARunningExecution() throws Exception {
        String id = createWorkflow(definition(
                List.of(TRIGGER, node("slow", "core.httpRequest", http("/slow")),
                        node("log", "core.logger", LOG)),
                List.of(edge("t", "main", "slow"), edge("slow", "main", "log"))));
        String executionId = execute(id, null);
        awaitExecution(executionId, e -> nodeRun(e, "slow").get("status").asText().equals("RUNNING"));

        mvc.perform(post("/api/v1/executions/" + executionId + "/cancel")).andExpect(status().isAccepted());
        JsonNode done = awaitFinished(executionId);

        assertThat(done.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(statuses(done)).containsExactly("t=SUCCEEDED", "slow=FAILED", "log=SKIPPED");
        assertThat(nodeRun(done, "slow").get("error").get("code").asText()).isEqualTo("CANCELLED");
        mvc.perform(post("/api/v1/executions/" + executionId + "/cancel")).andExpect(status().isConflict());
        assertThat(states.isActive(UUID.fromString(executionId))).isFalse();
    }

    @Test
    void storesOversizedPayloadsTruncatedButPassesThemOnInFull() throws Exception {
        String id = createWorkflow(definition(
                List.of(TRIGGER, node("big", "core.httpRequest", http("/big?bytes=300000")),
                        node("sum", "core.transform", "{\"operations\":[{\"field\":\"len\",\"source\":\"{{ input.body }}\","
                                + "\"operation\":\"length\"}]}")),
                List.of(edge("t", "main", "big"), edge("big", "main", "sum"))));

        JsonNode done = awaitFinished(execute(id, null));

        assertThat(done.get("status").asText()).isEqualTo("SUCCEEDED");
        JsonNode stored = nodeRun(done, "big").get("output");
        assertThat(stored.get("_truncated").asBoolean()).isTrue();
        assertThat(stored.get("originalSizeBytes").asInt()).isGreaterThan(300_000);
        assertThat(stored.get("preview").asText()).hasSize(2000);
        assertThat(nodeRun(done, "sum").get("output").get("len").asInt()).isEqualTo(299_998); // full body downstream
    }

    @Test
    void runsAreDeterministic() throws Exception {
        String id = createWorkflow(definition(
                List.of(TRIGGER, node("c", "core.condition", "{\"field\":\"flag\",\"operator\":\"equals\",\"value\":\"true\"}"),
                        node("b", "core.logger", LOG), node("a", "core.logger", LOG)),
                List.of(edge("t", "main", "c"), edge("c", "true", "a"), edge("c", "false", "b"))));

        JsonNode first = awaitFinished(execute(id, "{\"input\":{\"flag\":true}}"));
        JsonNode second = awaitFinished(execute(id, "{\"input\":{\"flag\":true}}"));

        assertThat(statuses(second)).isEqualTo(statuses(first)).containsExactly("t=SUCCEEDED", "c=SUCCEEDED",
                "b=SKIPPED", "a=SUCCEEDED");
        assertThat(nodeRun(second, "a").get("output")).isEqualTo(nodeRun(first, "a").get("output"));
    }

    @Test
    void executesTheSnapshotNotLaterEdits() throws Exception {
        String id = createWorkflow(definition(List.of(TRIGGER, node("slow", "core.httpRequest",
                http("/slow"))), List.of(edge("t", "main", "slow"))));
        String executionId = execute(id, null);
        mvc.perform(put("/api/v1/workflows/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Edited\",\"lockVersion\":0,\"definition\":"
                                + definition(List.of(TRIGGER), List.of()) + "}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/executions/" + executionId + "/cancel")).andExpect(status().isAccepted());

        JsonNode done = awaitFinished(executionId);
        assertThat(statuses(done)).containsExactly("t=SUCCEEDED", "slow=FAILED");
        WorkflowDefinition snapshot = executions.findById(UUID.fromString(executionId)).orElseThrow().getWorkflowSnapshot();
        assertThat(snapshot.nodes()).hasSize(2);
    }

    @Test
    void rejectsInvalidArchivedAndUnknownWorkflowsWithoutCreatingExecutions() throws Exception {
        String invalid = createWorkflow(definition(List.of(node("log", "core.logger", LOG)), List.of()));
        mvc.perform(post("/api/v1/workflows/" + invalid + "/execute"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("https://flowforge.local/problems/workflow-invalid"))
                .andExpect(jsonPath("$.issues[?(@.code=='NO_TRIGGER')]").exists());

        String archived = createWorkflow(definition(List.of(TRIGGER), List.of()));
        mvc.perform(delete("/api/v1/workflows/" + archived)).andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/workflows/" + archived + "/execute")).andExpect(status().isConflict());

        mvc.perform(post("/api/v1/workflows/" + UUID.randomUUID() + "/execute")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/executions/" + UUID.randomUUID())).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/executions/" + UUID.randomUUID() + "/cancel")).andExpect(status().isNotFound());
        assertThat(executions.count()).isZero();
    }

    @Test
    void marksExecutionsLeftOverFromAPreviousProcessAsInterrupted() throws Exception {
        String id = createWorkflow(definition(List.of(TRIGGER, node("log", "core.logger", LOG)),
                List.of(edge("t", "main", "log"))));
        WorkflowEntity workflow = workflows.findById(UUID.fromString(id)).orElseThrow();
        WorkflowExecutionEntity orphan = WorkflowExecutionEntity.queued(UUID.randomUUID(), workflow);
        orphan.markRunning(Instant.now());
        executions.save(orphan);
        NodeExecutionEntity running = new NodeExecutionEntity(UUID.randomUUID(), orphan.getId(), "t",
                "core.manualTrigger", 0);
        running.markRunning(Instant.now(), json.createObjectNode());
        NodeExecutionEntity pending = new NodeExecutionEntity(UUID.randomUUID(), orphan.getId(), "log", "core.logger", 1);
        nodeExecutions.saveAll(List.of(running, pending));

        states.recoverInterruptedExecutions();

        JsonNode recovered = fetch(orphan.getId().toString());
        assertThat(recovered.get("status").asText()).isEqualTo("FAILED");
        assertThat(recovered.get("error").get("code").asText()).isEqualTo("EXECUTION_INTERRUPTED");
        assertThat(statuses(recovered)).containsExactly("t=FAILED", "log=SKIPPED");
    }
}
