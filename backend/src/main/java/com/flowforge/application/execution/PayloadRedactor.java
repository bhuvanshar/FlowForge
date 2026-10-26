package com.flowforge.application.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Defense in depth for execution history: values stored under credential-looking keys are replaced by
 * {@code [REDACTED]}, and {@code Bearer <token>} / {@code Basic <credentials>} strings are masked, in every stored node input
 * and output. Only the stored copy is changed; downstream nodes still receive the real data in memory.
 *
 * <p>Matching is by key name, case-insensitive, anywhere in the key ({@code access_token}, {@code X-Api-Key}, {@code Set-Cookie}),
 * so a legitimate field such as {@code tokenCount} is masked too. That trade-off is deliberate: history is for debugging and
 * must never leak secrets.
 */
public final class PayloadRedactor {

    public static final String REDACTED = "[REDACTED]";

    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            "(?i).*(authorization|password|passwd|secret|token|api[-_]?key|cookie|credential|private[-_]?key|signature).*");
    private static final Pattern AUTH_SCHEME = Pattern.compile("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._~+/=-]{6,}");

    private PayloadRedactor() {
    }

    public static boolean isSensitiveKey(String key) {
        return SENSITIVE_KEY.matcher(key).matches();
    }

    /** Returns a redacted deep copy; the argument is not modified. */
    public static JsonNode redact(JsonNode value) {
        if (value == null) {
            return null;
        }
        if (value.isObject()) {
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            for (Iterator<Map.Entry<String, JsonNode>> it = value.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> field = it.next();
                JsonNode child = field.getValue();
                boolean hidden = isSensitiveKey(field.getKey()) && !child.isNull();
                out.set(field.getKey(), hidden ? TextNode.valueOf(REDACTED) : redact(child));
            }
            return out;
        }
        if (value.isArray()) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            value.forEach(item -> out.add(redact(item)));
            return out;
        }
        if (value.isTextual()) {
            String text = value.textValue();
            String masked = AUTH_SCHEME.matcher(text).replaceAll("$1 " + REDACTED);
            return masked.equals(text) ? value : TextNode.valueOf(masked);
        }
        return value;
    }
}
