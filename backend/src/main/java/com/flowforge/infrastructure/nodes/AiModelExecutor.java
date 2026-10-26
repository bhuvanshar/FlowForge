package com.flowforge.infrastructure.nodes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowforge.application.ai.AiChatService;
import com.flowforge.application.ai.ChatCall;
import com.flowforge.application.ai.ResponseSchemaPolicy;
import com.flowforge.application.ai.Usage;
import com.flowforge.application.credential.CredentialAccessException;
import com.flowforge.application.credential.CredentialHeaders;
import com.flowforge.application.credential.CredentialResolver;
import com.flowforge.application.credential.ResolvedCredential;
import com.flowforge.application.execution.NodeExecutionException;
import com.flowforge.application.execution.NodeExecutionRequest;
import com.flowforge.application.execution.NodeExecutionResult;
import com.flowforge.application.execution.NodeExecutor;
import com.flowforge.application.expression.ExpressionResolver;
import com.flowforge.application.expression.ExpressionScope;
import com.flowforge.config.AiProperties;
import com.flowforge.domain.workflow.NodeDefinition;
import com.flowforge.infrastructure.http.Redaction;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code core.aiModel}: sends a prompt to a chat model and returns the answer.
 *
 * <ul>
 *   <li><b>Prompts</b>: {@code systemPrompt} and {@code userPrompt} are templates with the usual safe
 *       {@code {{ path }}} references ({@code input}, {@code vars}, {@code trigger.body}, {@code nodes.<id>.output}).
 *       Referenced data is inserted as text and is <em>untrusted</em>: it can try to steer the model, so keep
 *       instructions in the system prompt and never treat the answer as code or as an instruction to FlowForge; it is
 *       only data for the next nodes.</li>
 *   <li><b>Credential</b>: optional {@code credentialId} (bearer token or API-key header). It is resolved as the
 *       workflow owner, bound to its allowed hosts, sent only as a request header and masked everywhere it could be
 *       stored. The API key is never part of the workflow, the output, the logs or any response.</li>
 *   <li><b>Output</b>: {@code { text, json?, model, provider, finishReason?, usage?, attempts, request }}. In JSON mode
 *       {@code json} is the parsed, schema-validated reply. {@code usage} is {@code {prompt, completion, total, cost}} as
 *       far as the provider reports it ({@code cost} only when the provider returns a price), summed over attempts.</li>
 *   <li><b>Failures</b>: bounded retries and the error codes are described in {@link AiChatService}.</li>
 * </ul>
 */
@Component
public class AiModelExecutor implements NodeExecutor {

    public static final String NODE_TYPE = "core.aiModel";
    public static final String PROMPT_TOO_LARGE = "AI_PROMPT_TOO_LARGE";
    private static final Duration ENGINE_MARGIN = Duration.ofSeconds(5);

    private final AiChatService chat;
    private final CredentialResolver credentials;
    private final ExpressionResolver resolver;
    private final AiProperties properties;
    private final ObjectMapper mapper;

    public AiModelExecutor(AiChatService chat, CredentialResolver credentials, ExpressionResolver resolver,
                           AiProperties properties, ObjectMapper mapper) {
        this.chat = chat;
        this.credentials = credentials;
        this.resolver = resolver;
        this.properties = properties;
        this.mapper = mapper;
    }

    @Override
    public String nodeType() {
        return NODE_TYPE;
    }

    /** The node spends at most {@code timeoutSeconds} over all attempts; the engine's limit is a little longer. */
    @Override
    public Optional<Duration> timeout(NodeDefinition node) {
        return Optional.of(budget(mapper.valueToTree(node.configuration())).plus(ENGINE_MARGIN));
    }

    @Override
    public NodeExecutionResult execute(NodeExecutionRequest request) throws Exception {
        JsonNode config = NodeConfig.of(mapper, request.node());
        ExpressionScope scope = new ExpressionScope(request.input(), request.context());
        String baseUrl = NodeConfig.requiredText(config, "baseUrl");
        String model = NodeConfig.requiredText(config, "model");
        String user = NodeConfig.resolving("userPrompt", () -> resolver.text(NodeConfig.requiredText(config, "userPrompt"),
                scope));
        String system = config.hasNonNull("systemPrompt")
                ? NodeConfig.resolving("systemPrompt", () -> resolver.text(config.get("systemPrompt").textValue(), scope))
                : "";
        if (user.isBlank()) {
            throw new NodeExecutionException(NodeConfig.INVALID_CONFIGURATION, "The user prompt is empty after "
                    + "resolving its references.");
        }
        if (system.length() + user.length() > properties.maxPromptChars()) {
            throw new NodeExecutionException(PROMPT_TOO_LARGE, "The prompt is larger than " + properties.maxPromptChars()
                    + " characters.");
        }

        boolean json = "json".equals(config.path("responseFormat").asText("text"));
        JsonNode schema = json && config.path("responseSchema").isObject() ? config.get("responseSchema") : null;
        if (schema != null) {
            var problems = ResponseSchemaPolicy.check(schema);
            if (!problems.isEmpty()) {
                throw new NodeExecutionException(NodeConfig.INVALID_CONFIGURATION, "responseSchema" + problems.get(0).path()
                        + " " + problems.get(0).message() + ".");
            }
        }

        ResolvedCredential credential = resolveCredential(config, baseUrl, request);
        Optional<ChatCall.Header> auth = credential == null ? Optional.empty() : Optional.of(header(credential, request));

        AiChatService.Task task = new AiChatService.Task(config.path("provider").asText("openaiCompatible"), baseUrl, model,
                system, user, config.path("temperature").isNumber() ? config.get("temperature").asDouble() : null,
                config.path("maxTokens").isInt() ? config.get("maxTokens").asInt() : null, json,
                json && !"prompt".equals(config.path("jsonMode").asText("jsonObject")), schema,
                config.path("maxRetries").asInt(2));
        String nodeId = request.node().id();
        AiChatService.Result result = chat.complete(task, auth, budget(config), request.context()::isCancelled,
                message -> request.context().log(nodeId, "WARN", message));

        request.context().log(nodeId, "INFO", usageLine(result));
        return NodeExecutionResult.success(output(config, baseUrl, model, credential, result));
    }

