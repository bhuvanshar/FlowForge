package com.flowforge.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.domain.execution.ExecutionStatus;
import com.flowforge.support.LocalHttpServer;
import com.flowforge.support.PostgresIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** Execution history API against real executions: lists, filters, node results, snapshots, redaction. */
class ExecutionHistoryApiIT extends PostgresIntegrationTest {

    private static LocalHttpServer server;

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeAll
    static void start() throws Exception {
        server = new LocalHttpServer();
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    private String credentialId;

    @BeforeEach
    void clean() throws Exception {
        jdbc.execute("TRUNCATE workflows CASCADE");
        jdbc.execute("TRUNCATE credentials");
        credentialId = json.readTree(mvc.perform(post("/api/v1/credentials").contentType(MediaType.APPLICATION_JSON)
                        .content(com.flowforge.support.Credentials.bearer("History API", "top-secret-token", "localhost")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
    }

    private String definition(String callPath, String loggerName) {
        return "{\"schemaVersion\":1,\"nodes\":["
                + "{\"id\":\"t\",\"type\":\"core.manualTrigger\",\"typeVersion\":1,\"name\":\"Start\",\"position\":{\"x\":0,\"y\":0},\"configuration\":{}},"
                + "{\"id\":\"call\",\"type\":\"core.httpRequest\",\"typeVersion\":1,\"name\":\"Call API\",\"position\":{\"x\":0,\"y\":0},\"configuration\":"
                + "{\"method\":\"GET\",\"url\":\"" + server.url("localhost", callPath)
                + "\",\"headers\":[],\"credentialId\":\"" + credentialId + "\",\"query\":[],\"timeoutSeconds\":10}},"
                + "{\"id\":\"log\",\"type\":\"core.logger\",\"typeVersion\":1,\"name\":\"" + loggerName
                + "\",\"position\":{\"x\":0,\"y\":0},\"configuration\":{\"message\":\"status {{ input.status }}\"}}],"
                + "\"edges\":[{\"id\":\"e1\",\"source\":\"t\",\"sourceHandle\":\"main\",\"target\":\"call\",\"targetHandle\":\"main\"},"
                + "{\"id\":\"e2\",\"source\":\"call\",\"sourceHandle\":\"main\",\"target\":\"log\",\"targetHandle\":\"main\"}]}";
    }

    private String createWorkflow(String callPath) throws Exception {
        String body = mvc.perform(post("/api/v1/workflows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"History\",\"definition\":" + definition(callPath, "Log result") + "}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asText();
    }

    private String run(String workflowId, String input) throws Exception {
        var request = post("/api/v1/workflows/" + workflowId + "/execute");
        if (input != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content("{\"input\":" + input + "}");
        }
        String id = json.readTree(mvc.perform(request).andExpect(status().isAccepted()).andReturn().getResponse()
                .getContentAsString()).get("executionId").asText();
        long until = System.currentTimeMillis() + 15_000;
        while (!ExecutionStatus.valueOf(json.readTree(mvc.perform(get("/api/v1/executions/" + id)).andReturn()
                .getResponse().getContentAsString()).get("status").asText()).isTerminal()) {
            if (System.currentTimeMillis() > until) {
                throw new AssertionError("not finished");
            }
            Thread.sleep(20);
        }
        return id;
    }

    private JsonNode fetch(String url) throws Exception {
        return json.readTree(mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void listsExecutionsNewestFirstWithPaginationDurationsAndNodeCounts() throws Exception {
        String ok = createWorkflow("/json");
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ids.add(run(ok, null));
        }

        JsonNode page = fetch("/api/v1/workflows/" + ok + "/executions?size=2");

        assertThat(page.get("totalElements").asInt()).isEqualTo(3);
        assertThat(page.get("totalPages").asInt()).isEqualTo(2);
        assertThat(page.get("items")).hasSize(2);
        assertThat(page.get("items").get(0).get("id").asText()).isEqualTo(ids.get(2));
        assertThat(page.get("items").get(1).get("id").asText()).isEqualTo(ids.get(1));
        JsonNode row = page.get("items").get(0);
        assertThat(row.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(row.get("durationMs").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(row.get("nodeCount").asInt()).isEqualTo(3);
        assertThat(row.get("succeededNodes").asInt()).isEqualTo(3);
        assertThat(row.get("failedNodes").asInt()).isZero();
        assertThat(row.has("failedNodeId")).isFalse();
        assertThat(row.has("nodes")).isFalse();
        assertThat(fetch("/api/v1/workflows/" + ok + "/executions?size=2&page=1").get("items").get(0).get("id").asText())
                .isEqualTo(ids.get(0));
    }

    @Test
    void filtersByStatusAndReportsTheFailedNode() throws Exception {
        String wf = createWorkflow("/json");
        run(wf, null);
        // Same workflow, now pointing at a failing endpoint.
        mvc.perform(put("/api/v1/workflows/" + wf).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"History\",\"lockVersion\":0,\"definition\":" + definition("/fail", "Log result") + "}"))
                .andExpect(status().isOk());
        String failed = run(wf, null);

        JsonNode onlyFailed = fetch("/api/v1/workflows/" + wf + "/executions?status=FAILED");
        assertThat(onlyFailed.get("totalElements").asInt()).isEqualTo(1);
        JsonNode row = onlyFailed.get("items").get(0);
        assertThat(row.get("id").asText()).isEqualTo(failed);
        assertThat(row.get("failedNodeId").asText()).isEqualTo("call");
        assertThat(row.get("failedNodeName").asText()).isEqualTo("Call API");
        assertThat(row.get("failedNodes").asInt()).isEqualTo(1);
        assertThat(row.get("skippedNodes").asInt()).isEqualTo(1);
        assertThat(row.get("error").get("code").asText()).isEqualTo("HTTP_STATUS_ERROR");
        assertThat(row.get("workflowVersion").asInt()).isEqualTo(2);

        assertThat(fetch("/api/v1/workflows/" + wf + "/executions?status=SUCCEEDED&status=FAILED").get("totalElements").asInt())
                .isEqualTo(2);
        assertThat(fetch("/api/v1/workflows/" + wf + "/executions?status=CANCELLED").get("items")).isEmpty();
    }

    @Test
    void nodeEndpointReturnsExactSavedResultsInExecutionOrderWithNamesAndDurations() throws Exception {
        String wf = createWorkflow("/json");
        String exec = run(wf, "{\"order\":7}");

        JsonNode nodes = fetch("/api/v1/executions/" + exec + "/nodes").get("nodes");

        assertThat(nodes).hasSize(3);
        assertThat(nodes.get(0).get("nodeId").asText()).isEqualTo("t");
        assertThat(nodes.get(0).get("sequence").asInt()).isZero();
        assertThat(nodes.get(0).get("nodeName").asText()).isEqualTo("Start");
        assertThat(nodes.get(0).get("output")).isEqualTo(json.readTree("{\"order\":7}"));
        assertThat(nodes.get(1).get("nodeName").asText()).isEqualTo("Call API");
        assertThat(nodes.get(1).get("input")).isEqualTo(json.readTree("{\"order\":7}"));
        assertThat(nodes.get(1).get("output").get("status").asInt()).isEqualTo(200);
        assertThat(nodes.get(1).get("output").get("body")).isEqualTo(json.readTree("{\"id\":\"u-1\",\"ok\":true}"));
        assertThat(nodes.get(2).get("input").get("status").asInt()).isEqualTo(200);
        for (JsonNode n : nodes) {
            assertThat(n.get("durationMs").asLong()).isGreaterThanOrEqualTo(0);
            assertThat(n.get("status").asText()).isEqualTo("SUCCEEDED");
        }
        assertThat(fetch("/api/v1/executions/" + exec + "/nodes?status=FAILED").get("nodes")).isEmpty();
        assertThat(fetch("/api/v1/executions/" + exec + "/nodes?status=SUCCEEDED").get("nodes")).hasSize(3);
        // Same data as the detail endpoint.
        assertThat(fetch("/api/v1/executions/" + exec).get("nodes")).isEqualTo(nodes);
    }

    @Test
    void oldExecutionsStayInspectableAfterTheWorkflowIsEditedArchivedAndHardDeletedSnapshotsIncluded() throws Exception {
        String wf = createWorkflow("/json");
        String exec = run(wf, null);
        JsonNode before = fetch("/api/v1/executions/" + exec);
        mvc.perform(put("/api/v1/workflows/" + wf).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Renamed\",\"lockVersion\":0,\"definition\":" + definition("/json", "Renamed logger")
                        + "}")).andExpect(status().isOk());

        JsonNode after = fetch("/api/v1/executions/" + exec);

        assertThat(after).isEqualTo(before);
        assertThat(after.get("nodes").get(2).get("nodeName").asText()).isEqualTo("Log result");
        assertThat(after.get("workflowVersion").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select workflow_version from workflow_executions where id = ?::uuid", Integer.class,
                exec)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select workflow_snapshot::text from workflow_executions where id = ?::uuid",
                String.class, exec)).contains("Log result").doesNotContain("Renamed logger");

        mvc.perform(delete("/api/v1/workflows/" + wf)).andExpect(status().isNoContent());
        assertThat(fetch("/api/v1/workflows/" + wf + "/executions?status=SUCCEEDED").get("totalElements").asInt()).isEqualTo(1);
        assertThat(fetch("/api/v1/executions/" + exec)).isEqualTo(before);
    }

    @Test
    void storedHistoryNeverContainsCredentialsButDownstreamNodesGotTheRealValues() throws Exception {
        String wf = createWorkflow("/echo");

        String exec = run(wf, "{\"user\":\"ada\",\"password\":\"hunter2\",\"api_key\":\"k-123\"}");
        JsonNode execution = fetch("/api/v1/executions/" + exec);

        assertThat(execution.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(execution.toString()).doesNotContain("hunter2", "k-123", "top-secret-token");
        JsonNode trigger = execution.get("nodes").get(0);
        assertThat(trigger.get("input").get("user").asText()).isEqualTo("ada");
        assertThat(trigger.get("input").get("password").asText()).isEqualTo("[REDACTED]");
        assertThat(trigger.get("output").get("api_key").asText()).isEqualTo("[REDACTED]");
        assertThat(execution.get("nodes").get(1).get("output").get("request").get("headers").get("Authorization").asText())
                .isEqualTo("[REDACTED]");
        // The server really received the secret header (only history is redacted).
        assertThat(server.last().headers().get("authorization")).isEqualTo("Bearer top-secret-token");
        assertThat(jdbc.queryForObject("select count(*) from node_executions where input::text like '%hunter2%' "
                + "or output::text like '%hunter2%'", Integer.class)).isZero();
    }

    @Test
    void logsAreCappedAndSanitized() throws Exception {
        String body = "{\"name\":\"Logs\",\"definition\":{\"schemaVersion\":1,\"nodes\":["
                + "{\"id\":\"t\",\"type\":\"core.manualTrigger\",\"typeVersion\":1,\"name\":\"Start\",\"position\":{\"x\":0,\"y\":0},\"configuration\":{}},"
                + "{\"id\":\"l\",\"type\":\"core.logger\",\"typeVersion\":1,\"name\":\"Log\",\"position\":{\"x\":0,\"y\":0},"
                + "\"configuration\":{\"message\":\"x {{ input.note }} password=hunter2\"}}],"
                + "\"edges\":[{\"id\":\"e\",\"source\":\"t\",\"sourceHandle\":\"main\",\"target\":\"l\",\"targetHandle\":\"main\"}]}}";
        String wf = json.readTree(mvc.perform(post("/api/v1/workflows").contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString()).get("id").asText();

        String exec = run(wf, "{\"note\":\"" + "n".repeat(5000) + "\"}");
        JsonNode logs = fetch("/api/v1/executions/" + exec).get("logs");

        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).get("message").asText()).hasSizeLessThanOrEqualTo(2000).doesNotContain("hunter2");
    }

    @Test
    void validatesParametersAndMissingResources() throws Exception {
        String wf = createWorkflow("/json");
        mvc.perform(get("/api/v1/workflows/" + wf + "/executions?size=101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/workflows/" + wf + "/executions?page=-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/workflows/" + wf + "/executions?status=NOPE")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/workflows/" + UUID.randomUUID() + "/executions")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/executions/" + UUID.randomUUID() + "/nodes")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/executions/" + UUID.randomUUID() + "/nodes").param("status", "BAD"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/workflows/" + wf + "/executions")).andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0));
    }
}
