package com.flowforge.application.credential;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.application.FieldValidationException.Violation;
import com.flowforge.domain.validation.HeaderRules;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Credential types from {@code schemas/credential-types.v1.json} (shared with the frontend). */
@Component
public class CredentialTypeRegistry {

    public static final String CATALOG = "/schemas/credential-types.v1.json";

    public record Field(String name, String label, boolean secret, int maxLength, Pattern pattern) {
    }

    public record CredentialType(String type, String label, List<String> usableBy, List<Field> fields) {

        public List<Field> publicFields() {
            return fields.stream().filter(f -> !f.secret()).toList();
        }

        public List<Field> secretFields() {
            return fields.stream().filter(Field::secret).toList();
        }

        public boolean usableBy(String nodeType) {
            return usableBy.contains(nodeType);
        }
    }

    private final Map<String, CredentialType> types = new LinkedHashMap<>();
    private final int secretMinLength;
    private final int secretMaxLength;
    private final Pattern secretPattern;

    public CredentialTypeRegistry(ObjectMapper mapper) {
        JsonNode catalog = read(mapper);
        JsonNode secret = catalog.get("secretField");
        secretMinLength = secret.get("minLength").asInt();
        secretMaxLength = secret.get("maxLength").asInt();
        secretPattern = Pattern.compile(secret.get("pattern").asText());
        for (JsonNode t : catalog.get("credentialTypes")) {
            List<Field> fields = new ArrayList<>();
            for (JsonNode f : t.get("fields")) {
                boolean isSecret = f.get("secret").asBoolean();
                fields.add(new Field(f.get("name").asText(), f.get("label").asText(), isSecret,
                        isSecret ? secretMaxLength : f.path("maxLength").asInt(200),
                        isSecret ? secretPattern : Pattern.compile(f.path("pattern").asText(".+"))));
            }
            List<String> usableBy = new ArrayList<>();
            t.get("usableBy").forEach(u -> usableBy.add(u.asText()));
            types.put(t.get("type").asText(), new CredentialType(t.get("type").asText(), t.get("label").asText(),
                    List.copyOf(usableBy), List.copyOf(fields)));
        }
    }

    public Optional<CredentialType> find(String type) {
        return Optional.ofNullable(type == null ? null : types.get(type));
    }

    public List<CredentialType> all() {
        return List.copyOf(types.values());
    }

    /** Checks non-secret fields: exactly the type's public fields, each matching its pattern. */
    public List<Violation> validatePublic(CredentialType type, Map<String, String> values) {
        List<Violation> out = new ArrayList<>();
        Map<String, String> given = values == null ? Map.of() : values;
        for (Field field : type.publicFields()) {
            String value = given.get(field.name());
            String path = "fields." + field.name();
            if (value == null || value.isBlank()) {
                out.add(new Violation(path, "is required"));
            } else if (value.length() > field.maxLength()) {
                out.add(new Violation(path, "must be at most " + field.maxLength() + " characters"));
            } else if (!field.pattern().matcher(value).matches()) {
                out.add(new Violation(path, "has an invalid format"));
            } else if (field.name().equals("headerName") && (HeaderRules.isForbidden(value)
                    || value.equalsIgnoreCase("content-type"))) {
                out.add(new Violation(path, "cannot be a header managed by the HTTP client"));
            }
        }
        given.keySet().stream().filter(k -> type.publicFields().stream().noneMatch(f -> f.name().equals(k)))
                .forEach(k -> out.add(new Violation("fields." + k, "is not a field of this credential type")));
        return out;
    }

    /** Checks secret fields: exactly the type's secret fields, 8..4096 characters, no line breaks. */
    public List<Violation> validateSecret(CredentialType type, Map<String, String> values) {
        List<Violation> out = new ArrayList<>();
        Map<String, String> given = values == null ? Map.of() : values;
        for (Field field : type.secretFields()) {
            String value = given.get(field.name());
            String path = "secret." + field.name();
            if (value == null || value.isEmpty()) {
                out.add(new Violation(path, "is required"));
            } else if (value.length() < secretMinLength || value.length() > secretMaxLength) {
                out.add(new Violation(path, "must be " + secretMinLength + " to " + secretMaxLength + " characters"));
            } else if (!secretPattern.matcher(value).matches()) {
                out.add(new Violation(path, "must not contain line breaks"));
            }
        }
        given.keySet().stream().filter(k -> type.secretFields().stream().noneMatch(f -> f.name().equals(k)))
                .forEach(k -> out.add(new Violation("secret." + k, "is not a secret field of this credential type")));
        return out;
    }

    private static JsonNode read(ObjectMapper mapper) {
        try (InputStream in = CredentialTypeRegistry.class.getResourceAsStream(CATALOG)) {
            if (in == null) {
                throw new IllegalStateException("Missing " + CATALOG);
            }
            return mapper.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
