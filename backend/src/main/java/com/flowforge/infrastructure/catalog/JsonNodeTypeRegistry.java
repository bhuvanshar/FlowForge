package com.flowforge.infrastructure.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.domain.validation.NodeTypeDefinition;
import com.flowforge.domain.validation.NodeTypeRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Node types from the canonical catalog {@code schemas/node-types.v1.json} (shared with the frontend editor). */
@Component
public class JsonNodeTypeRegistry implements NodeTypeRegistry {

    public static final String CATALOG = "/schemas/node-types.v1.json";

    private final Map<String, NodeTypeDefinition> types;

    public JsonNodeTypeRegistry(ObjectMapper objectMapper) {
        this.types = load(objectMapper);
    }

    @Override
    public Optional<NodeTypeDefinition> find(String type) {
        return Optional.ofNullable(types.get(type));
    }

    @Override
    public Collection<NodeTypeDefinition> all() {
        return types.values();
    }

    private static Map<String, NodeTypeDefinition> load(ObjectMapper mapper) {
        try (InputStream in = JsonNodeTypeRegistry.class.getResourceAsStream(CATALOG)) {
            if (in == null) {
                throw new IllegalStateException("Missing node type catalog " + CATALOG);
            }
            Map<String, NodeTypeDefinition> result = new LinkedHashMap<>();
            for (JsonNode node : mapper.readTree(in).get("nodeTypes")) {
                NodeTypeDefinition definition = new NodeTypeDefinition(
                        node.get("type").asText(),
                        node.get("typeVersion").asInt(),
                        NodeTypeDefinition.Role.valueOf(node.get("role").asText().toUpperCase(Locale.ROOT)),
                        strings(node.get("inputs")),
                        strings(node.get("outputs")));
                result.put(definition.type(), definition);
            }
            return Map.copyOf(result);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(v -> values.add(v.asText()));
        return values;
    }
}
