package com.flowforge.infrastructure.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowforge.application.ai.ResponseSchemaPolicy;
import com.flowforge.application.trigger.CronSchedule;
import com.flowforge.application.trigger.TriggerNodes;
import com.flowforge.config.TriggerProperties;
import com.flowforge.domain.validation.ConfigurationValidator;
import com.flowforge.domain.validation.CredentialReferences;
import com.flowforge.domain.validation.HeaderRules;
import com.flowforge.domain.validation.NodeTypeRegistry;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Validates node configurations against {@code schemas/node-config.v1.schema.json} ({@code $defs/<type>}), then applies
 * the rules JSON Schema cannot express (unique variable names, transform output fields and header names, parseable cron
 * expressions and time zones, the server policy on unauthenticated webhooks, HTTP header rules and credential
 * references). Messages are
 * rewritten into short, editor-friendly text; at most one message per location is reported.
 */
@Component
public class JsonSchemaConfigurationValidator implements ConfigurationValidator {

    public static final String SCHEMA = "/schemas/node-config.v1.schema.json";

    private final ObjectMapper objectMapper;
    private final boolean allowUnauthenticatedWebhooks;
    private final CredentialReferences credentials;
    private final Map<String, JsonSchema> schemas = new HashMap<>();

    /** Default server policy: unauthenticated webhooks are rejected. */
    public JsonSchemaConfigurationValidator(ObjectMapper objectMapper, NodeTypeRegistry registry) {
        this(objectMapper, registry, TriggerProperties.defaults());
    }

    public JsonSchemaConfigurationValidator(ObjectMapper objectMapper, NodeTypeRegistry registry,
                                            TriggerProperties triggers) {
        this(objectMapper, registry, triggers, (CredentialReferences) null);
    }

    @Autowired
    public JsonSchemaConfigurationValidator(ObjectMapper objectMapper, NodeTypeRegistry registry,
                                            TriggerProperties triggers, ObjectProvider<CredentialReferences> credentials) {
        this(objectMapper, registry, triggers, credentials.getIfAvailable());
    }

    /** @param credentials checks {@code credentialId} references; null checks only their shape */
    public JsonSchemaConfigurationValidator(ObjectMapper objectMapper, NodeTypeRegistry registry,
                                            TriggerProperties triggers, CredentialReferences credentials) {
        this.credentials = credentials;
        this.objectMapper = objectMapper;
        this.allowUnauthenticatedWebhooks = triggers.allowUnauthenticatedWebhooks();
        JsonNode document = readSchema(objectMapper);
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        registry.all().forEach(type -> {
            if (!document.get("$defs").has(type.type())) {
                throw new IllegalStateException("No configuration schema for node type " + type.type());
            }
            ObjectNode root = objectMapper.createObjectNode();
            root.put("$schema", document.get("$schema").asText());
            root.set("$defs", document.get("$defs"));
            root.put("$ref", "#/$defs/" + type.type());
            schemas.put(type.type(), factory.getSchema(root));
        });
    }

    @Override
    public List<Violation> validate(String type, Map<String, Object> configuration) {
        JsonSchema schema = schemas.get(type);
        if (schema == null) {
            return List.of();
        }
        JsonNode json = objectMapper.valueToTree(configuration);
        Map<String, Violation> byPath = new LinkedHashMap<>();
        for (ValidationMessage message : schema.validate(json)) {
            Violation violation = describe(type, message);
            byPath.putIfAbsent(violation.path(), violation);
        }
        List<Violation> violations = new ArrayList<>(byPath.values());
        switch (type) {
            case "core.setVariables" -> unique(json, "variables", "name", false, "Variable names must be unique",
                    violations);
            case "core.transform" -> unique(json, "operations", "field", false, "Output fields must be unique",
                    violations);
            case "core.httpRequest" -> {
                unique(json, "headers", "name", true, "Header names must be unique", violations);
                headerRules(json, byPath, violations);
                credentialReference(json, type, byPath, violations);
            }
            case "core.aiModel" -> {
                credentialReference(json, type, byPath, violations);
                responseSchema(json, byPath, violations);
            }
            case TriggerNodes.WEBHOOK -> {
                if (!allowUnauthenticatedWebhooks && "none".equals(text(json, "authentication"))) {
                    violations.add(new Violation("authentication", "unauthenticated webhooks are disabled on this "
                            + "server; use a signature or token"));
                }
            }
            case TriggerNodes.SCHEDULE -> {
                checkParse(json, "cron", CronSchedule::parseCron, byPath, violations);
                checkParse(json, "timezone", CronSchedule::parseZone, byPath, violations);
            }
            default -> {
            }
        }
        return violations;
    }

    /**
     * Headers managed by the HTTP client are refused; credential headers (Authorization, Cookie, X-API-Key, ...) may not
     * carry a literal value, which would store a secret in the workflow JSON: use a credential or a {{ }} reference.
     */
    private static void headerRules(JsonNode json, Map<String, Violation> schemaViolations, List<Violation> out) {
        JsonNode rows = json.get("headers");
        if (rows == null || !rows.isArray()) {
            return;
        }
        for (int i = 0; i < rows.size(); i++) {
            String name = rows.get(i).path("name").asText("");
            String value = rows.get(i).path("value").asText("");
            if (schemaViolations.containsKey("headers[" + i + "].name")) {
                continue;
            }
            if (HeaderRules.isForbidden(name)) {
                out.add(new Violation("headers[" + i + "].name", "is managed by the HTTP client and cannot be set"));
            } else if (HeaderRules.isSensitive(name) && !value.isEmpty() && !value.contains("{{")) {
                out.add(new Violation("headers[" + i + "].value", "must not contain a secret: use a credential "
                        + "(or a {{ }} reference) for " + name));
            }
        }
    }

