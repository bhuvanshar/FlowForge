package com.flowforge.infrastructure.nodes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.application.execution.NodeExecutionRequest;
import com.flowforge.application.execution.NodeExecutionResult;
import com.flowforge.application.execution.NodeExecutor;
import com.flowforge.application.expression.ExpressionResolver;
import com.flowforge.application.expression.ExpressionScope;
import com.flowforge.domain.execution.ErrorDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@code core.logger}: resolves the message template and writes it to the execution log (INFO). The message is
 * sanitized: control characters removed, obvious credentials ({@code password=...}, {@code Bearer ...}) redacted,
 * truncated to 2000 characters. Output: the input, unchanged.
 */
@Component
public class LoggerExecutor implements NodeExecutor {

    private static final Logger log = LoggerFactory.getLogger(LoggerExecutor.class);

    private final ExpressionResolver resolver;
    private final ObjectMapper mapper;

    public LoggerExecutor(ExpressionResolver resolver, ObjectMapper mapper) {
        this.resolver = resolver;
        this.mapper = mapper;
    }

    @Override
    public String nodeType() {
        return "core.logger";
    }

    @Override
    public NodeExecutionResult execute(NodeExecutionRequest request) {
        JsonNode config = NodeConfig.of(mapper, request.node());
        String template = NodeConfig.requiredText(config, "message");
        String message = NodeConfig.resolving("message",
                () -> resolver.text(template, new ExpressionScope(request.input(), request.context())));
        request.context().log(request.node().id(), "INFO", message);
        if (log.isInfoEnabled()) {
            String safe = ErrorDetails.sanitized("LOG", request.context().secrets().mask(message)).message();
            log.info("[execution {}] [{}] {}", request.context().executionId(), request.node().id(), safe);
        }
        return NodeExecutionResult.success(request.input());
    }
}