    private Duration budget(JsonNode config) {
        return Duration.ofSeconds(Math.max(1, Math.min(config.path("timeoutSeconds").asInt(60), 300)));
    }

    private ResolvedCredential resolveCredential(JsonNode config, String baseUrl, NodeExecutionRequest request) {
        String id = config.path("credentialId").asText("");
        if (id.isEmpty()) {
            return null;
        }
        ResolvedCredential credential;
        try {
            credential = credentials.resolve(UUID.fromString(id), request.context().ownerId(), NODE_TYPE);
        } catch (CredentialAccessException e) {
            throw new NodeExecutionException(e.code(), e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new NodeExecutionException(CredentialAccessException.NOT_FOUND, "Invalid credential id.");
        }
        String host;
        try {
            host = URI.create(baseUrl).getHost();
        } catch (IllegalArgumentException e) {
            host = null;
        }
        if (!credential.permitsHost(host)) {
            throw new NodeExecutionException("CREDENTIAL_HOST_NOT_ALLOWED", "Credential '" + credential.name()
                    + "' may not be sent to " + host + " (allowed: " + String.join(", ", credential.allowedHosts())
                    + ").");
        }
        return credential;
    }

    private ChatCall.Header header(ResolvedCredential credential, NodeExecutionRequest request) {
        try {
            CredentialHeaders.Header header = CredentialHeaders.of(credential, request.context().secrets());
            return new ChatCall.Header(header.name(), header.value());
        } catch (CredentialAccessException e) {
            throw new NodeExecutionException(e.code(), e.getMessage());
        }
    }

    private ObjectNode output(JsonNode config, String baseUrl, String model, ResolvedCredential credential,
                              AiChatService.Result result) {
        ObjectNode out = mapper.createObjectNode();
        out.put("text", result.text());
        if (result.json() != null) {
            out.set("json", result.json());
        }
        out.put("model", result.model() != null ? result.model() : model);
        out.put("provider", config.path("provider").asText("openaiCompatible"));
        if (result.finishReason() != null) {
            out.put("finishReason", result.finishReason());
        }
        Usage usage = result.usage();
        if (!usage.isEmpty()) {
            ObjectNode u = out.putObject("usage");
            if (usage.prompt() != null) {
                u.put("prompt", usage.prompt());
            }
            if (usage.completion() != null) {
                u.put("completion", usage.completion());
            }
            if (usage.total() != null) {
                u.put("total", usage.total());
            }
            if (usage.cost() != null) {
                u.put("cost", usage.cost());
            }
        }
        out.put("attempts", result.attempts());
        ObjectNode echo = out.putObject("request");
        echo.put("baseUrl", Redaction.url(baseUrl));
        echo.put("model", model);
        echo.put("responseFormat", config.path("responseFormat").asText("text"));
        if (config.path("temperature").isNumber()) {
            echo.put("temperature", config.get("temperature").asDouble());
        }
        if (credential != null) {
            ObjectNode used = echo.putObject("authenticatedWith"); // not "credential": key-name redaction would hide it
            used.put("id", credential.id().toString());
            used.put("name", credential.name());
            used.put("type", credential.type());
        }
        return out;
    }

    private static String usageLine(AiChatService.Result result) {
        Usage u = result.usage();
        StringBuilder line = new StringBuilder("AI call finished: model ").append(result.model() == null ? "?"
                : result.model()).append(", attempts ").append(result.attempts());
        if (u.prompt() != null) {
            line.append(", prompt ").append(u.prompt());
        }
        if (u.completion() != null) {
            line.append(", completion ").append(u.completion());
        }
        if (u.total() != null) {
            line.append(", total ").append(u.total());
        }
        if (u.cost() != null) {
            line.append(", cost ").append(u.cost().toPlainString());
        }
        return line.toString();
    }
}