    /** The schema must stay inside {@link ResponseSchemaPolicy}: no references, patterns or oversized documents. */
    private static void responseSchema(JsonNode json, Map<String, Violation> schemaViolations, List<Violation> out) {
        JsonNode schema = json.get("responseSchema");
        if (schema == null || !schema.isObject() || schemaViolations.containsKey("responseSchema")) {
            return;
        }
        for (ResponseSchemaPolicy.Problem problem : ResponseSchemaPolicy.check(schema).stream().limit(3).toList()) {
            out.add(new Violation("responseSchema", (problem.path().isEmpty() ? "" : "at " + problem.path() + ": ")
                    + problem.message()));
        }
    }

    private void credentialReference(JsonNode json, String nodeType, Map<String, Violation> schemaViolations,
                                     List<Violation> out) {
        String id = text(json, "credentialId");
        if (credentials == null || id == null || schemaViolations.containsKey("credentialId")) {
            return;
        }
        credentials.problem(id, nodeType).ifPresent(problem -> out.add(new Violation("credentialId", problem)));
    }

    /** Runs {@code parser} on a string setting the schema accepted (no violation reported for it yet). */
    private static void checkParse(JsonNode json, String field, java.util.function.Function<String, ?> parser,
                                   Map<String, Violation> schemaViolations, List<Violation> out) {
        String value = text(json, field);
        if (value == null || schemaViolations.containsKey(field)) {
            return;
        }
        try {
            parser.apply(value);
        } catch (IllegalArgumentException e) {
            out.add(new Violation(field, e.getMessage()));
        }
    }

    private static String text(JsonNode json, String field) {
        JsonNode value = json.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static Violation describe(String nodeType, ValidationMessage message) {
        String location = path(message.getInstanceLocation().toString());
        String schemaLocation = message.getSchemaLocation().toString();
        String property = message.getProperty();
        return switch (message.getType()) {
            case "required" -> new Violation(join(location, property), "is required");
            case "additionalProperties" -> new Violation(join(location, property), "is not a supported setting");
            case "minItems" -> new Violation(location, "needs at least one entry");
            case "minLength" -> new Violation(location, "must not be empty");
            case "pattern" -> new Violation(location, patternMessage(schemaLocation));
            case "anyOf" -> new Violation(location, "strings may only contain {{ path.to.value }} references");
            case "not" -> new Violation(location, switch (nodeType) {
                case "core.httpRequest" -> "GET and HEAD requests cannot have a body";
                case "core.aiModel" -> "'jsonMode' and 'responseSchema' only apply when the response format is JSON";
                default -> "'value' must be omitted for operators that take no comparison value";
            });
            default -> new Violation(location, stripLocation(message.getMessage()));
        };
    }

    private static String patternMessage(String schemaLocation) {
        if (schemaLocation.contains("variableName")) {
            return "must start with a letter or _ and contain only letters, digits and _";
        }
        if (schemaLocation.contains("headerName")) {
            return "is not a valid header name";
        }
        if (schemaLocation.contains("/url/")) {
            return "must start with http://, https:// or a {{ }} reference";
        }
        if (schemaLocation.contains("cronExpression")) {
            return "must have 5 or 6 space-separated cron fields";
        }
        if (schemaLocation.contains("aiBaseUrl")) {
            return "must be an http(s) URL without query, fragment or references";
        }
        if (schemaLocation.contains("aiModelName")) {
            return "is not a valid model name";
        }
        if (schemaLocation.contains("credentialId")) {
            return "must be the id of a stored credential";
        }
        if (schemaLocation.contains("timeZone")) {
            return "must be an IANA time zone name such as Europe/Berlin";
        }
        return "only {{ path.to.value }} references are allowed inside {{ }}";
    }

    private static void unique(JsonNode json, String list, String key, boolean ignoreCase, String message,
                               List<Violation> out) {
        JsonNode rows = json.get(list);
        if (rows == null || !rows.isArray()) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            JsonNode value = rows.get(i).get(key);
            if (value == null || !value.isTextual() || value.asText().isEmpty()) {
                continue;
            }
            String normalized = ignoreCase ? value.asText().toLowerCase(Locale.ROOT) : value.asText();
            if (!seen.add(normalized)) {
                out.add(new Violation(list + "[" + i + "]." + key, message));
            }
        }
    }

    /** {@code $.variables[0].name} becomes {@code variables[0].name}; {@code $} becomes the empty string. */
    private static String path(String instanceLocation) {
        String p = instanceLocation.startsWith("$") ? instanceLocation.substring(1) : instanceLocation;
        return p.startsWith(".") ? p.substring(1) : p;
    }

    private static String join(String location, String property) {
        if (property == null || property.isEmpty()) {
            return location;
        }
        return location.isEmpty() ? property : location + "." + property;
    }

    private static String stripLocation(String message) {
        int colon = message.indexOf(": ");
        return colon >= 0 && message.startsWith("$") ? message.substring(colon + 2) : message;
    }

    private static JsonNode readSchema(ObjectMapper mapper) {
        try (InputStream in = JsonSchemaConfigurationValidator.class.getResourceAsStream(SCHEMA)) {
            if (in == null) {
                throw new IllegalStateException("Missing configuration schema " + SCHEMA);
            }
            return mapper.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
