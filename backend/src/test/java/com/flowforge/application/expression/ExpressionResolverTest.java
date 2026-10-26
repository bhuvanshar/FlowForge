package com.flowforge.application.expression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.application.execution.ExecutionContext;
import com.flowforge.application.execution.TestContexts;
import java.io.InputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ExpressionResolverTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final ExpressionResolver resolver = new ExpressionResolver();
    private ExpressionScope scope;

    private static JsonNode json(String text) throws Exception {
        return JSON.readTree(text);
    }

    @BeforeEach
    void setUp() throws Exception {
        ExecutionContext context = TestContexts.context(json("{\"email\":\"a@b.io\",\"items\":[{\"id\":7}]}"));
        TestContexts.ran(context, "http1", json("{\"status\":201,\"body\":{\"id\":\"u-1\",\"tags\":[\"x\",\"y\"]}}"));
        context.variables().put("threshold", 18).put("name", "Ada");
        scope = new ExpressionScope(json("{\"total\":120.5,\"ok\":true,\"none\":null,\"list\":[1,2]}"), context);
    }

    @Test
    void wholeReferencesKeepTheirJsonType() throws Exception {
        assertThat(resolver.value("{{ input.total }}", scope)).isEqualTo(json("120.5"));
        assertThat(resolver.value("{{input.ok}}", scope)).isEqualTo(json("true"));
        assertThat(resolver.value("{{ input.list }}", scope)).isEqualTo(json("[1,2]"));
        assertThat(resolver.value("{{ nodes.http1.output.body }}", scope))
                .isEqualTo(json("{\"id\":\"u-1\",\"tags\":[\"x\",\"y\"]}"));
        assertThat(resolver.value("{{ vars.threshold }}", scope)).isEqualTo(json("18"));
    }

    @Test
    void supportsTheDocumentedRoots() {
        assertThat(resolver.text("{{trigger.body.email}}", scope)).isEqualTo("a@b.io");
        assertThat(resolver.text("{{ trigger.body.items[0].id }}", scope)).isEqualTo("7");
        assertThat(resolver.text("{{nodes.http1.output.status}}", scope)).isEqualTo("201");
        assertThat(resolver.text("{{ nodes.http1.output.body.tags[1] }}", scope)).isEqualTo("y");
        assertThat(resolver.text("Hi {{ vars.name }}", scope)).isEqualTo("Hi Ada");
    }

    @Test
    void embeddedReferencesAreStringified() {
        assertThat(resolver.text("total={{ input.total }} ok={{ input.ok }} list={{ input.list }} none=[{{ input.none }}]",
                scope)).isEqualTo("total=120.5 ok=true list=[1,2] none=[]");
        assertThat(resolver.value("id {{ nodes.http1.output.body.id }}", scope).textValue()).isEqualTo("id u-1");
    }

    @Test
    void missingValuesAreNullOrEmptyNeverErrors() {
        assertThat(resolver.value("{{ input.nope.deeper }}", scope).isNull()).isTrue();
        assertThat(resolver.value("{{ input.list[9] }}", scope).isNull()).isTrue();
        assertThat(resolver.value("{{ nodes.notRunYet.output }}", scope).isNull()).isTrue();
        assertThat(resolver.text("[{{ vars.unknown }}]", scope)).isEqualTo("[]");
        assertThat(resolver.value("{{ input.total.x }}", scope).isNull()).isTrue(); // property of a number
        assertThat(resolver.value("{{ input[0] }}", scope).isNull()).isTrue(); // index into an object... not a path
    }

    @Test
    void plainTextAndSingleBracesAreLiteral() {
        assertThat(resolver.text("no refs { here }", scope)).isEqualTo("no refs { here }");
        assertThat(resolver.value("42", scope).textValue()).isEqualTo("42");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{{ foo() }}", "{{ input.total + 1 }}", "{{ 'x' }}", "{{ constructor.constructor('return 1')() }}",
            "{{ unterminated", "{{{ input }}}", "{{ a; b }}", "{{ input[\"total\"] }}", "{{ }}", "{{ process.env }}x()" })
    void rejectsAnythingButPathsOrUnknownRoots(String template) {
        assertThatThrownBy(() -> resolver.text(template, scope)).isInstanceOf(ExpressionException.class);
    }

    @Test
    void unknownRootsAreErrorsWithAHint() {
        assertThatThrownBy(() -> resolver.value("{{ env.HOME }}", scope))
                .isInstanceOf(ExpressionException.class).hasMessageContaining("Use input, trigger, vars or nodes");
        assertThatThrownBy(() -> resolver.value("{{ nodes }}", scope))
                .isInstanceOf(ExpressionException.class).hasMessageContaining("nodes.<nodeId>.output");
    }

    @Test
    void resolvesStringsInsideJsonDeeplyAndLeavesOtherValuesAlone() throws Exception {
        JsonNode body = json("{\"email\":\"{{ trigger.body.email }}\",\"n\":1,\"nested\":[\"{{ vars.threshold }}\","
                + "\"x {{ vars.name }}\",null],\"{{ keys }}\":true}");

        assertThat(resolver.json(body, scope)).isEqualTo(json(
                "{\"email\":\"a@b.io\",\"n\":1,\"nested\":[18,\"x Ada\",null],\"{{ keys }}\":true}"));
    }

    @Test
    void lookupInFollowsPlainPathsFromAGivenRoot() throws Exception {
        assertThat(resolver.lookupIn(json("{\"order\":{\"total\":5}}"), "order.total")).isEqualTo(json("5"));
        assertThat(resolver.lookupIn(json("{}"), "order.total").isMissingNode()).isTrue();
        assertThatThrownBy(() -> resolver.lookupIn(json("{}"), "order total")).isInstanceOf(ExpressionException.class);
    }

    @Test
    void grammarIsIdenticalToTheSharedNodeConfigurationSchema() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/schemas/node-config.v1.schema.json")) {
            String pattern = JSON.readTree(in).at("/$defs/safeString/pattern").asText();
            assertThat(ExpressionSyntax.TEMPLATE_PATTERN).isEqualTo(pattern);
        }
    }
}
