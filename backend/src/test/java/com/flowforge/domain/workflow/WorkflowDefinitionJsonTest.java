package com.flowforge.domain.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowforge.config.JpaJsonConfig;
import com.flowforge.support.WorkflowSchema;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.context.annotation.Import;

/** Uses the application's configured ObjectMapper (the same one Hibernate uses for JSONB columns). */
@JsonTest
@Import(JpaJsonConfig.class)
class WorkflowDefinitionJsonTest {

    @Autowired
    ObjectMapper objectMapper;

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private WorkflowDefinition example() throws Exception {
        return objectMapper.readValue(WorkflowSchema.exampleDefinition(), WorkflowDefinition.class);
    }

    @Test
    void exampleDeserializesIntoRecords() throws Exception {
        WorkflowDefinition definition = example();

        assertThat(definition.schemaVersion()).isEqualTo(1);
        assertThat(definition.nodes()).extracting(NodeDefinition::id)
                .containsExactly("trigger", "check", "notify", "ignore");
        assertThat(definition.nodes().get(2).position()).isEqualTo(new NodePosition(480, -80.5));
        assertThat(definition.edges()).extracting(EdgeDefinition::sourceHandle)
                .containsExactly("main", "true", "false");
        assertThat(definition.editor().viewport().zoom()).isEqualTo(1.0);
        // Null configuration values are preserved, not dropped.
        assertThat(definition.nodes().get(1).configuration()).containsKey("note").containsEntry("note", null);
    }

    @Test
    void serializationRoundTripsAndConformsToSchema() throws Exception {
        WorkflowDefinition definition = example();

        JsonNode json = objectMapper.valueToTree(definition);
        assertThat(WorkflowSchema.validate(json)).isEmpty();
        assertThat(objectMapper.treeToValue(json, WorkflowDefinition.class)).isEqualTo(definition);
        // Numerically equal (positions are doubles, so 240 is written as 240.0), every key and null preserved.
        JSONAssert.assertEquals(new String(WorkflowSchema.exampleDefinition().readAllBytes()),
                objectMapper.writeValueAsString(json), JSONCompareMode.STRICT);
    }

    @Test
    void emptyDefinitionOmitsEditorAndIsSchemaValid() {
        JsonNode json = objectMapper.valueToTree(WorkflowDefinition.empty());

        assertThat(json.has("editor")).isFalse();
        assertThat(WorkflowSchema.validate(json)).isEmpty();
    }

    @Test
    void missingCollectionsDeserializeAsEmptyLists() throws Exception {
        WorkflowDefinition definition = objectMapper.readValue("{\"schemaVersion\":1}", WorkflowDefinition.class);

        assertThat(definition.nodes()).isEmpty();
        assertThat(definition.edges()).isEmpty();
    }

    @Test
    void schemaRejectsUnknownPropertiesAndBadHandles() throws Exception {
        ObjectNode json = (ObjectNode) objectMapper.readTree(WorkflowSchema.exampleDefinition());
        json.put("unexpected", true);
        ((ObjectNode) json.withArray("edges").get(0)).put("sourceHandle", "1-bad");

        assertThat(WorkflowSchema.validate(json)).hasSize(2);
    }

    @Test
    void beanValidationMatchesSchemaPatterns() {
        NodeDefinition badNode = new NodeDefinition("has space", "NotAType", 0, "", new NodePosition(0, 0), Map.of());
        WorkflowDefinition definition = new WorkflowDefinition(2, List.of(badNode), List.of(), null);

        assertThat(validator.validate(definition))
                .extracting(v -> v.getPropertyPath().toString())
                .containsExactlyInAnyOrder("schemaVersion", "nodes[0].id", "nodes[0].type",
                        "nodes[0].typeVersion", "nodes[0].name");
    }

    @Test
    void exampleSatisfiesBeanValidation() throws Exception {
        assertThat(validator.validate(example())).isEmpty();
    }
}
