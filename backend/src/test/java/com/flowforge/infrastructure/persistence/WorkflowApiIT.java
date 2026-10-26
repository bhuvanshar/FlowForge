package com.flowforge.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.support.PostgresIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** End-to-end tests of /api/v1/workflows through the full stack against real PostgreSQL. */
class WorkflowApiIT extends PostgresIntegrationTest {

    /** Smallest graph that passes validation (needed to activate a workflow). */
    static final String VALID_DEF = "{\"schemaVersion\":1,\"nodes\":["
            + "{\"id\":\"t\",\"type\":\"core.manualTrigger\",\"typeVersion\":1,\"name\":\"Start\",\"position\":{\"x\":0,\"y\":0},\"configuration\":{}},"
            + "{\"id\":\"l\",\"type\":\"core.logger\",\"typeVersion\":1,\"name\":\"Log\",\"position\":{\"x\":200,\"y\":0},\"configuration\":{\"message\":\"hi\"}}],"
            + "\"edges\":[{\"id\":\"e1\",\"source\":\"t\",\"sourceHandle\":\"main\",\"target\":\"l\",\"targetHandle\":\"main\"}]}";
    private static final String EMPTY_DEF = "{\"schemaVersion\":1,\"nodes\":[],\"edges\":[]}";
    private static final String ONE_NODE_DEF = "{\"schemaVersion\":1,\"nodes\":[{\"id\":\"a\",\"type\":\"core.noOp\","
            + "\"typeVersion\":1,\"name\":\"A\",\"position\":{\"x\":1.5,\"y\":2},\"configuration\":{\"k\":null}}],"
            + "\"edges\":[]}";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE workflows CASCADE");
    }

    private JsonNode createWorkflow(String name) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/workflows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"description\":\"d\"}"))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String updateBody(String name, long lockVersion, String definition, String status) {
        return "{\"name\":\"" + name + "\",\"lockVersion\":" + lockVersion + ",\"definition\":" + definition
                + (status == null ? "" : ",\"status\":\"" + status + "\"") + "}";
    }

    @Test
    void fullLifecycle() throws Exception {
        JsonNode created = createWorkflow("Orders");
        String id = created.get("id").asText();
        assertThat(created.get("status").asText()).isEqualTo("DRAFT");
        assertThat(created.get("version").asInt()).isEqualTo(1);
        assertThat(created.get("lockVersion").asLong()).isZero();

        mvc.perform(get("/api/v1/workflows/" + id))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Orders"));

        // Definition change: version 2, lockVersion 1; null configuration value survives the database round trip.
        mvc.perform(put("/api/v1/workflows/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("Orders v2", 0, ONE_NODE_DEF, "INACTIVE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.lockVersion").value(1))
                .andExpect(jsonPath("$.status").value("INACTIVE"));
        mvc.perform(get("/api/v1/workflows/" + id))
                .andExpect(jsonPath("$.definition.nodes[0].configuration.k").value((Object) null))
                .andExpect(jsonPath("$.definition.nodes[0].position.x").value(1.5));

        // Rename only: lockVersion bumps, definition version does not.
        mvc.perform(put("/api/v1/workflows/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("Orders v3", 1, ONE_NODE_DEF, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.lockVersion").value(2));

        // Soft delete, then still readable but hidden from the default list.
        mvc.perform(delete("/api/v1/workflows/" + id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/workflows/" + id)).andExpect(jsonPath("$.status").value("ARCHIVED"));
        mvc.perform(get("/api/v1/workflows")).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/v1/workflows").param("status", "ARCHIVED"))
                .andExpect(jsonPath("$.totalElements").value(1));

        // Archived is read-only until restored.
        JsonNode archived = objectMapper.readTree(mvc.perform(get("/api/v1/workflows/" + id)).andReturn()
                .getResponse().getContentAsString());
        long lock = archived.get("lockVersion").asLong();
        mvc.perform(put("/api/v1/workflows/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("x", lock, EMPTY_DEF, null)))
                .andExpect(status().isConflict());
        mvc.perform(put("/api/v1/workflows/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("Restored", lock, ONE_NODE_DEF, "DRAFT")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT"));

        // Hard delete only after archiving.
        mvc.perform(delete("/api/v1/workflows/" + id).param("permanent", "true")).andExpect(status().isConflict());
        mvc.perform(delete("/api/v1/workflows/" + id)).andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/workflows/" + id).param("permanent", "true")).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/workflows/" + id)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/workflows/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void staleUpdateIsRejectedAndDoesNotOverwrite() throws Exception {
        JsonNode created = createWorkflow("Orders");
        String id = created.get("id").asText();

        mvc.perform(put("/api/v1/workflows/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("First writer", 0, EMPTY_DEF, null)))
                .andExpect(status().isOk());
        mvc.perform(put("/api/v1/workflows/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("Second writer (stale)", 0, ONE_NODE_DEF, null)))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://flowforge.local/problems/conflict"));

        mvc.perform(get("/api/v1/workflows/" + id))
                .andExpect(jsonPath("$.name").value("First writer"))
                .andExpect(jsonPath("$.definition.nodes.length()").value(0));
    }

    @Test
    void duplicateCreatesIndependentDraftCopy() throws Exception {
        String id = createWorkflow("Orders").get("id").asText();
        mvc.perform(put("/api/v1/workflows/" + id).contentType(MediaType.APPLICATION_JSON)
                .content(updateBody("Orders", 0, ONE_NODE_DEF, "INACTIVE"))).andExpect(status().isOk());

        MvcResult result = mvc.perform(post("/api/v1/workflows/" + id + "/duplicate"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Orders (copy)"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.definition.nodes[0].id").value("a"))
                .andReturn();
        JsonNode copy = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(copy.get("id").asText()).isNotEqualTo(id);
        assertThat(result.getResponse().getHeader("Location")).endsWith(copy.get("id").asText());

        mvc.perform(post("/api/v1/workflows/" + id + "/duplicate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Named copy\"}"))
                .andExpect(jsonPath("$.name").value("Named copy"));
        mvc.perform(post("/api/v1/workflows/" + UUID.randomUUID() + "/duplicate")).andExpect(status().isNotFound());
    }

    @Test
    void listSupportsPaginationSortingAndFiltering() throws Exception {
        for (String n : new String[] {"Alpha", "beta", "Gamma 100%", "Delta_x", "epsilon"}) {
            createWorkflow(n);
        }
        String activeId = createWorkflow("Zeta active").get("id").asText();
        mvc.perform(put("/api/v1/workflows/" + activeId).contentType(MediaType.APPLICATION_JSON)
                .content(updateBody("Zeta active", 0, VALID_DEF, "ACTIVE"))).andExpect(status().isOk());

        mvc.perform(get("/api/v1/workflows").param("sort", "name,asc").param("size", "4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(6))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items.length()").value(4))
                .andExpect(jsonPath("$.items[0].name").value("Alpha"))
                .andExpect(jsonPath("$.items[3].name").value("epsilon"));
        mvc.perform(get("/api/v1/workflows").param("sort", "name,asc").param("size", "4").param("page", "1"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[1].name").value("Zeta active"));
        mvc.perform(get("/api/v1/workflows").param("sort", "name,desc").param("size", "1"))
                .andExpect(jsonPath("$.items[0].name").value("Zeta active"));

        mvc.perform(get("/api/v1/workflows").param("name", "ALPH"))
                .andExpect(jsonPath("$.totalElements").value(1));
        // LIKE wildcards in user input are literal.
        mvc.perform(get("/api/v1/workflows").param("name", "%"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].name").value("Gamma 100%"));
        mvc.perform(get("/api/v1/workflows").param("name", "_"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].name").value("Delta_x"));
        mvc.perform(get("/api/v1/workflows").param("status", "ACTIVE"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].name").value("Zeta active"));
        mvc.perform(get("/api/v1/workflows").param("status", "ACTIVE", "DRAFT"))
                .andExpect(jsonPath("$.totalElements").value(6));

        mvc.perform(get("/api/v1/workflows").param("sort", "definition")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/workflows").param("size", "1000")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/workflows").param("page", "-1")).andExpect(status().isBadRequest());
    }

    @Test
    void validationErrorsAreProblemDetails() throws Exception {
        mvc.perform(post("/api/v1/workflows").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"));
        mvc.perform(post("/api/v1/workflows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"definition\":{\"schemaVersion\":1,\"nodes\":[],\"edges\":[],"
                                + "\"surprise\":true}}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void oversizedDefinitionIs413() throws Exception {
        String big = "{\"schemaVersion\":1,\"nodes\":[{\"id\":\"a\",\"type\":\"core.noOp\",\"typeVersion\":1,"
                + "\"name\":\"A\",\"position\":{\"x\":0,\"y\":0},\"configuration\":{\"blob\":\""
                + "x".repeat(600_000) + "\"}}],\"edges\":[]}";

        mvc.perform(post("/api/v1/workflows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"big\",\"definition\":" + big + "}"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.type").value("https://flowforge.local/problems/payload-too-large"));
    }

    @Test
    void openApiDocumentDescribesAllEndpoints() throws Exception {
        MvcResult result = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn();
        JsonNode paths = objectMapper.readTree(result.getResponse().getContentAsString()).get("paths");

        assertThat(paths.get("/api/v1/workflows").has("post")).isTrue();
        assertThat(paths.get("/api/v1/workflows").has("get")).isTrue();
        assertThat(paths.get("/api/v1/workflows/{id}").fieldNames()).toIterable()
                .containsExactlyInAnyOrder("get", "put", "delete");
        assertThat(paths.get("/api/v1/workflows/{id}/duplicate").has("post")).isTrue();
        assertThat(paths.has("/api/v1/health")).isTrue();
    }
}
