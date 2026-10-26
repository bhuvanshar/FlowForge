package com.flowforge.infrastructure.nodes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.flowforge.application.execution.NodeExecutionRequest;
import com.flowforge.application.execution.NodeExecutionResult;
import com.flowforge.application.execution.NodeExecutor;
import com.flowforge.application.expression.ExpressionResolver;
import com.flowforge.application.expression.ExpressionScope;
import org.springframework.stereotype.Component;

/**
 * {@code core.condition}: compares {@code field} with {@code value} using {@code operator} ({@link Comparison}) and
 * activates the {@code true} or {@code false} output. {@code field} is either a path into the input
 * ({@code order.total}) or a template ({@code {{ vars.total }}}). Output: the input, unchanged.
 */
@Component
public class ConditionExecutor implements NodeExecutor {

    private final ExpressionResolver resolver;
    private final ObjectMapper mapper;

    public ConditionExecutor(ExpressionResolver resolver, ObjectMapper mapper) {
        this.resolver = resolver;
        this.mapper = mapper;
    }

    @Override
    public String nodeType() {
        return "core.condition";
    }

    @Override
    public NodeExecutionResult execute(NodeExecutionRequest request) {
        JsonNode config = NodeConfig.of(mapper, request.node());
        ExpressionScope scope = new ExpressionScope(request.input(), request.context());
        String field = NodeConfig.requiredText(config, "field");
        String operator = NodeConfig.requiredText(config, "operator");

        JsonNode left = NodeConfig.resolving("field", () -> field.contains("{{")
                ? resolver.value(field, scope)
                : resolver.lookupIn(request.input(), field.trim()));
        JsonNode right = config.has("value")
                ? NodeConfig.resolving("value", () -> resolver.value(config.get("value").asText(), scope))
                : MissingNode.getInstance();

        boolean result = Comparison.evaluate(operator, left, right);
        return NodeExecutionResult.branch(request.input(), result ? "true" : "false");
    }
}
