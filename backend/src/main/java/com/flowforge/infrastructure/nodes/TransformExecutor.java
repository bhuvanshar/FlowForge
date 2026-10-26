package com.flowforge.infrastructure.nodes;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.flowforge.application.execution.NodeExecutionException;
import com.flowforge.application.execution.NodeExecutionRequest;
import com.flowforge.application.execution.NodeExecutionResult;
import com.flowforge.application.execution.NodeExecutor;
import com.flowforge.application.expression.ExpressionResolver;
import com.flowforge.application.expression.ExpressionScope;
import java.math.BigDecimal;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * {@code core.transform}: builds a new object from a list of operations. Each operation reads {@code source} (a template;
 * a single reference keeps its type), applies {@code operation} and stores the result as {@code field}. The output
 * contains only these fields, which gives field selection ({@code copy} of the fields you want), renaming (a different
 * {@code field} name) and mapping (any source, including other nodes and variables).
 *
 * <p>Operations are type-checked; a wrong type fails the node with {@code TRANSFORM_TYPE_ERROR} instead of guessing.
 */
@Component
public class TransformExecutor implements NodeExecutor {

    static final String TYPE_ERROR = "TRANSFORM_TYPE_ERROR";

    private final ExpressionResolver resolver;
    private final ObjectMapper mapper;

    public TransformExecutor(ExpressionResolver resolver, ObjectMapper mapper) {
        this.resolver = resolver;
        this.mapper = mapper;
    }

    @Override
    public String nodeType() {
        return "core.transform";
    }

    @Override
    public NodeExecutionResult execute(NodeExecutionRequest request) {
        JsonNode config = NodeConfig.of(mapper, request.node());
        ExpressionScope scope = new ExpressionScope(request.input(), request.context());
        ObjectNode output = mapper.createObjectNode();
        int index = 0;
        for (JsonNode operation : config.path("operations")) {
            String field = NodeConfig.requiredText(operation, "field");
            String source = NodeConfig.requiredText(operation, "source");
            String kind = NodeConfig.requiredText(operation, "operation");
            JsonNode value = NodeConfig.resolving("operations[" + index++ + "].source",
                    () -> resolver.value(source, scope));
            output.set(field, apply(kind, field, value));
        }
        return NodeExecutionResult.success(output);
    }

    JsonNode apply(String operation, String field, JsonNode value) {
        return switch (operation) {
            case "copy" -> value;
            case "toUpperCase" -> TextNode.valueOf(requireText(operation, field, value).toUpperCase(Locale.ROOT));
            case "toLowerCase" -> TextNode.valueOf(requireText(operation, field, value).toLowerCase(Locale.ROOT));
            case "trim" -> TextNode.valueOf(requireText(operation, field, value).strip());
            case "toNumber" -> Comparison.number(value).<JsonNode>map(TransformExecutor::numberNode)
                    .orElseThrow(() -> typeError(operation, field, "a number or numeric text", value));
            case "toString" -> TextNode.valueOf(ExpressionResolver.stringify(value));
            case "parseJson" -> parse(field, requireText(operation, field, value));
            case "stringifyJson" -> TextNode.valueOf(value.toString());
            case "length" -> {
                if (value.isTextual()) {
                    yield IntNode.valueOf(value.textValue().codePointCount(0, value.textValue().length()));
                }
                if (value.isContainerNode()) {
                    yield IntNode.valueOf(value.size());
                }
                throw typeError(operation, field, "text, an array or an object", value);
            }
            default -> throw new NodeExecutionException(NodeConfig.INVALID_CONFIGURATION,
                    "Unsupported operation '" + operation + "' for field '" + field + "'.");
        };
    }

    private static JsonNode numberNode(BigDecimal number) {
        BigDecimal stripped = number.stripTrailingZeros();
        if (stripped.scale() <= 0 && stripped.abs().compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) <= 0) {
            return com.fasterxml.jackson.databind.node.LongNode.valueOf(stripped.longValueExact());
        }
        return DecimalNode.valueOf(number);
    }

    private JsonNode parse(String field, String text) {
        try {
            return mapper.readTree(text);
        } catch (JsonProcessingException e) {
            throw new NodeExecutionException(TYPE_ERROR, "Field '" + field + "': the text is not valid JSON.");
        }
    }

    private static String requireText(String operation, String field, JsonNode value) {
        if (value.isTextual()) {
            return value.textValue();
        }
        throw typeError(operation, field, "text", value);
    }

    private static NodeExecutionException typeError(String operation, String field, String expected, JsonNode value) {
        return new NodeExecutionException(TYPE_ERROR, "Field '" + field + "': '" + operation + "' needs " + expected
                + ", got " + Comparison.describe(value) + ".");
    }
}
