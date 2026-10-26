package com.flowforge.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flowforge.application.WorkflowService;
import com.flowforge.application.execution.ExecutionCapacityException;
import com.flowforge.application.execution.ExecutionPersistenceService;
import com.flowforge.application.execution.WorkflowExecutionService;
import com.flowforge.domain.workflow.WorkflowDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import com.flowforge.support.PostgresIntegrationTest;
import com.flowforge.support.Workflows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** The localhost trust boundary: host allowlist, cross-site write blocking, CORS, size limits, headers, errors. */
class SecurityHardeningIT extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    WorkflowService workflows;

    @Autowired
    WorkflowExecutionService executionService;

    @Autowired
    ExecutionPersistenceService persistence;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE workflows CASCADE");
    }

    private int workflowCount() {
        return jdbc.queryForObject("select count(*) from workflows", Integer.class);
    }

    private static final String CREATE = "{\"name\":\"x\"}";

    @Test
    void foreignHostNamesAreRefusedAgainstDnsRebinding() throws Exception {
        mvc.perform(get("/api/v1/workflows").header("Host", "attacker.example:8080"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://flowforge.local/problems/host-not-allowed"));
        mvc.perform(get("/api/v1/workflows").header("Host", "127.0.0.1:8080")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/workflows").header("Host", "[::1]:8080")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/workflows").header("Host", "localhost")).andExpect(status().isOk());
    }

    @Test
    void crossSiteWritesAreBlockedButTheFrontendOriginAndWebhooksAreNot() throws Exception {
        mvc.perform(post("/api/v1/workflows").header("Origin", "https://evil.example")
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://flowforge.local/problems/cross-origin-request-blocked"));
        // A "simple" form-style request without CORS preflight is blocked too.
        mvc.perform(post("/api/v1/workflows").header("Origin", "null").contentType(MediaType.TEXT_PLAIN).content(CREATE))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/workflows").header("Sec-Fetch-Site", "cross-site")
                .contentType(MediaType.APPLICATION_JSON).content(CREATE)).andExpect(status().isForbidden());
        assertThat(workflowCount()).isZero();

        mvc.perform(post("/api/v1/workflows").header("Origin", "http://localhost:5173")
                .contentType(MediaType.APPLICATION_JSON).content(CREATE)).andExpect(status().isCreated());
        mvc.perform(post("/api/v1/workflows").header("Sec-Fetch-Site", "same-origin")
                .contentType(MediaType.APPLICATION_JSON).content(CREATE)).andExpect(status().isCreated());
        // Reads from other origins are refused by CORS processing; a same-origin read is fine.
        mvc.perform(get("/api/v1/workflows").header("Origin", "https://evil.example")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/workflows")).andExpect(status().isOk());
        // Webhooks are server-to-server and authenticate themselves: no origin rule (404 = reached the endpoint).
        mvc.perform(post("/api/v1/hooks/unknownWebhookId000000").header("Origin", "https://evil.example"))
                .andExpect(status().isNotFound());
        assertThat(workflowCount()).isEqualTo(2);
    }

    @Test
    void corsAllowsOnlyTheConfiguredOrigins() throws Exception {
        mvc.perform(options("/api/v1/workflows").header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
        mvc.perform(options("/api/v1/workflows").header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        mvc.perform(options("/api/v1/workflows").header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "PATCH"))
                .andExpect(status().isForbidden());
    }

    @Test
    void oversizedAndDeeplyNestedBodiesAreRejected() throws Exception {
        String big = "{\"name\":\"" + "x".repeat(1024 * 1024) + "\"}";
        mvc.perform(post("/api/v1/workflows").contentType(MediaType.APPLICATION_JSON).content(big))
                .andExpect(status().isPayloadTooLarge());
        String deep = "{\"name\":\"d\",\"definition\":{\"schemaVersion\":1,\"edges\":[],\"nodes\":[{\"id\":\"a\","
                + "\"type\":\"core.logger\",\"typeVersion\":1,\"name\":\"a\",\"position\":{\"x\":0,\"y\":0},"
                + "\"configuration\":{\"v\":" + "[".repeat(200) + "]".repeat(200) + "}}]}}";
        mvc.perform(post("/api/v1/workflows").contentType(MediaType.APPLICATION_JSON).content(deep))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("nesting depth")));
        assertThat(workflowCount()).isZero();
    }

    @Test
    void responsesCarrySecurityHeadersAndErrorsLeakNothing() throws Exception {
        mvc.perform(get("/api/v1/health"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'"));
        String malformed = mvc.perform(post("/api/v1/workflows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"secret-in-a-broken-body\" ,,}"))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertThat(malformed).doesNotContain("secret-in-a-broken-body").doesNotContain("line:").doesNotContain("Exception");
        mvc.perform(get("/api/v1/workflows/not-a-uuid")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Parameter 'id' has an invalid value."));
        mvc.perform(get("/actuator/env")).andExpect(status().isNotFound());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    void eachWorkflowHasABoundedNumberOfActiveExecutions() throws Exception {
        String definition = Workflows.manual();
        String id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(mvc.perform(post("/api/v1/workflows")
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Busy\",\"definition\":" + definition + "}"))
                .andReturn().getResponse().getContentAsString()).get("id").asText();
        // Simulate ten runs still queued or running.
        for (int i = 0; i < 10; i++) {
            jdbc.update("insert into workflow_executions (id, workflow_id, workflow_version, workflow_snapshot, status) "
                    + "select gen_random_uuid(), id, version, definition, 'QUEUED' from workflows where id = ?::uuid", id);
        }
        mvc.perform(post("/api/v1/workflows/" + id + "/execute")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("https://flowforge.local/problems/execution-capacity"));
        jdbc.update("delete from workflow_executions");
    }
    @Test
    void concurrentAdmissionsCannotExceedTheWorkflowLimit() throws Exception {
        var workflow = workflows.create("Concurrent runs", null,
                new ObjectMapper().readValue(Workflows.manual(), WorkflowDefinition.class));
        for (int i = 0; i < 9; i++) {
            jdbc.update("insert into workflow_executions (id, workflow_id, workflow_version, workflow_snapshot, status) "
                    + "select gen_random_uuid(), id, version, definition, 'QUEUED' from workflows where id = ?",
                    workflow.getId());
        }
        // All requests have passed the early capacity check before any of them reserves the last slot.
        var prepared = executionService.prepare(workflow, null);
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        try (var threads = Executors.newFixedThreadPool(8)) {
            var results = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 8; i++) {
                results.add(threads.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Admission barrier timed out");
                    try {
                        persistence.createQueued(prepared.workflow(), prepared.plan());
                        return true;
                    } catch (ExecutionCapacityException full) {
                        return false;
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int accepted = 0;
            for (var result : results) if (result.get(15, TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isEqualTo(1);
            assertThat(persistence.activeCount(workflow.getId())).isEqualTo(10);
        } finally {
            start.countDown();
        }
    }

}
