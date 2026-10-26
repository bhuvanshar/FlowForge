package com.flowforge.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.application.trigger.WebhookSignatures;
import com.flowforge.domain.execution.ExecutionStatus;
import com.flowforge.support.PostgresIntegrationTest;
import com.flowforge.support.Workflows;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Webhook deliveries end to end: authentication, request validation, input mapping and the resulting execution. */
class WebhookApiIT extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    com.flowforge.application.execution.ExecutionStateManager states;

    @BeforeEach
    void clean() throws InterruptedException {
        long until = System.currentTimeMillis() + 5_000;
        while (states.activeCount() > 0 && System.currentTimeMillis() < until) {
            Thread.sleep(10); // let runs accepted by the previous test finish before their rows are truncated
        }
        jdbc.execute("TRUNCATE workflows CASCADE");
    }

    private record Hook(String workflowId, String webhookId, String secret) {

        String url() {
            return "/api/v1/hooks/" + webhookId;
        }
    }

    private Hook activeHook(String definition) throws Exception {
        String id = json.readTree(mvc.perform(post("/api/v1/workflows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Hooked\",\"definition\":" + definition + "}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
        mvc.perform(post("/api/v1/workflows/" + id + "/activate")).andExpect(status().isOk());
        String webhookId = read(get("/api/v1/workflows/" + id + "/triggers")).get("webhook").get("webhookId").asText();
        String secret = read(get("/api/v1/workflows/" + id + "/triggers/webhook/secret")).get("secret").asText();
        return new Hook(id, webhookId, secret);
    }

    private JsonNode read(MockHttpServletRequestBuilder request) throws Exception {
        return json.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString());
    }

    private static MockHttpServletRequestBuilder signed(Hook hook, HttpMethod method, String body, long timestamp) {
        String ts = Long.toString(timestamp);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequestBuilder request = request(method, hook.url())
                .header("X-FlowForge-Timestamp", ts)
                .header("X-FlowForge-Signature", WebhookSignatures.sign(hook.secret(), ts, bytes));
        return body.isEmpty() ? request : request.contentType(MediaType.APPLICATION_JSON).content(bytes);
    }

    private static MockHttpServletRequestBuilder signed(Hook hook, String body) {
        return signed(hook, HttpMethod.POST, body, Instant.now().getEpochSecond());
    }

    private String accepted(ResultActions result, Hook hook) throws Exception {
        String response = result.andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.workflowId").value(hook.workflowId()))
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/v1/executions/")))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("executionId").asText();
    }

    private JsonNode awaitFinished(String executionId) throws Exception {
        long until = System.currentTimeMillis() + 10_000;
        while (true) {
            JsonNode execution = read(get("/api/v1/executions/" + executionId));
            if (ExecutionStatus.valueOf(execution.get("status").asText()).isTerminal()) {
                return execution;
            }
            if (System.currentTimeMillis() > until) {
                throw new AssertionError("Timed out; last state " + execution);
            }
            Thread.sleep(25);
        }
    }

    private static JsonNode triggerOutput(JsonNode execution) {
        return execution.get("nodes").get(0).get("output");
    }

    private ResultActions problem(ResultActions result, int status, String code) throws Exception {
        return result.andExpect(status().is(status))
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.type").value("https://flowforge.local/problems/" + code));
    }

    private int executionCount() {
        return jdbc.queryForObject("select count(*) from workflow_executions", Integer.class);
    }

    // ----------------------------------------------------------------------------------------------------------------

    @Test
    void signedDeliveryRunsTheWorkflowWithTheBodyAsTriggerInput() throws Exception {
        Hook hook = activeHook(Workflows.webhook("POST", "signature", "body"));

        String executionId = accepted(mvc.perform(signed(hook, "{\"email\":\"a@example.com\",\"n\":[1,2]}")), hook);
        JsonNode done = awaitFinished(executionId);

        assertThat(done.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(done.get("triggerType").asText()).isEqualTo("WEBHOOK");
        assertThat(done.has("scheduledFor")).isFalse();
        assertThat(triggerOutput(done)).isEqualTo(json.readTree("{\"email\":\"a@example.com\",\"n\":[1,2]}"));
        JsonNode history = read(get("/api/v1/workflows/" + hook.workflowId() + "/executions"));
        assertThat(history.get("items").get(0).get("triggerType").asText()).isEqualTo("WEBHOOK");
    }

    @Test
    void signatureFailuresAreUnauthorizedAndCreateNothing() throws Exception {
        Hook hook = activeHook(Workflows.webhook("POST", "signature", "body"));
        long now = Instant.now().getEpochSecond();

        problem(mvc.perform(post(hook.url()).contentType(MediaType.APPLICATION_JSON).content("{}")), 401,
                "unauthorized").andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("Missing X-FlowForge-Timestamp")));
        problem(mvc.perform(post(hook.url()).contentType(MediaType.APPLICATION_JSON).content("{}")
                .header("X-FlowForge-Timestamp", Long.toString(now))
                .header("X-FlowForge-Signature", WebhookSignatures.sign("wrong-secret", Long.toString(now),
                        "{}".getBytes(StandardCharsets.UTF_8)))), 401, "unauthorized")
                .andExpect(jsonPath("$.detail").value("Invalid webhook signature."));
        // Body changed after signing.
        problem(mvc.perform(signed(hook, HttpMethod.POST, "{}", now).content("{\"x\":1}")), 401, "unauthorized");
        // Correctly signed but outside the 5 minute window (stale or from the future).
        problem(mvc.perform(signed(hook, HttpMethod.POST, "{}", now - 301)), 401, "unauthorized")
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("outside the accepted")));
        problem(mvc.perform(signed(hook, HttpMethod.POST, "{}", now + 301)), 401, "unauthorized");
        problem(mvc.perform(post(hook.url()).header("X-FlowForge-Timestamp", "yesterday")
                .header("X-FlowForge-Signature", "sha256=00")), 401, "unauthorized");

        assertThat(executionCount()).isZero();
    }

    @Test
    void aSignedDeliveryCannotBeReplayed() throws Exception {
        Hook hook = activeHook(Workflows.webhook("POST", "signature", "body"));
        long now = Instant.now().getEpochSecond();

        accepted(mvc.perform(signed(hook, HttpMethod.POST, "{\"id\":1}", now)), hook);
        problem(mvc.perform(signed(hook, HttpMethod.POST, "{\"id\":1}", now)), 409, "webhook-replayed");
        // A new timestamp is a new delivery.
        accepted(mvc.perform(signed(hook, HttpMethod.POST, "{\"id\":1}", now + 1)), hook);
        assertThat(executionCount()).isEqualTo(2);
    }

    @Test
    void tokenAuthenticationAcceptsBearerOrTokenHeader() throws Exception {
        Hook hook = activeHook(Workflows.webhook("PUT", "token", "body"));

        accepted(mvc.perform(request(HttpMethod.PUT, hook.url()).header("Authorization", "Bearer " + hook.secret())
                .contentType(MediaType.APPLICATION_JSON).content("{\"a\":1}")), hook);
        accepted(mvc.perform(request(HttpMethod.PUT, hook.url()).header("X-FlowForge-Token", hook.secret())), hook);

        problem(mvc.perform(request(HttpMethod.PUT, hook.url()).header("Authorization", "Bearer nope")), 401,
                "unauthorized").andExpect(header().string("WWW-Authenticate", "Bearer realm=\"flowforge-webhook\""));
        problem(mvc.perform(request(HttpMethod.PUT, hook.url())), 401, "unauthorized");
        assertThat(executionCount()).isEqualTo(2);
    }

    @Test
    void wrongMethodUnknownIdAndInactiveWorkflowsAreRejected() throws Exception {
        Hook hook = activeHook(Workflows.webhook("POST", "token", "body"));

        problem(mvc.perform(get(hook.url()).header("X-FlowForge-Token", hook.secret())), 405, "method-not-allowed")
                .andExpect(header().string("Allow", "POST"));
        problem(mvc.perform(post("/api/v1/hooks/doesNotExist000000000").header("X-FlowForge-Token", hook.secret())),
                404, "not-found");

        mvc.perform(post("/api/v1/workflows/" + hook.workflowId() + "/deactivate")).andExpect(status().isOk());
        problem(mvc.perform(post(hook.url()).header("X-FlowForge-Token", hook.secret())), 404, "not-found");

        mvc.perform(post("/api/v1/workflows/" + hook.workflowId() + "/activate")).andExpect(status().isOk());
        accepted(mvc.perform(post(hook.url()).header("X-FlowForge-Token", hook.secret())), hook);
    }

    @Test
    void bodiesMustBeSmallJson() throws Exception {
        Hook hook = activeHook(Workflows.webhook("POST", "token", "body"));
        String big = "{\"x\":\"" + "a".repeat(256 * 1024) + "\"}";

        problem(mvc.perform(post(hook.url()).header("X-FlowForge-Token", hook.secret())
                .contentType(MediaType.APPLICATION_JSON).content(big)), 413, "payload-too-large");
        problem(mvc.perform(post(hook.url()).header("X-FlowForge-Token", hook.secret())
                .contentType(MediaType.TEXT_PLAIN).content("hello")), 415, "unsupported-media-type");
        problem(mvc.perform(post(hook.url()).header("X-FlowForge-Token", hook.secret())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).content("a=1")), 415, "unsupported-media-type");
        problem(mvc.perform(post(hook.url()).header("X-FlowForge-Token", hook.secret())
                .contentType(MediaType.APPLICATION_JSON).content("{\"a\":")), 400, "invalid-request");
        assertThat(executionCount()).isZero();

        // Vendor JSON types are fine; an empty body becomes {}.
        String id = accepted(mvc.perform(post(hook.url()).header("X-FlowForge-Token", hook.secret())
                .contentType("application/vnd.github+json").content("[1,2]")), hook);
        assertThat(triggerOutput(awaitFinished(id))).isEqualTo(json.readTree("[1,2]"));
        id = accepted(mvc.perform(post(hook.url()).header("X-FlowForge-Token", hook.secret())), hook);
        assertThat(triggerOutput(awaitFinished(id))).isEqualTo(json.readTree("{}"));
    }

    @Test
    void requestInputModeMapsMethodQueryHeadersAndBodyWithoutCredentials() throws Exception {
        Hook hook = activeHook(Workflows.webhook("POST", "signature", "request"));
        long now = Instant.now().getEpochSecond();

        String id = accepted(mvc.perform(signed(hook, HttpMethod.POST, "{\"order\":7}", now)
                .queryParam("source", "shop").queryParam("tag", "a", "b")
                .header("X-Request-Id", "req-1").header("Cookie", "session=secret")), hook);
        JsonNode input = triggerOutput(awaitFinished(id));

        assertThat(input.get("method").asText()).isEqualTo("POST");
        assertThat(input.get("path").asText()).isEqualTo(hook.url());
        assertThat(input.get("query")).isEqualTo(json.readTree("{\"source\":\"shop\",\"tag\":[\"a\",\"b\"]}"));
        assertThat(input.get("body")).isEqualTo(json.readTree("{\"order\":7}"));
        assertThat(input.get("headers").get("x-request-id").asText()).isEqualTo("req-1");
        assertThat(input.get("headers").get("x-flowforge-timestamp").asText()).isEqualTo(Long.toString(now));
        assertThat(input.get("headers").has("x-flowforge-signature")).isFalse();
        assertThat(input.get("headers").has("cookie")).isFalse();
        assertThat(input.get("receivedAt").asText()).isNotBlank();
    }

    @Test
    void unauthenticatedRegistrationsAreRefusedWhileTheServerPolicyForbidsThem() throws Exception {
        Hook hook = activeHook(Workflows.webhook("POST", "token", "body"));
        // As if the workflow had been activated while the server allowed unauthenticated webhooks.
        jdbc.update("update webhook_triggers set authentication = 'none'");

        problem(mvc.perform(post(hook.url())), 401, "unauthorized")
                .andExpect(jsonPath("$.detail").value("Unauthenticated webhooks are disabled on this server."));
        assertThat(executionCount()).isZero();
    }

    @Test
    void rotatedSecretsInvalidateOldCredentialsImmediately() throws Exception {
        Hook hook = activeHook(Workflows.webhook("POST", "token", "body"));
        mvc.perform(post("/api/v1/workflows/" + hook.workflowId() + "/triggers/webhook/secret/rotate"))
                .andExpect(status().isOk());

        problem(mvc.perform(post(hook.url()).header("X-FlowForge-Token", hook.secret())), 401, "unauthorized");
    }
}
