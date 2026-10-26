package com.flowforge.infrastructure.nodes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.application.execution.NodeExecutionRequest;
import com.flowforge.application.execution.NodeExecutionResult;
import com.flowforge.application.execution.NodeExecutor;
import com.flowforge.application.expression.ExpressionResolver;
import com.flowforge.application.expression.ExpressionScope;
import org.springframework.stereotype.Component;

/**
 * {@code core.setVariables}: creates or overwrites workflow variables, readable later as {@code {{ vars.<name> }}}.
 * Rows are applied in order, so a row may use variables set by earlier rows. A value that is a single reference keeps the
 * referenced JSON type. Output: the input, unchanged (variables live in the execution, not in the data flow).
 */
@Component
public class SetVariablesExecutor implements NodeExecutor {

    private final ExpressionResolver resolver;
    private final ObjectMapper mapper;

    public SetVariablesExecutor(ExpressionResolver resolver, ObjectMapper mapper) {
        this.resolver = resolver;
        this.mapper = mapper;
    }

    @Override
    public String nodeType() {
        return "core.setVariables";
    }

    @Override
    public NodeExecutionResult execute(NodeExecutionRequest request) {
        JsonNode config = NodeConfig.of(mapper, request.node());
        ExpressionScope scope = new ExpressionScope(request.input(), request.context());
        int index = 0;
        for (JsonNode row : config.path("variables")) {
            String name = NodeConfig.requiredText(row, "name");
            String template = NodeConfig.requiredText(row, "value");
            JsonNode value = NodeConfig.resolving("variables[" + index++ + "].value",
                    () -> resolver.value(template, scope));
            request.context().variables().set(name, value);
        }
        return NodeExecutionResult.success(request.input());
    }
}
