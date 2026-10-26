package com.flowforge.infrastructure.nodes;

import static com.flowforge.application.execution.TestContexts.context;
import static com.flowforge.application.execution.TestContexts.node;
import static com.flowforge.application.execution.TestContexts.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.application.execution.ExecutionContext;
import com.flowforge.application.execution.NodeExecutionException;
import com.flowforge.application.execution.NodeExecutionResult;
import com.flowforge.application.execution.TestContexts;
import com.flowforge.application.expression.ExpressionResolver;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Unit tests for ManualTrigger, SetVariables, Condition, Transform and Logger. HTTP has its own test. */
class BuiltInExecutorsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ExpressionResolver RESOLVER = new ExpressionResolver();

    private static JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Nested
    class ManualTrigger {

        @Test
        void emitsTheTriggerInputUnchanged() {
            JsonNode input = json("{\"user\":{\"email\":\"a@b.io\"}}");
            NodeExecutionResult result = new ManualTriggerExecutor().execute(
                    request(node("t", "core.manualTrigger", Map.of()), input, context(input)));

            assertThat(result.output()).isEqualTo(input);
            assertThat(result.activatedOutputs()).containsExactly("main");
        }
    }

    @Nested
    class SetVariables {

        private final SetVariablesExecutor executor = new SetVariablesExecutor(RESOLVER, JSON);

        @Test
        void createsAndOverwritesVariablesInOrderAndPassesTheInputThrough() {
            ExecutionContext ctx = context(json("{\"user\":{\"email\":\"a@b.io\",\"age\":31}}"));
            ctx.variables().put("existing", "old");
            JsonNode input = json("{\"keep\":1}");
            Map<String, Object> config = Map.of("variables", List.of(
                    Map.of("name", "email", "value", "{{ trigger.body.user.email }}"),
                    Map.of("name", "age", "value", "{{ trigger.body.user.age }}"),
                    Map.of("name", "greeting", "value", "Hello {{ vars.email }}"),
                    Map.of("name", "existing", "value", "new"),
                    Map.of("name", "literal", "value", "42")));

            NodeExecutionResult result = executor.execute(request(node("v", "core.setVariables", config), input, ctx));

            assertThat(result.output()).isEqualTo(input);
            assertThat(ctx.variables()).isEqualTo(json(
                    "{\"existing\":\"new\",\"email\":\"a@b.io\",\"age\":31,\"greeting\":\"Hello a@b.io\",\"literal\":\"42\"}"));
            assertThat(ctx.variables().get("age").isInt()).isTrue();
        }

        @Test
        void badExpressionsFailWithTheOffendingRow() {
            Map<String, Object> config = Map.of("variables", List.of(Map.of("name", "a", "value", "1"),
                    Map.of("name", "b", "value", "{{ env.SECRET }}")));

            assertThatThrownBy(() -> executor.execute(request(node("v", "core.setVariables", config), json("{}"),
                    context(json("{}")))))
                    .isInstanceOfSatisfying(NodeExecutionException.class, e -> {
                        assertThat(e.code()).isEqualTo("EXPRESSION_ERROR");
                        assertThat(e.getMessage()).startsWith("variables[1].value:");
                    });
        }
    }

    @Nested
    class Condition {

        private final ConditionExecutor executor = new ConditionExecutor(RESOLVER, JSON);

        private String branch(String input, String field, String operator, String value) {
            Map<String, Object> config = value == null ? Map.of("field", field, "operator", operator)
                    : Map.of("field", field, "operator", operator, "value", value);
            ExecutionContext ctx = context(json("{\"limit\":100}"));
            ctx.variables().put("min", 18);
            NodeExecutionResult result = executor.execute(request(node("c", "core.condition", config), json(input), ctx));
            assertThat(result.output()).isEqualTo(json(input));
            return String.join(",", result.activatedOutputs());
        }

        @ParameterizedTest(name = "{0} {1} {2} {3} -> {4}")
        @CsvSource(delimiter = '|', value = {
            "{\"n\":120}        | n           | greaterThan        | 100                       | true",
            "{\"n\":\"120\"}    | n           | greaterThan        | 100                       | true",
            "{\"n\":100}        | n           | greaterThan        | 100                       | false",
            "{\"n\":100}        | n           | greaterThanOrEqual | 100.0                     | true",
            "{\"n\":5}          | n           | lessThan           | {{ trigger.body.limit }}  | true",
            "{\"n\":5}          | n           | lessThanOrEqual    | 4                         | false",
            "{\"n\":18}         | n           | equals             | {{ vars.min }}            | true",
            "{\"n\":1.0}        | n           | equals             | 1                         | true",
            "{\"s\":\"abc\"}    | s           | equals             | abc                       | true",
            "{\"s\":\"abc\"}    | s           | notEquals          | ABC                       | true",
            "{\"b\":true}       | b           | equals             | true                      | true",
            "{\"b\":false}      | b           | equals             | true                      | false",
            "{\"s\":\"hello\"}  | s           | contains           | ell                       | true",
            "{\"a\":[\"x\",2]}  | a           | contains           | 2                         | true",
            "{\"a\":[\"x\"]}    | a           | notContains        | y                         | true",
            "{\"s\":\"hello\"}  | s           | startsWith         | he                        | true",
            "{\"s\":\"hello\"}  | s           | endsWith           | lo                        | true",
            "{\"s\":\"\"}       | s           | isEmpty            |                           | true",
            "{\"s\":[]}         | s           | isEmpty            |                           | true",
            "{}                 | missing     | isEmpty            |                           | true",
            "{\"s\":null}       | s           | isEmpty            |                           | true",
            "{\"s\":{\"k\":1}}  | s           | isNotEmpty         |                           | true",
            "{\"o\":{\"t\":9}}  | o.t         | greaterThan        | 8                         | true",
            "{\"o\":{\"t\":9}}  | {{ input.o.t }} | equals         | 9                         | true",
            "{}                 | {{ trigger.body.limit }} | equals | 100                      | true",
        })
        void evaluatesComparisons(String input, String field, String operator, String value, String expected) {
            assertThat(branch(input, field, operator, value == null || value.isBlank() ? null : value.trim()))
                    .isEqualTo(expected);
        }

        @Test
        void numericComparisonOfNonNumbersIsATypeError() {
            assertThatThrownBy(() -> branch("{\"s\":\"abc\"}", "s", "greaterThan", "1"))
                    .isInstanceOfSatisfying(NodeExecutionException.class, e -> {
                        assertThat(e.code()).isEqualTo("TYPE_MISMATCH");
                        assertThat(e.getMessage()).contains("needs two numbers", "string \"abc\"");
                    });
            assertThatThrownBy(() -> branch("{\"o\":{}}", "o", "startsWith", "x"))
                    .isInstanceOf(NodeExecutionException.class);
        }

        @Test
        void invalidFieldPathIsAnExpressionError() {
            assertThatThrownBy(() -> branch("{}", "not a path", "equals", "1"))
                    .isInstanceOfSatisfying(NodeExecutionException.class,
                            e -> assertThat(e.code()).isEqualTo("EXPRESSION_ERROR"));
        }
    }

    @Nested
    class Transform {

        private final TransformExecutor executor = new TransformExecutor(RESOLVER, JSON);

        private JsonNode run(JsonNode input, List<Map<String, Object>> operations) {
            ExecutionContext ctx = context(json("{\"campaign\":\"Spring\"}"));
            TestContexts.ran(ctx, "http1", json("{\"body\":{\"id\":\"u-9\"}}"));
            ctx.variables().put("currency", "eur");
            return executor.execute(request(node("x", "core.transform", Map.of("operations", operations)), input, ctx))
                    .output();
        }

        private static Map<String, Object> op(String field, String source, String operation) {
            return Map.of("field", field, "source", source, "operation", operation);
        }

        @Test
        void selectsRenamesAndMapsFields() {
            JsonNode out = run(json("{\"first\":\"Ada\",\"last\":\"Lovelace\",\"secret\":\"x\",\"n\":3}"), List.of(
                    op("firstName", "{{ input.first }}", "copy"),
                    op("fullName", "{{ input.first }} {{ input.last }}", "copy"),
                    op("userId", "{{ nodes.http1.output.body.id }}", "copy"),
                    op("campaign", "{{ trigger.body.campaign }}", "toLowerCase"),
                    op("currency", "{{ vars.currency }}", "toUpperCase"),
                    op("count", "{{ input.n }}", "copy")));

            assertThat(out).isEqualTo(json("{\"firstName\":\"Ada\",\"fullName\":\"Ada Lovelace\",\"userId\":\"u-9\","
                    + "\"campaign\":\"spring\",\"currency\":\"EUR\",\"count\":3}"));
            assertThat(out.has("secret")).isFalse();
        }

        @Test
        void typeSafeConversions() {
            JsonNode out = run(json("{\"s\":\"  42.50 \",\"i\":\"7\",\"arr\":[1,2,3],\"obj\":{\"a\":1},\"text\":\"{\\\"k\\\":[1]}\","
                            + "\"word\":\"héllo\",\"num\":5}"), List.of(
                    op("trimmed", "{{ input.s }}", "trim"),
                    op("decimal", "42.50", "toNumber"),
                    op("integer", "{{ input.i }}", "toNumber"),
                    op("numText", "{{ input.num }}", "toString"),
                    op("objText", "{{ input.obj }}", "stringifyJson"),
                    op("parsed", "{{ input.text }}", "parseJson"),
                    op("arrLen", "{{ input.arr }}", "length"),
                    op("wordLen", "{{ input.word }}", "length")));

            assertThat(out.get("trimmed").textValue()).isEqualTo("42.50");
            assertThat(out.get("decimal").decimalValue()).isEqualByComparingTo("42.50");
            assertThat(out.get("integer").isIntegralNumber()).isTrue();
            assertThat(out.get("integer").asLong()).isEqualTo(7);
            assertThat(out.get("numText")).isEqualTo(json("\"5\""));
            assertThat(out.get("objText")).isEqualTo(json("\"{\\\"a\\\":1}\""));
            assertThat(out.get("parsed")).isEqualTo(json("{\"k\":[1]}"));
            assertThat(out.get("arrLen").intValue()).isEqualTo(3);
            assertThat(out.get("wordLen").intValue()).isEqualTo(5);
        }

        @Test
        void wrongTypesFailWithTheFieldName() {
            assertThatThrownBy(() -> run(json("{\"n\":5}"), List.of(op("upper", "{{ input.n }}", "toUpperCase"))))
                    .isInstanceOfSatisfying(NodeExecutionException.class, e -> {
                        assertThat(e.code()).isEqualTo("TRANSFORM_TYPE_ERROR");
                        assertThat(e.getMessage()).contains("Field 'upper'", "needs text", "number 5");
                    });
            assertThatThrownBy(() -> run(json("{\"s\":\"abc\"}"), List.of(op("n", "{{ input.s }}", "toNumber"))))
                    .isInstanceOf(NodeExecutionException.class).hasMessageContaining("numeric text");
            assertThatThrownBy(() -> run(json("{\"s\":\"{oops\"}"), List.of(op("p", "{{ input.s }}", "parseJson"))))
                    .isInstanceOf(NodeExecutionException.class).hasMessageContaining("not valid JSON");
            assertThatThrownBy(() -> run(json("{\"b\":true}"), List.of(op("l", "{{ input.b }}", "length"))))
                    .isInstanceOf(NodeExecutionException.class);
            assertThatThrownBy(() -> run(json("{}"), List.of(op("x", "{{ input.missing }}", "trim"))))
                    .isInstanceOf(NodeExecutionException.class).hasMessageContaining("null");
        }

        @Test
        void copyOfAMissingValueIsNull() {
            assertThat(run(json("{}"), List.of(op("x", "{{ input.missing }}", "copy"))))
                    .isEqualTo(json("{\"x\":null}"));
        }
    }

    @Nested
    class Logger {

        private final LoggerExecutor executor = new LoggerExecutor(RESOLVER, JSON);

        @Test
        void writesTheResolvedSanitizedMessageToTheExecutionLogAndPassesTheInputThrough() {
            ExecutionContext ctx = context(json("{\"email\":\"a@b.io\"}"));
            ctx.variables().put("token", "abc123");
            JsonNode input = json("{\"n\":1}");

            NodeExecutionResult result = executor.execute(request(node("log1", "core.logger",
                    Map.of("message", "User {{ trigger.body.email }} n={{ input.n }} token={{ vars.token }}\u0007")),
                    input, ctx));

            assertThat(result.output()).isEqualTo(input);
            List<ExecutionContext.LogEntry> logs = TestContexts.logs(ctx);
            assertThat(logs).hasSize(1);
            assertThat(logs.get(0).nodeId()).isEqualTo("log1");
            assertThat(logs.get(0).level()).isEqualTo("INFO");
            assertThat(logs.get(0).message()).isEqualTo("User a@b.io n=1 token=[REDACTED]");
        }

        @Test
        void registeredCredentialValuesAreMaskedInApplicationLogsToo() {
            var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(LoggerExecutor.class);
            var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
            var previousLevel = logger.getLevel();
            boolean previousAdditive = logger.isAdditive();
            appender.start();
            logger.addAppender(appender);
            logger.setLevel(ch.qos.logback.classic.Level.INFO);
            logger.setAdditive(false);
            try {
                String secret = "test-opaque-credential-123456";
                ExecutionContext ctx = context(json("{}"));
                ctx.secrets().register(secret);
                JsonNode input = JSON.createObjectNode().put("echo", "Provider echoed " + secret);
                var result = executor.execute(request(node("log", "core.logger",
                        Map.of("message", "{{ input.echo }}")), input, ctx));
                assertThat(result.output()).isEqualTo(input); // downstream data is unchanged
                assertThat(appender.list).singleElement().satisfies(event ->
                        assertThat(event.getFormattedMessage()).contains("Provider echoed [REDACTED]")
                                .doesNotContain(secret));
                assertThat(TestContexts.logs(ctx)).singleElement().satisfies(line ->
                        assertThat(line.message()).contains("[REDACTED]").doesNotContain(secret));
            } finally {
                logger.detachAppender(appender);
                appender.stop();
                logger.setLevel(previousLevel);
                logger.setAdditive(previousAdditive);
            }
        }

        @Test
        void longMessagesAreTruncatedAndTheLogIsCapped() {
            ExecutionContext ctx = context(json("{}"));
            ctx.variables().put("big", "x".repeat(5000));
            for (int i = 0; i < ExecutionContext.MAX_LOG_LINES + 10; i++) {
                executor.execute(request(node("l", "core.logger", Map.of("message", "{{ vars.big }}")), json("{}"), ctx));
            }

            List<ExecutionContext.LogEntry> logs = TestContexts.logs(ctx);
            assertThat(logs).hasSize(ExecutionContext.MAX_LOG_LINES + 1);
            assertThat(logs.get(0).message()).hasSize(2000);
            assertThat(logs.get(logs.size() - 1).level()).isEqualTo("WARN");
        }
    }
}
