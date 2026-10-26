package com.flowforge.infrastructure.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.domain.validation.ConfigurationValidator.Violation;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The runtime validator must agree with the shared contract fixtures used by the frontend. */
class JsonSchemaConfigurationValidatorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonNodeTypeRegistry registry = new JsonNodeTypeRegistry(mapper);
    private final JsonSchemaConfigurationValidator validator = new JsonSchemaConfigurationValidator(mapper, registry);

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(JsonNode node) {
        return mapper.convertValue(node, Map.class);
    }

    @Test
    void sharedFixturesAreAcceptedOrRejected() throws Exception {
        JsonNode cases;
        try (InputStream in = getClass().getResourceAsStream("/workflow/node-config-fixtures.v1.json")) {
            cases = mapper.readTree(in).get("cases");
        }
        List<String> failures = new ArrayList<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = cases.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            for (JsonNode valid : entry.getValue().get("valid")) {
                List<Violation> v = validator.validate(entry.getKey(), map(valid));
                if (!v.isEmpty()) {
                    failures.add(entry.getKey() + " valid " + valid + " -> " + v);
                }
            }
            for (JsonNode invalid : entry.getValue().get("invalid")) {
                if (validator.validate(entry.getKey(), map(invalid)).isEmpty()) {
                    failures.add(entry.getKey() + " invalid but accepted " + invalid);
                }
            }
        }
        assertThat(failures).isEmpty();
    }

    @Test
    void reportsAtMostOneMessagePerLocation() {
        List<Violation> violations = validator.validate("core.logger", Map.of("message", ""));
        assertThat(violations).extracting(Violation::path).containsExactly("message");
    }

    @Test
    void unauthenticatedWebhooksAreRejectedUnlessTheServerAllowsThem() {
        Map<String, Object> none = Map.of("method", "POST", "authentication", "none", "inputMode", "body");
        assertThat(validator.validate("core.webhookTrigger", none)).extracting(Violation::path)
                .containsExactly("authentication");

        var permissive = new JsonSchemaConfigurationValidator(mapper, registry,
                new com.flowforge.config.TriggerProperties(true, null, null, null, null, null, null, null));
        assertThat(permissive.validate("core.webhookTrigger", none)).isEmpty();
    }

    @Test
    void cronAndTimeZoneMustParse() {
        assertThat(validator.validate("core.scheduleTrigger",
                Map.of("cron", "0 25 * * *", "timezone", "UTC", "missedRunPolicy", "skip")))
                .singleElement().satisfies(v -> {
                    assertThat(v.path()).isEqualTo("cron");
                    assertThat(v.message()).startsWith("is not a valid cron expression");
                });
        assertThat(validator.validate("core.scheduleTrigger",
                Map.of("cron", "0 9 * * MON", "timezone", "Mars/Olympus", "missedRunPolicy", "skip")))
                .extracting(Violation::path, Violation::message)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("timezone",
                        "is not a known time zone (use an IANA name such as Europe/Berlin)"));
        // Structural errors are reported once, by the schema.
        assertThat(validator.validate("core.scheduleTrigger",
                Map.of("cron", "* * *", "timezone", "UTC", "missedRunPolicy", "skip")))
                .extracting(Violation::message).containsExactly("must have 5 or 6 space-separated cron fields");
    }

    @Test
    void unknownTypesAreNotJudged() {
        assertThat(validator.validate("vendor.custom", Map.of("anything", 1))).isEmpty();
    }

    @Test
    void registryLoadsAllCatalogTypesWithRoles() {
        assertThat(registry.all()).hasSize(9);
        assertThat(registry.find("core.manualTrigger")).get().matches(t -> t.isTrigger() && t.inputs().isEmpty());
        assertThat(registry.find("core.condition")).get().matches(t -> t.isBranching() && !t.isTrigger());
    }

    private static Map<String, Object> http(List<Map<String, String>> headers) {
        Map<String, Object> c = new java.util.LinkedHashMap<>();
        c.put("method", "GET");
        c.put("url", "https://api.example.com");
        c.put("headers", headers);
        c.put("query", List.of());
        c.put("timeoutSeconds", 10);
        return c;
    }

    @Test
    void secretsMayNotBeStoredInHeaderValues() {
        assertThat(validator.validate("core.httpRequest", http(List.of(
                Map.of("name", "Authorization", "value", "Bearer abc123"),
                Map.of("name", "x-api-key", "value", "k-123"),
                Map.of("name", "Cookie", "value", "session=1")))))
                .extracting(Violation::path)
                .containsExactly("headers[0].value", "headers[1].value", "headers[2].value");
        // References are fine (the value comes from run-time data), as are ordinary headers.
        assertThat(validator.validate("core.httpRequest", http(List.of(
                Map.of("name", "Authorization", "value", "Bearer {{ trigger.body.token }}"),
                Map.of("name", "Accept", "value", "application/json"))))).isEmpty();
    }

    @Test
    void headersManagedByTheClientAreRejected() {
        assertThat(validator.validate("core.httpRequest", http(List.of(Map.of("name", "Host", "value", "x"),
                Map.of("name", "Transfer-Encoding", "value", "chunked")))))
                .extracting(Violation::path, Violation::message)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("headers[0].name",
                                "is managed by the HTTP client and cannot be set"),
                        org.assertj.core.groups.Tuple.tuple("headers[1].name",
                                "is managed by the HTTP client and cannot be set"));
    }

    @Test
    void credentialReferencesAreCheckedByTheCredentialPort() {
        var checked = new JsonSchemaConfigurationValidator(mapper, registry,
                com.flowforge.config.TriggerProperties.defaults(),
                (id, type) -> id.startsWith("00000000") ? java.util.Optional.of("refers to a credential that does not exist")
                        : java.util.Optional.empty());
        Map<String, Object> config = http(List.of());
        config.put("credentialId", "00000000-0000-4000-8000-000000000000");
        assertThat(checked.validate("core.httpRequest", config)).extracting(Violation::path, Violation::message)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("credentialId",
                        "refers to a credential that does not exist"));
        config.put("credentialId", "3f2b8c1e-5d4a-4b6f-9e7d-1a2b3c4d5e6f");
        assertThat(checked.validate("core.httpRequest", config)).isEmpty();
        config.put("credentialId", "my-token");
        assertThat(checked.validate("core.httpRequest", config)).extracting(Violation::message)
                .containsExactly("must be the id of a stored credential");
    }

    private static Map<String, Object> ai(Object... extra) {
        Map<String, Object> c = new java.util.LinkedHashMap<>();
        c.put("provider", "openaiCompatible");
        c.put("baseUrl", "https://api.openai.com/v1");
        c.put("model", "gpt-4o-mini");
        c.put("userPrompt", "Hello {{ input.name }}");
        c.put("responseFormat", "json");
        c.put("jsonMode", "jsonObject");
        c.put("timeoutSeconds", 30);
        c.put("maxRetries", 1);
        for (int i = 0; i < extra.length; i += 2) {
            c.put((String) extra[i], extra[i + 1]);
        }
        return c;
    }

    @Test
    void responseSchemasMustStayInsideThePolicy() {
        assertThat(validator.validate("core.aiModel", ai("responseSchema", Map.of("type", "object", "properties",
                Map.of("a", Map.of("type", "string")))))).isEmpty();
        assertThat(validator.validate("core.aiModel", ai("responseSchema", Map.of("$ref", "http://169.254.169.254/"))))
                .extracting(Violation::path, Violation::message)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("responseSchema",
                        "at /$ref: keyword '$ref' is not supported (references, patterns and formats are not allowed)"));
        assertThat(validator.validate("core.aiModel", ai("responseSchema", Map.of("properties", Map.of("a",
                Map.of("pattern", "(a+)+$")))))).singleElement().satisfies(v -> assertThat(v.message())
                .contains("/properties/a/pattern"));
    }

    @Test
    void jsonOnlySettingsNeedJsonFormat() {
        assertThat(validator.validate("core.aiModel", ai("responseFormat", "text")))
                .extracting(Violation::message)
                .containsExactly("'jsonMode' and 'responseSchema' only apply when the response format is JSON");
    }

    @Test
    void aiCredentialReferencesAreChecked() {
        var checked = new JsonSchemaConfigurationValidator(mapper, registry,
                com.flowforge.config.TriggerProperties.defaults(),
                (id, type) -> type.equals("core.aiModel") ? java.util.Optional.of("refers to a credential that does not exist")
                        : java.util.Optional.empty());
        assertThat(checked.validate("core.aiModel", ai("credentialId", "3f2b8c1e-5d4a-4b6f-9e7d-1a2b3c4d5e6f")))
                .extracting(Violation::path).containsExactly("credentialId");
        assertThat(validator.validate("core.aiModel", ai("baseUrl", "https://api.example.com/v1?key=sk-1")))
                .extracting(Violation::message).containsExactly("must be an http(s) URL without query, fragment or references");
    }
}
