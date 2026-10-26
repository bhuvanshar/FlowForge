package com.flowforge.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.application.execution.ExecutionStateManager;
import com.flowforge.domain.execution.ExecutionStatus;
import com.flowforge.support.PostgresIntegrationTest;
import com.flowforge.support.Workflows;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** What the dashboard needs from the API: last execution per listed workflow, and read-only settings. */
class DashboardApiIT extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ExecutionStateManager states;

    @BeforeEach
    void clean() throws InterruptedException {
        long until = System.currentTimeMillis() + 5_000;
        while (states.activeCount() > 0 && System.currentTimeMillis() < until) {
            Thread.sleep(10);
        }
        jdbc.execute("TRUNCATE workflows CASCADE");
    }

    private String create(String name, String definition) throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/workflows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"definition\":" + definition + "}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
    }

    private JsonNode list(String query) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/workflows" + query)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString());
    }

    private static JsonNode byName(JsonNode page, String name) {
        for (JsonNode item : page.get("items")) {
            if (item.get("name").asText().equals(name)) {
                return item;
            }
        }
        throw new AssertionError("No workflow " + name + " in " + page);
    }

    private String runToEnd(String workflowId) throws Exception {
        String id = json.readTree(mvc.perform(post("/api/v1/workflows/" + workflowId + "/execute"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString()).get("executionId").asText();
        long until = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < until) {
            JsonNode execution = json.readTree(mvc.perform(get("/api/v1/executions/" + id)).andReturn().getResponse()
                    .getContentAsString());
            if (ExecutionStatus.valueOf(execution.get("status").asText()).isTerminal()) {
                return id;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("execution did not finish");
    }

    @Test
    void theListCarriesTheNewestExecutionOfEachWorkflow() throws Exception {
        String ok = create("Runs fine", Workflows.manual());
        // A condition comparing text with "greater than" fails at run time (TYPE_MISMATCH).
        String broken = create("Fails", Workflows.definition(List.of(
                Workflows.node("t", "core.manualTrigger", "{\"testInput\":{\"name\":\"abc\"}}"),
                Workflows.node("c", "core.condition", "{\"field\":\"name\",\"operator\":\"greaterThan\",\"value\":\"5\"}"),
                Workflows.node("l", "core.logger", "{\"message\":\"big\"}")),
                List.of(Workflows.edge("t", "c"), "{\"id\":\"c-l\",\"source\":\"c\",\"sourceHandle\":\"true\","
                        + "\"target\":\"l\",\"targetHandle\":\"main\"}")));
        create("Never ran", Workflows.manual());
        String first = runToEnd(ok);
        String second = runToEnd(ok); // newest wins
        String failed = runToEnd(broken);

        JsonNode page = list("?size=10");

        JsonNode last = byName(page, "Runs fine").get("lastExecution");
        assertThat(last.get("id").asText()).isEqualTo(second).isNotEqualTo(first);
        assertThat(last.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(last.get("triggerType").asText()).isEqualTo("MANUAL");
        assertThat(last.get("createdAt").asText()).isNotBlank();
        assertThat(last.get("finishedAt").asText()).isNotBlank();
        assertThat(byName(page, "Fails").get("lastExecution").get("id").asText()).isEqualTo(failed);
        assertThat(byName(page, "Fails").get("lastExecution").get("status").asText()).isEqualTo("FAILED");
        assertThat(byName(page, "Never ran").has("lastExecution")).isFalse();
    }

    @Test
    void anInFlightExecutionHasNoFinishTime() throws Exception {
        String id = create("Busy", Workflows.manual());
        jdbc.update("insert into workflow_executions (id, workflow_id, workflow_version, workflow_snapshot, status) "
                + "select gen_random_uuid(), id, version, definition, 'QUEUED' from workflows where id = ?::uuid", id);

        JsonNode last = byName(list(""), "Busy").get("lastExecution");

        assertThat(last.get("status").asText()).isEqualTo("QUEUED");
        assertThat(last.has("finishedAt")).isFalse();
    }

    @Test
    void sortingAndFilteringStillWorkWithTheExtraField() throws Exception {
        create("b-workflow", Workflows.manual());
        create("a-workflow", Workflows.manual());
        JsonNode sorted = list("?sort=name,asc&name=workflow");
        assertThat(List.of(sorted.get("items").get(0).get("name").asText(), sorted.get("items").get(1).get("name").asText()))
                .containsExactly("a-workflow", "b-workflow");
        assertThat(list("?name=nothing-like-this").get("items")).isEmpty();
    }

    @Test
    void settingsAreReadOnlyAndContainNoSecrets() throws Exception {
        String body = mvc.perform(get("/api/v1/settings")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andExpect(jsonPath("$.execution.workerThreads").isNumber())
                .andExpect(jsonPath("$.execution.maxActivePerWorkflow").value(10))
                .andExpect(jsonPath("$.outbound.allowedHosts[0]").value("localhost"))
                .andExpect(jsonPath("$.outbound.allowPrivateAddresses").value(true))
                .andExpect(jsonPath("$.triggers.schedulerEnabled").value(false))
                .andExpect(jsonPath("$.triggers.allowUnauthenticatedWebhooks").value(false))
                .andExpect(jsonPath("$.credentials.encryptionAvailable").value(true))
                .andExpect(jsonPath("$.credentials.activeKeyId").value("test-1"))
                .andExpect(jsonPath("$.security.allowedHostNames[0]").value("localhost"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(com.flowforge.support.TestKeys.KEY).doesNotContain("password");
        mvc.perform(post("/api/v1/settings")).andExpect(status().isMethodNotAllowed());
        mvc.perform(get("/api/v1/settings").header("Host", "attacker.example")).andExpect(status().isForbidden());
    }
}
