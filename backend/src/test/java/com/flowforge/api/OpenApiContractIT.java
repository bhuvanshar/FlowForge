package com.flowforge.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowforge.support.PostgresIntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The REST contract as a reviewed file: {@code docs/api/openapi.json} must equal what the running backend publishes.
 * The frontend contract test ({@code apiContract.test.ts}) checks its client against the same file, so a backend change
 * that the frontend does not follow fails one of the two builds.
 *
 * <p>After an intended API change: {@code UPDATE_OPENAPI_SNAPSHOT=true ./mvnw verify -Dit.test=OpenApiContractIT}, review
 * the diff of docs/api/openapi.json, then run the frontend tests.
 */
class OpenApiContractIT extends PostgresIntegrationTest {

    private static final Path SNAPSHOT = Path.of("..", "docs", "api", "openapi.json");

    @Autowired
    MockMvc mvc;

    @Test
    void theCommittedApiSnapshotMatchesTheRunningBackend() throws Exception {
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .enable(SerializationFeature.INDENT_OUTPUT);
        JsonNode live = mapper.readTree(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString());
        ((ObjectNode) live).remove("servers"); // the test server's random URL is not part of the contract
        // Normalize ordering by round-tripping through a sorted map.
        String current = mapper.writeValueAsString(mapper.treeToValue(live, Object.class)) + "\n";

        if ("true".equals(System.getenv("UPDATE_OPENAPI_SNAPSHOT"))) {
            Files.writeString(SNAPSHOT, current);
        }
        assertThat(Files.exists(SNAPSHOT)).as("docs/api/openapi.json exists (generate it with UPDATE_OPENAPI_SNAPSHOT=true)")
                .isTrue();
        assertThat(current).as("docs/api/openapi.json is out of date: regenerate it and review the diff")
                .isEqualTo(Files.readString(SNAPSHOT));
    }
}
