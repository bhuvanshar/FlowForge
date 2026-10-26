package com.flowforge.domain.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Guards the node type catalog shared with the frontend editor. */
class NodeTypeCatalogTest {

    private JsonNode catalog() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/schemas/node-types.v1.json")) {
            return new ObjectMapper().readTree(in);
        }
    }

    @Test
    void typeIdsAndHandlesFollowTheWorkflowContract() throws Exception {
        Pattern type = Pattern.compile(WorkflowDefinition.NODE_TYPE_PATTERN);
        Pattern handle = Pattern.compile(WorkflowDefinition.HANDLE_PATTERN);
        Set<String> seen = new HashSet<>();

        for (JsonNode node : catalog().get("nodeTypes")) {
            String id = node.get("type").asText();
            assertThat(type.matcher(id).matches()).as(id).isTrue();
            assertThat(seen.add(id)).as("duplicate " + id).isTrue();
            assertThat(node.get("typeVersion").asInt()).isGreaterThanOrEqualTo(1);
            assertThat(node.get("outputs")).isNotEmpty();
            for (String key : new String[] {"inputs", "outputs"}) {
                for (JsonNode h : node.get(key)) {
                    assertThat(handle.matcher(h.asText()).matches()).as(id + "." + h).isTrue();
                }
            }
        }
        assertThat(seen).containsExactlyInAnyOrder("core.manualTrigger", "core.webhookTrigger", "core.scheduleTrigger",
                "core.setVariables", "core.condition", "core.transform", "core.logger", "core.httpRequest", "core.aiModel");
    }
}
