package com.flowforge.application.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class ResponseSchemaPolicyTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static List<String> problems(String schema) {
        return ResponseSchemaPolicy.check(json(schema)).stream().map(p -> p.path() + " " + p.message()).toList();
    }

    @Test
    void acceptsTheSupportedSubset() {
        assertThat(problems("{\"type\":\"object\",\"title\":\"T\",\"required\":[\"a\"],\"additionalProperties\":false,"
                + "\"properties\":{\"a\":{\"type\":[\"string\",\"null\"],\"maxLength\":10},\"b\":{\"type\":\"array\","
                + "\"items\":{\"enum\":[1,2]},\"minItems\":1,\"uniqueItems\":true},\"c\":{\"oneOf\":[{\"type\":\"integer\","
                + "\"minimum\":0},{\"const\":\"n/a\"}]}}}")).isEmpty();
    }

    @Test
    void rejectsReferencesPatternsFormatsAndUnknownKeywords() {
        assertThat(problems("{\"$ref\":\"http://169.254.169.254/\"}")).singleElement().asString()
                .contains("keyword '$ref' is not supported").contains("references, patterns and formats");
        assertThat(problems("{\"properties\":{\"a\":{\"type\":\"string\",\"pattern\":\"(a+)+$\"}}}")).singleElement()
                .asString().contains("/properties/a/pattern").contains("not supported");
        assertThat(problems("{\"type\":\"string\",\"format\":\"email\"}")).singleElement().asString()
                .contains("format");
        assertThat(problems("{\"$schema\":\"x\",\"$id\":\"y\",\"$defs\":{}}")).hasSize(3);
        assertThat(problems("{\"if\":{},\"then\":{}}")).hasSize(2);
    }

    @Test
    void rejectsMalformedKeywordValues() {
        assertThat(problems("{\"type\":\"date\"}")).singleElement().asString().contains("must be one of");
        assertThat(problems("{\"required\":\"a\"}")).singleElement().asString().contains("list of property names");
        assertThat(problems("{\"properties\":[]}")).singleElement().asString().contains("object of schemas");
        assertThat(problems("{\"properties\":{\"a\":true}}")).singleElement().asString().contains("must be a schema object");
        assertThat(problems("{\"minimum\":\"1\"}")).singleElement().asString().contains("must be a number");
        assertThat(problems("{\"enum\":[]}")).singleElement().asString().contains("1 to 100");
        assertThat(problems("{\"anyOf\":[]}")).singleElement().asString().contains("1 to 20");
        assertThat(ResponseSchemaPolicy.check(json("[]"))).singleElement().extracting(ResponseSchemaPolicy.Problem::message)
                .isEqualTo("must be a JSON Schema object");
    }

    @Test
    void boundsDepthSizeAndNodeCount() {
        String deep = "{\"type\":\"object\"}";
        for (int i = 0; i < ResponseSchemaPolicy.MAX_DEPTH + 1; i++) {
            deep = "{\"properties\":{\"a\":" + deep + "}}";
        }
        assertThat(problems(deep)).anyMatch(p -> p.contains("nested deeper"));

        StringBuilder wide = new StringBuilder("{\"properties\":{");
        for (int i = 0; i < ResponseSchemaPolicy.MAX_NODES + 5; i++) {
            wide.append(i == 0 ? "" : ",").append("\"p").append(i).append("\":{}");
        }
        assertThat(problems(wide + "}}")).anyMatch(p -> p.contains("more than " + ResponseSchemaPolicy.MAX_NODES));

        String big = "{\"description\":\"" + "x".repeat(900) + "\",\"properties\":{" + "\"k\":{\"description\":\""
                + "y".repeat(900) + "\"},".repeat(1) + "\"z\":{}}}";
        assertThat(ResponseSchemaPolicy.check(json(big))).isEmpty();
        StringBuilder huge = new StringBuilder("{\"properties\":{");
        for (int i = 0; i < 40; i++) {
            huge.append(i == 0 ? "" : ",").append("\"p").append(i).append("\":{\"description\":\"").append("d".repeat(500))
                    .append("\"}");
        }
        assertThat(problems(huge + "}}")).singleElement().asString().contains("larger than");
    }

    @Test
    void sharedFixturesAreAcceptedOrRejectedLikeInTheEditor() throws Exception {
        JsonNode fixtures;
        try (var in = getClass().getResourceAsStream("/workflow/response-schema-fixtures.v1.json")) {
            fixtures = JSON.readTree(in);
        }
        for (JsonNode valid : fixtures.get("valid")) {
            assertThat(ResponseSchemaPolicy.check(valid)).as(valid.toString()).isEmpty();
        }
        for (JsonNode invalid : fixtures.get("invalid")) {
            assertThat(ResponseSchemaPolicy.check(invalid)).as(invalid.toString()).isNotEmpty();
        }
    }
}
