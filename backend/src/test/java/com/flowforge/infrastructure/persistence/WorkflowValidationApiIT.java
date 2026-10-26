package com.flowforge.infrastructure.persistence;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.support.PostgresIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

class WorkflowValidationApiIT extends PostgresIntegrationTest {

    private static final String INVALID_DEF = "{\"schemaVersion\":1,\"nodes\":["
            + "{\"id\":\"t\",\"type\":\"core.manualTrigger\",\"typeVersion\":1,\"name\":\"Start\",\"position\":{\"x\":0,\"y\":0},\"configuration\":{}},"
            + "{\"id\":\"c\",\"type\":\"core.condition\",\"typeVersion\":1,\"name\":\"Check\",\"position\":{\"x\":0,\"y\":0},\"configuration\":{\"field\":\"\",\"operator\":\"equals\",\"value\":\"1\"}},"
            + "{\"id\":\"l\",\"type\":\"core.logger\",\"typeVersion\":1,\"name\":\"Log\",\"position\":{\"x\":0,\"y\":0},\"configuration\":{\"message\":\"hi\"}}],"
            + "\"edges\":[{\"id\":\"e1\",\"source\":\"t\",\"sourceHandle\":\"main\",\"target\":\"c\",\"targetHandle\":\"main\"},"
            + "{\"id\":\"e2\",\"source\":\"c\",\"sourceHandle\":\"true\",\"target\":\"l\",\"targetHandle\":\"main\"},"
            + "{\"id\":\"e3\",\"source\":\"l\",\"sourceHandle\":\"main\",\"target\":\"c\",\"targetHandle\":\"main\"}]}";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbc;

    private String id;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.execute("TRUNCATE workflows CASCADE");
        String body = mvc.perform(post("/api/v1/workflows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"V\",\"definition\":" + WorkflowApiIT.VALID_DEF + "}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        id = objectMapper.readTree(body).get("id").asText();
    }

    @Test
    void storedValidGraphPasses() throws Exception {
        mvc.perform(post("/api/v1/workflows/" + id + "/validate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.errorCount").value(0))
                .andExpect(jsonPath("$.issues.length()").value(0))
                .andExpect(jsonPath("$.executionOrder[0]").value("t"))
                .andExpect(jsonPath("$.executionOrder[1]").value("l"));
    }

    @Test
    void unsavedDefinitionInBodyIsValidatedWithPreciseIssues() throws Exception {
        mvc.perform(post("/api/v1/workflows/" + id + "/validate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"definition\":" + INVALID_DEF + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errorCount").value(3))
                .andExpect(jsonPath("$.warningCount").value(1))
                .andExpect(jsonPath("$.issues[0].code").value("UNSUPPORTED_MERGE"))
                .andExpect(jsonPath("$.issues[0].nodeId").value("c"))
                .andExpect(jsonPath("$.issues[0].edgeId").value("e3"))
                .andExpect(jsonPath("$.issues[1].code").value("BRANCH_NOT_CONNECTED"))
                .andExpect(jsonPath("$.issues[1].severity").value("WARNING"))
                .andExpect(jsonPath("$.issues[2].code").value("CYCLE_DETECTED"))
                .andExpect(jsonPath("$.issues[2].edgeId").value("e3"))
                .andExpect(jsonPath("$.issues[3].code").value("CONFIGURATION_INVALID"))
                .andExpect(jsonPath("$.issues[3].path").value("nodes[1].configuration.field"))
                .andExpect(jsonPath("$.executionOrder.length()").value(0));

        // Validation never changes the stored workflow.
        mvc.perform(get("/api/v1/workflows/" + id)).andExpect(jsonPath("$.definition.nodes.length()").value(2));
    }

    @Test
    void unknownWorkflowIs404AndMalformedBodyIs400() throws Exception {
        mvc.perform(post("/api/v1/workflows/" + UUID.randomUUID() + "/validate")).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/workflows/" + id + "/validate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"definition\":{\"schemaVersion\":1,\"nodes\":[],\"edges\":[],\"x\":1}}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void activationIsRefusedWith422ForInvalidGraphsAndAllowedForValidOnes() throws Exception {
        mvc.perform(put("/api/v1/workflows/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"V\",\"status\":\"ACTIVE\",\"lockVersion\":0,\"definition\":" + INVALID_DEF + "}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("https://flowforge.local/problems/workflow-invalid"))
                .andExpect(jsonPath("$.issues[0].code").value("UNSUPPORTED_MERGE"));
        mvc.perform(get("/api/v1/workflows/" + id))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.lockVersion").value(0));

        mvc.perform(put("/api/v1/workflows/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"V\",\"status\":\"ACTIVE\",\"lockVersion\":0,\"definition\":"
                                + WorkflowApiIT.VALID_DEF + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void draftsCanStillBeSavedWhileInvalid() throws Exception {
        mvc.perform(put("/api/v1/workflows/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"V\",\"lockVersion\":0,\"definition\":" + INVALID_DEF + "}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/workflows/" + id + "/validate"))
                .andExpect(jsonPath("$.valid").value(false));
    }
}
