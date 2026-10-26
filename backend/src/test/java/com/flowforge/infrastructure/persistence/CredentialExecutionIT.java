package com.flowforge.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.application.execution.ExecutionStateManager;
import com.flowforge.domain.execution.ExecutionStatus;
import com.flowforge.support.Credentials;
import com.flowforge.support.LocalHttpServer;
import com.flowforge.support.PostgresIntegrationTest;
import com.flowforge.support.Workflows;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Acceptance: workflows use stored credentials, the server receives the secret, and it appears nowhere in the workflow
 * JSON, the execution history, the logs, the events or any API response, even when the remote server echoes it back.
 */
class CredentialExecutionIT extends PostgresIntegrationTest {

    private static final String TOKEN = "tok_live_EchoedBackByServer_77";
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

    @BeforeEach
    void clean() throws InterruptedException {
        long until = System.currentTimeMillis() + 5_000;
        while (states.activeCount() > 0 && System.currentTimeMillis() < until) {
            Thread.sleep(10);
        }
        jdbc.execute("TRUNCATE workflows CASCADE");
        jdbc.execute("TRUNCATE credentials");
    }

    private JsonNode call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, String body,
                          int expected) throws Exception {
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        String response = mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse()
                .getContentAsString();
        return response.isEmpty() ? null : json.readTree(response);
    }

    private String credential(String body) throws Exception {
        return call(post("/api/v1/credentials"), body, 201).get("id").asText();
    }

    /** trigger -> call (HTTP GET with the credential) -> log ("{{ input.body }}", which contains the echo). */
    private String workflow(String credentialId, String path) throws Exception {
        String definition = Workflows.definition(List.of(
                Workflows.node("t", "core.manualTrigger", "{}"),
                Workflows.node("call", "core.httpRequest", "{\"method\":\"GET\",\"url\":\"" + server.url("localhost", path)
                        + "\",\"headers\":[],\"query\":[],\"timeoutSeconds\":10,\"credentialId\":\"" + credentialId + "\"}"),
                Workflows.node("log", "core.logger", "{\"message\":\"server said {{ input.body }}\"}")),
                List.of(Workflows.edge("t", "call"), Workflows.edge("call", "log")));
        return call(post("/api/v1/workflows"), "{\"name\":\"Uses credential\",\"definition\":" + definition + "}", 201)
                .get("id").asText();
    }

    private JsonNode runToEnd(String workflowId) throws Exception {
        String executionId = call(post("/api/v1/workflows/" + workflowId + "/execute"), null, 202).get("executionId")
                .asText();
        long until = System.currentTimeMillis() + 10_000;
        while (true) {
            JsonNode execution = call(get("/api/v1/executions/" + executionId), null, 200);
            if (ExecutionStatus.valueOf(execution.get("status").asText()).isTerminal()) {
                return execution;
            }
            assertThat(System.currentTimeMillis()).isLessThan(until);
            Thread.sleep(25);
        }
    }

    private void assertNoSecretAnywhere(String secret) {
        for (String table : List.of("workflows", "workflow_executions", "node_executions", "execution_logs",
                "credentials")) {
            assertThat(jdbc.queryForObject("select count(*) from " + table + " t where t::text like ?", Integer.class,
                    "%" + secret + "%")).as(table).isZero();
        }
    }

    @Test
    void aStoredCredentialAuthenticatesTheRequestAndNeverAppearsInHistoryLogsOrApis() throws Exception {
        String credentialId = credential(Credentials.bearer("Echo API", TOKEN, "localhost"));
        String workflowId = workflow(credentialId, "/echo");

        JsonNode execution = runToEnd(workflowId);

        assertThat(execution.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(server.last().headers().get("authorization")).isEqualTo("Bearer " + TOKEN);
        // The echo server returned the header in its body; history, logs and responses only show [REDACTED].
        assertThat(execution.toString()).doesNotContain(TOKEN).contains("[REDACTED]");
        JsonNode callNode = execution.get("nodes").get(1);
        assertThat(callNode.get("output").get("request").get("authenticatedWith").get("id").asText()).isEqualTo(credentialId);
        assertThat(execution.get("logs").toString()).doesNotContain(TOKEN).contains("server said");
        assertThat(call(get("/api/v1/workflows/" + workflowId), null, 200).toString()).doesNotContain(TOKEN)
                .contains(credentialId);
        assertThat(call(get("/api/v1/workflows/" + workflowId + "/executions"), null, 200).toString()).doesNotContain(TOKEN);
        assertNoSecretAnywhere(TOKEN);
        assertThat(call(get("/api/v1/credentials/" + credentialId), null, 200).get("lastUsedAt").asText()).isNotBlank();
    }

    @Test
    void basicAndHeaderCredentialsAreMaskedIncludingDerivedValues() throws Exception {
        String basic = credential(Credentials.basic("Basic API", "ada", "correct-horse-battery", "localhost"));
        JsonNode first = runToEnd(workflow(basic, "/echo"));
        String encoded = java.util.Base64.getEncoder().encodeToString("ada:correct-horse-battery".getBytes());
        assertThat(server.last().headers().get("authorization")).isEqualTo("Basic " + encoded);
        assertThat(first.toString()).doesNotContain("correct-horse-battery").doesNotContain(encoded);

        String header = credential(Credentials.header("Key API", "X-Service-Key", "svc_key_ABCDEF123456", "localhost"));
        JsonNode second = runToEnd(workflow(header, "/echo"));
        assertThat(server.last().headers().get("x-service-key")).isEqualTo("svc_key_ABCDEF123456");
        assertThat(second.toString()).doesNotContain("svc_key_ABCDEF123456");
        assertNoSecretAnywhere("correct-horse-battery");
        assertNoSecretAnywhere(encoded);
        assertNoSecretAnywhere("svc_key_ABCDEF123456");
    }

    @Test
    void rotationTakesEffectForTheNextRun() throws Exception {
        String credentialId = credential(Credentials.bearer("Rotating", "tok_first_000000", "localhost"));
        String workflowId = workflow(credentialId, "/json");
        runToEnd(workflowId);
        assertThat(server.last().headers().get("authorization")).isEqualTo("Bearer tok_first_000000");

        call(post("/api/v1/credentials/" + credentialId + "/rotate"), "{\"secret\":{\"token\":\"tok_second_00000\"}}", 200);
        runToEnd(workflowId);
        assertThat(server.last().headers().get("authorization")).isEqualTo("Bearer tok_second_00000");
    }

    @Test
    void credentialsAreOnlySentToTheirAllowedHosts() throws Exception {
        String credentialId = credential(Credentials.bearer("Elsewhere", TOKEN, "api.example.com"));
        int before = server.received().size();

        JsonNode execution = runToEnd(workflow(credentialId, "/echo"));

        assertThat(execution.get("status").asText()).isEqualTo("FAILED");
        assertThat(execution.get("error").get("code").asText()).isEqualTo("CREDENTIAL_HOST_NOT_ALLOWED");
        assertThat(server.received()).hasSize(before);
    }

    @Test
    void referencesAreValidatedAndForeignOrBrokenCredentialsCannotBeUsed() throws Exception {
        String credentialId = credential(Credentials.bearer("Foreign", TOKEN, "localhost"));
        String workflowId = workflow(credentialId, "/echo");

        // Another owner's credential behaves like a missing one: validation issue, activation refused, run fails.
        jdbc.update("update credentials set owner_id = 'someone-else'");
        JsonNode validation = call(post("/api/v1/workflows/" + workflowId + "/validate"), null, 200);
        assertThat(validation.get("valid").asBoolean()).isFalse();
        assertThat(validation.get("issues").toString()).contains("credentialId")
                .contains("refers to a credential that does not exist");
        call(post("/api/v1/workflows/" + workflowId + "/activate"), null, 422);
        call(post("/api/v1/workflows/" + workflowId + "/execute"), null, 422);

        // Owned again but with tampered ciphertext: the run fails with a generic message, nothing leaks.
        jdbc.update("update credentials set owner_id = 'local', secret_ciphertext = "
                + "overlay(secret_ciphertext placing '\\xff'::bytea from 5 for 1)");
        JsonNode execution = runToEnd(workflowId);
        assertThat(execution.get("error").get("code").asText()).isEqualTo("CREDENTIAL_UNAVAILABLE");
        assertThat(execution.toString()).doesNotContain(TOKEN);
    }

    @Test
    void literalSecretsInWorkflowJsonAreRejected() throws Exception {
        String definition = Workflows.definition(List.of(Workflows.node("t", "core.manualTrigger", "{}"),
                Workflows.node("call", "core.httpRequest", "{\"method\":\"GET\",\"url\":\"https://api.example.com\","
                        + "\"headers\":[{\"name\":\"Authorization\",\"value\":\"Bearer " + TOKEN + "\"}],\"query\":[],"
                        + "\"timeoutSeconds\":10}")), List.of(Workflows.edge("t", "call")));
        String workflowId = call(post("/api/v1/workflows"), "{\"name\":\"Plain secret\",\"definition\":" + definition
                + "}", 201).get("id").asText();
        JsonNode problem = call(post("/api/v1/workflows/" + workflowId + "/activate"), null, 422);
        assertThat(problem.get("issues").toString()).contains("use a credential");
        call(post("/api/v1/workflows/" + workflowId + "/execute"), null, 422);
    }
}
