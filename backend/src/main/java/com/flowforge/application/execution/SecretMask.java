package com.flowforge.application.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.flowforge.domain.execution.ErrorDetails;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Value-based redaction for one execution. Node executors register every secret they put on the wire (credential
 * values and derived forms such as a Basic authorization value); the engine then replaces any occurrence of those values
 * with {@code [REDACTED]} in everything it stores or publishes: node input and output, errors and log lines. This
 * catches secrets that key-name redaction ({@link PayloadRedactor}) cannot see, e.g. a server echoing a token in its
 * response body, or a later Logger node printing it.
 *
 * <p>In-memory data passed between nodes is not changed. Values shorter than {@link #MIN_LENGTH} are not registered
 * (credential secrets are at least 8 characters). Thread-safe.
 */
public final class SecretMask {

    public static final String REDACTED = "[REDACTED]";
    public static final int MIN_LENGTH = 6;

    private final Set<String> secrets = ConcurrentHashMap.newKeySet();
    private volatile List<String> ordered = List.of();

    /** Registers a secret and its URL-encoded form. */
    public void register(String secret) {
        if (secret == null || secret.length() < MIN_LENGTH) {
            return;
        }
        boolean changed = secrets.add(secret);
        String encoded = URLEncoder.encode(secret, StandardCharsets.UTF_8);
        changed |= !encoded.equals(secret) && secrets.add(encoded);
        if (changed) {
            // Longest first, so a secret containing another one is masked as a whole.
            ordered = secrets.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList();
        }
    }

    public boolean isEmpty() {
        return secrets.isEmpty();
    }

    public String mask(String text) {
        if (text == null || ordered.isEmpty()) {
            return text;
        }
        String out = text;
        for (String secret : ordered) {
            if (out.contains(secret)) {
                out = out.replace(secret, REDACTED);
            }
        }
        return out;
    }

    /** A masked deep copy (or the same node when nothing matched). Field names are masked too. */
    public JsonNode mask(JsonNode value) {
        if (value == null || ordered.isEmpty()) {
            return value;
        }
        if (value.isTextual()) {
            String masked = mask(value.textValue());
            return masked.equals(value.textValue()) ? value : TextNode.valueOf(masked);
        }
        if (value.isObject()) {
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            for (Iterator<Map.Entry<String, JsonNode>> it = value.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> field = it.next();
                out.set(mask(field.getKey()), mask(field.getValue()));
            }
            return out;
        }
        if (value.isArray()) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            value.forEach(item -> out.add(mask(item)));
            return out;
        }
        if (value.isNumber()) {
            String text = value.asText();
            String masked = mask(text);
            // A server can echo an all-digit credential as a JSON number. Only the persisted copy changes type.
            return masked.equals(text) ? value : TextNode.valueOf(masked);
        }
        return value;
    }

    public ErrorDetails mask(ErrorDetails error) {
        if (error == null || ordered.isEmpty()) {
            return error;
        }
        String masked = mask(error.message());
        return masked == null || masked.equals(error.message()) ? error : ErrorDetails.sanitized(error.code(), masked);
    }
}
