package com.flowforge.application.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class AiOutputParserTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final AiOutputParser parser = new AiOutputParser(JSON);

    @Test
    void parsesPlainAndFencedJson() throws Exception {
        assertThat(parser.parse(" {\"a\":1}\n").json()).isEqualTo(JSON.readTree("{\"a\":1}"));
        assertThat(parser.parse("```json\n{\"a\":1}\n```").json()).isEqualTo(JSON.readTree("{\"a\":1}"));
        assertThat(parser.parse("```\n[1]\n```\n").json()).isEqualTo(JSON.readTree("[1]"));
        assertThat(parser.parse("42").ok()).isTrue();
    }

    @Test
    void anythingElseAroundTheJsonIsMalformed() {
        for (String reply : new String[] {"Sure! {\"a\":1}", "{\"a\":1} Hope that helps", "{\"a\":1}{\"b\":2}", "{\"a\":",
                "", "   ", "```json\n{\"a\":1}", "{'a':1}"}) {
            AiOutputParser.Parsed parsed = parser.parse(reply);
            assertThat(parsed.ok()).as(reply).isFalse();
            assertThat(parsed.json()).isNull();
            assertThat(parsed.problem()).isNotBlank();
        }
        assertThat(parser.parse("").problem()).isEqualTo("the reply is empty");
    }

    @Test
    void problemTextStaysShortAndLocationFree() {
        String problem = parser.parse("Sure thing! " + "blah ".repeat(100)).problem();
        assertThat(problem).startsWith("the reply is not valid JSON").hasSizeLessThan(160).doesNotContain("line:");
    }

    @Test
    void validatesAgainstTheSchema() throws Exception {
        var schema = JSON.readTree("{\"type\":\"object\",\"required\":[\"n\"],\"properties\":{\"n\":{\"type\":\"integer\","
                + "\"minimum\":1}},\"additionalProperties\":false}");
        assertThat(parser.validate(schema, JSON.readTree("{\"n\":3}"))).isEmpty();
        assertThat(parser.validate(schema, JSON.readTree("{\"n\":0}"))).singleElement().asString().contains("minimum");
        assertThat(parser.validate(schema, JSON.readTree("{\"n\":\"x\",\"extra\":1}"))).hasSize(2);
        assertThat(parser.validate(schema, JSON.readTree("{}"))).singleElement().asString().contains("required");
    }
}
