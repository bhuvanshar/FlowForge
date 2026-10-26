package com.flowforge.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowforge.application.execution.ExecutionStateManager;
import com.flowforge.domain.execution.ExecutionStatus;
import com.flowforge.support.Credentials;
import com.flowforge.support.MockAiServer;
import com.flowforge.support.MockAiServer.Reply;
import com.flowforge.support.PostgresIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Acceptance for Module 13: the sample workflow Manual Trigger -> Set Variables -> AI Model -> Condition -> Logger runs
 * end to end against a mocked OpenAI-compatible provider, with the API key coming from a stored credential only.
 */
class AiWorkflowIT extends PostgresIntegrationTest {

    private static final String API_KEY = "sk-it-9f8e7d6c5b4a39281706";
    private static MockAiServer ai;

    @BeforeAll
    static void startServer() throws Exception {
        ai = new MockAiServer();
    }

    @AfterAll
    static void stopServer() {
        ai.close();
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ExecutionStateManager states;

    private String credentialId;

    @BeforeEach
    void setUp() throws Exception {
        long until = System.currentTimeMillis() + 5_000;
        while (states.activeCount() > 0 && System.currentTimeMillis() < until) {
            Thread.sleep(10);
        }
        jdbc.execute("TRUNCATE workflows CASCADE");
        jdbc.execute("TRUNCATE credentials");
        ai.reset();
        credentialId = call(post("/api/v1/credentials"), Credentials.bearer("Mock AI", API_KEY, "localhost"), 201)
                .get("id").asText();
    }

    private JsonNode call(MockHttpServletRequestBuilder request, String body, int expected) throws Exception {
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        String response = mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse()
                .getContentAsString();
        return response.isEmpty() ? null : json.readTree(response);
    }

    /** Creates the sample with the stored credential and points its AI node at the mock server. */
    private String sampleWorkflow() throws Exception {
        JsonNode created = call(post("/api/v1/samples/ai-review-triage/create"), "{\"credentialId\":\"" + credentialId + "\"}",
                201);
        ObjectNode definition = (ObjectNode) created.get("definition");
        for (JsonNode node : definition.get("nodes")) {
            if (node.get("type").asText().equals("core.aiModel")) {
                ((ObjectNode) node.get("configuration")).put("baseUrl", ai.baseUrl());
            }
        }
        call(put("/api/v1/workflows/" + created.get("id").asText()), "{\"name\":\"AI triage\",\"definition\":" + definition
                + ",\"lockVersion\":" + created.get("lockVersion").asLong() + "}", 200);
        return created.get("id").asText();
    }

    private JsonNode run(String workflowId, String input) throws Exception {
        JsonNode started = call(post("/api/v1/workflows/" + workflowId + "/execute"), input == null ? null
                : "{\"input\":" + input + "}", 202);
        String id = started.get("executionId").asText();
        long until = System.currentTimeMillis() + 15_000;
        while (true) {
            JsonNode execution = call(get("/api/v1/executions/" + id), null, 200);
            if (ExecutionStatus.valueOf(execution.get("status").asText()).isTerminal()) {
                return execution;
            }
            assertThat(System.currentTimeMillis()).as("execution finished").isLessThan(until);
            Thread.sleep(25);
        }
    }

    private static JsonNode nodeRun(JsonNode execution, String nodeId) {
        for (JsonNode n : execution.get("nodes")) {
            if (n.get("nodeId").asText().equals(nodeId)) {
                return n;
            }
        }
        throw new AssertionError("No node run " + nodeId);
    }

    private static List<String> logs(JsonNode execution) {
        List<String> out = new ArrayList<>();
        execution.get("logs").forEach(l -> out.add(l.get("level").asText() + " " + l.get("message").asText()));
        return out;
    }

    private static final String NEGATIVE = "{\"sentiment\":\"negative\",\"urgency\":4,\"summary\":\"Late, damaged parcel and no replies\"}";
    private static final String POSITIVE = "{\"sentiment\":\"positive\",\"urgency\":1,\"summary\":\"Happy customer\"}";

    // ----------------------------------------------------------------------------------------------------------------

    @Test
    void theSampleWorkflowIsListedValidAndRunsTheNegativeBranch() throws Exception {
        mvc.perform(get("/api/v1/samples")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("ai-review-triage"))
                .andExpect(jsonPath("$[0].name").value("AI review triage (sample)"));
        String workflowId = sampleWorkflow();
        JsonNode validation = call(post("/api/v1/workflows/" + workflowId + "/validate"), null, 200);
        assertThat(validation.get("valid").asBoolean()).as(validation.toString()).isTrue();
        assertThat(validation.get("errorCount").asInt()).isZero();

        ai.reply(Reply.content(NEGATIVE));
        JsonNode execution = run(workflowId, null); // uses the trigger's test input (the sample review)

        assertThat(execution.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(execution.get("nodes")).extracting(n -> n.get("nodeId").asText() + "=" + n.get("status").asText())
                .containsExactly("start=SUCCEEDED", "prepare=SUCCEEDED", "ai=SUCCEEDED", "check=SUCCEEDED",
                        "escalate=SUCCEEDED", "archive=SKIPPED");

        // The provider got the interpolated prompt, the JSON constraints and the key from the credential.
        MockAiServer.Received sent = ai.lastRequest();
        assertThat(sent.headers().get("authorization")).isEqualTo("Bearer " + API_KEY);
        JsonNode body = sent.json();
        assertThat(body.get("model").asText()).isEqualTo("gpt-4o-mini");
        assertThat(body.get("response_format").get("type").asText()).isEqualTo("json_object");
        assertThat(body.get("messages").get(1).get("content").asText()).contains("<review>")
                .contains("The package arrived two weeks late");
        assertThat(body.get("messages").get(0).get("content").asText()).contains("JSON Schema").contains("urgency");

        // The structured answer flows to the next nodes.
        JsonNode aiOut = nodeRun(execution, "ai").get("output");
        assertThat(aiOut.get("json").get("sentiment").asText()).isEqualTo("negative");
        assertThat(aiOut.get("json").get("urgency").asInt()).isEqualTo(4);
        assertThat(aiOut.get("usage").get("total").asInt()).isEqualTo(19);
        assertThat(nodeRun(execution, "check").get("output").get("json").get("summary").asText()).startsWith("Late");
        assertThat(logs(execution)).anyMatch(l -> l.equals("INFO Escalate to support: Late, damaged parcel and no replies "
                + "(urgency 4)")).anyMatch(l -> l.startsWith("INFO AI call finished: model test-model-2026"));
    }

    @Test
    void thePositiveReviewTakesTheOtherBranch() throws Exception {
        ai.reply(Reply.content(POSITIVE));
        JsonNode execution = run(sampleWorkflow(), "{\"review\":\"Fast delivery, lovely product!\"}");

        assertThat(execution.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(nodeRun(execution, "escalate").get("status").asText()).isEqualTo("SKIPPED");
        assertThat(nodeRun(execution, "archive").get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(logs(execution)).contains("INFO No escalation needed: review is positive");
        assertThat(ai.lastRequest().json().get("messages").get(1).get("content").asText()).contains("Fast delivery");
    }

    @Test
    void theApiKeyNeverAppearsInWorkflowsHistoryLogsOrResponsesEvenWhenTheModelEchoesIt() throws Exception {
        String workflowId = sampleWorkflow();
        // A hostile or careless model repeats the key it must not know (here: through the summary the Logger prints).
        ai.reply(Reply.content("{\"sentiment\":\"negative\",\"urgency\":5,\"summary\":\"leaked " + API_KEY + "\"}"));

        JsonNode execution = run(workflowId, null);

        assertThat(execution.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(execution.toString()).doesNotContain(API_KEY).contains("[REDACTED]");
        assertThat(call(get("/api/v1/workflows/" + workflowId), null, 200).toString()).doesNotContain(API_KEY)
                .contains(credentialId);
        assertThat(call(get("/api/v1/workflows/" + workflowId + "/executions"), null, 200).toString())
                .doesNotContain(API_KEY);
        for (String table : List.of("workflows", "workflow_executions", "node_executions", "execution_logs", "credentials")) {
            assertThat(jdbc.queryForObject("select count(*) from " + table + " t where t::text like ?", Integer.class,
                    "%" + API_KEY + "%")).as(table).isZero();
        }
        assertThat(nodeRun(execution, "ai").get("output").get("request").get("authenticatedWith").get("name").asText())
                .isEqualTo("Mock AI");
    }

    @Test
    void malformedAnswersAreRepairedWithinTheRetryBoundAndUsageIsSummed() throws Exception {
        ai.reply(Reply.content("Here is my analysis: negative, quite urgent."), Reply.content(NEGATIVE));

        JsonNode execution = run(sampleWorkflow(), null);

        assertThat(execution.get("status").asText()).isEqualTo("SUCCEEDED");
        JsonNode aiOut = nodeRun(execution, "ai").get("output");
        assertThat(aiOut.get("attempts").asInt()).isEqualTo(2);
        assertThat(aiOut.get("usage").get("total").asInt()).isEqualTo(38);
        assertThat(ai.requests()).hasSize(2);
        assertThat(logs(execution)).anyMatch(l -> l.startsWith("WARN The model's reply was unusable"));
    }

    @Test
    void answersThatKeepViolatingTheSchemaFailTheExecutionWithAClearError() throws Exception {
        ai.reply(Reply.content("{\"sentiment\":\"angry\",\"urgency\":9,\"summary\":\"x\"}")).repeatLast();

        JsonNode execution = run(sampleWorkflow(), null);

        assertThat(execution.get("status").asText()).isEqualTo("FAILED");
        assertThat(execution.get("error").get("code").asText()).isEqualTo("AI_OUTPUT_INVALID");
        assertThat(execution.get("error").get("message").asText()).contains("Classify review")
                .contains("does not match the response schema").contains("after 3 attempts");
        assertThat(nodeRun(execution, "check").get("status").asText()).isEqualTo("SKIPPED");
        assertThat(ai.requests()).hasSize(3);
    }

    @Test
    void providerFailuresAndRateLimitsAreReportedAndBounded() throws Exception {
        String workflowId = sampleWorkflow();

        ai.reply(Reply.error(429, "Rate limit reached for requests"), Reply.error(429, "Rate limit reached for requests"),
                Reply.content(POSITIVE));
        JsonNode recovered = run(workflowId, null);
        assertThat(recovered.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(nodeRun(recovered, "ai").get("output").get("attempts").asInt()).isEqualTo(3);

        ai.reset();
        ai.reply(Reply.error(401, "Incorrect API key provided: " + API_KEY.substring(0, 8) + "****")).repeatLast();
        JsonNode rejected = run(workflowId, null);
        assertThat(rejected.get("status").asText()).isEqualTo("FAILED");
        assertThat(rejected.get("error").get("code").asText()).isEqualTo("AI_AUTH_FAILED");
        assertThat(rejected.toString()).doesNotContain("Incorrect API key").doesNotContain(API_KEY.substring(0, 8));
        assertThat(ai.requests()).hasSize(1);

        ai.reset();
        ai.reply(Reply.error(503, "overloaded")).repeatLast();
        JsonNode down = run(workflowId, null);
        assertThat(down.get("error").get("code").asText()).isEqualTo("AI_PROVIDER_ERROR");
        assertThat(ai.requests()).hasSize(3);
    }

    @Test
    void theCredentialIsCheckedWhenValidatingAndActivating() throws Exception {
        String workflowId = sampleWorkflow();
        jdbc.update("update credentials set owner_id = 'someone-else'"); // not ours any more

        JsonNode validation = call(post("/api/v1/workflows/" + workflowId + "/validate"), null, 200);
        assertThat(validation.get("valid").asBoolean()).isFalse();
        assertThat(validation.get("issues").toString()).contains("credentialId")
                .contains("refers to a credential that does not exist");
        call(post("/api/v1/workflows/" + workflowId + "/activate"), null, 422);
        call(post("/api/v1/workflows/" + workflowId + "/execute"), null, 422);
        assertThat(ai.requests()).isEmpty();
    }

    @Test
    void aCredentialForAnotherHostIsNeverSentToTheModelEndpoint() throws Exception {
        credentialId = call(post("/api/v1/credentials"), Credentials.bearer("OpenAI only", API_KEY, "api.openai.com"), 201)
                .get("id").asText();
        JsonNode execution = run(sampleWorkflow(), null);

        assertThat(execution.get("status").asText()).isEqualTo("FAILED");
        assertThat(execution.get("error").get("code").asText()).isEqualTo("CREDENTIAL_HOST_NOT_ALLOWED");
        assertThat(ai.requests()).isEmpty();
    }

    @Test
    void sampleCreationValidatesItsInput() throws Exception {
        mvc.perform(post("/api/v1/samples/nope/create")).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/samples/ai-review-triage/create").contentType(MediaType.APPLICATION_JSON)
                .content("{\"credentialId\":\"sk-not-an-id\"}")).andExpect(status().isBadRequest());
        JsonNode plain = call(post("/api/v1/samples/ai-review-triage/create"), null, 201);
        assertThat(plain.get("status").asText()).isEqualTo("DRAFT");
        assertThat(plain.get("definition").get("nodes")).hasSize(6);
        assertThat(plain.toString()).doesNotContain("credentialId");
        mvc.perform(post("/api/v1/samples/ai-review-triage/create").header("Origin", "https://evil.example")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
    }
}
