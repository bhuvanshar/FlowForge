package com.flowforge.domain.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Validates the shared node configuration fixtures against node-config.v1.schema.json. The frontend runs the same
 * fixtures through this schema (Ajv) and through its Zod validation, which keeps editor and backend in agreement.
 */
class NodeConfigSchemaTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode read(String path) throws Exception {
        try (InputStream in = getClass().getResourceAsStream(path)) {
            return mapper.readTree(in);
        }
    }

    /** A self-contained schema for one node type: the document's $defs plus a root $ref to $defs/<type>. */
    private JsonSchema schemaFor(JsonNode doc, String type) {
        ObjectNode root = mapper.createObjectNode();
        root.put("$schema", doc.get("$schema").asText());
        root.set("$defs", doc.get("$defs"));
        root.put("$ref", "#/$defs/" + type);
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(root);
    }

    @Test
    void fixturesAreAcceptedOrRejectedAsDeclared() throws Exception {
        JsonNode doc = read("/schemas/node-config.v1.schema.json");
        JsonNode cases = read("/workflow/node-config-fixtures.v1.json").get("cases");
        List<String> failures = new ArrayList<>();
        int checked = 0;

        for (Iterator<Map.Entry<String, JsonNode>> it = cases.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            JsonSchema schema = schemaFor(doc, entry.getKey());
            for (JsonNode valid : entry.getValue().get("valid")) {
                checked++;
                if (!schema.validate(valid).isEmpty()) {
                    failures.add(entry.getKey() + " should be valid: " + valid);
                }
            }
            for (JsonNode invalid : entry.getValue().get("invalid")) {
                checked++;
                if (schema.validate(invalid).isEmpty()) {
                    failures.add(entry.getKey() + " should be invalid: " + invalid);
                }
            }
        }

        assertThat(failures).isEmpty();
        assertThat(checked).isGreaterThan(50);
    }

    @Test
    void everyCatalogNodeTypeHasAConfigurationSchemaAndDefault() throws Exception {
        JsonNode doc = read("/schemas/node-config.v1.schema.json");
        for (JsonNode type : read("/schemas/node-types.v1.json").get("nodeTypes")) {
            String id = type.get("type").asText();
            assertThat(doc.get("$defs").has(id)).as("schema for " + id).isTrue();
            assertThat(type.get("defaultConfiguration").isObject()).as("default for " + id).isTrue();
        }
    }
}
