package com.flowforge.application.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the model's text into JSON and checks it against the node's response schema. Models often wrap JSON in a
 * Markdown code fence; that wrapper (and only that) is tolerated. Anything else around the JSON is malformed output.
 */
public final class AiOutputParser {

    private static final Pattern FENCE = Pattern.compile("^```[A-Za-z0-9_-]*\\s*\\n(.*?)\\n?```\\s*$", Pattern.DOTALL);

    private final ObjectMapper strict;

    public AiOutputParser(ObjectMapper mapper) {
        this.strict = mapper.copy().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    /** Result of parsing: either {@code json} or a {@code problem} (never both). */
    public record Parsed(JsonNode json, String problem) {

        public boolean ok() {
            return problem == null;
        }
    }

    public Parsed parse(String text) {
        String candidate = text == null ? "" : text.strip();
        Matcher fence = FENCE.matcher(candidate);
        if (fence.matches()) {
            candidate = fence.group(1).strip();
        }
        if (candidate.isEmpty()) {
            return new Parsed(null, "the reply is empty");
        }
        try {
            return new Parsed(strict.readTree(candidate), null);
        } catch (JsonProcessingException e) {
            // The parser's location text can quote the model output; keep only the generic reason.
            return new Parsed(null, "the reply is not valid JSON (" + reason(e) + ")");
        }
    }

    /** Messages about how {@code value} violates {@code schema}; empty when it conforms. Schema must pass the policy. */
    public List<String> validate(JsonNode schema, JsonNode value) {
        JsonSchema compiled = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schema);
        return compiled.validate(value).stream().map(ValidationMessage::getMessage).sorted().limit(5).toList();
    }

    private static String reason(JsonProcessingException e) {
        String message = e.getOriginalMessage();
        int cut = message.indexOf('\n');
        message = cut >= 0 ? message.substring(0, cut) : message;
        return message.length() > 80 ? message.substring(0, 80) : message;
    }
}
