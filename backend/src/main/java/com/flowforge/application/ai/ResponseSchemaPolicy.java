package com.flowforge.application.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which JSON Schemas may be used to validate AI output. Schemas are written by workflow authors and evaluated by the
 * server, so only a small, side-effect-free subset is accepted: no {@code $ref} (nothing is ever fetched), no
 * {@code pattern} or {@code format} (regular expressions can be made to run for very long), and bounded size, depth and
 * enum length. The same rules exist in the editor (checked against the shared fixtures).
 */
public final class ResponseSchemaPolicy {

    public static final int MAX_DEPTH = 10;
    public static final int MAX_NODES = 500;
    public static final int MAX_ENUM_VALUES = 100;
    public static final int MAX_BYTES = 16 * 1024;

    private static final Set<String> TYPES = Set.of("object", "array", "string", "number", "integer", "boolean", "null");
    private static final Set<String> NUMBER_KEYWORDS = Set.of("minimum", "maximum", "exclusiveMinimum",
            "exclusiveMaximum", "minLength", "maxLength", "minItems", "maxItems");
    private static final Set<String> COUNT_KEYWORDS = Set.of("minLength", "maxLength", "minItems", "maxItems");
    private static final Set<String> SCHEMA_LIST_KEYWORDS = Set.of("anyOf", "oneOf", "allOf");
    private static final Set<String> KEYWORDS = Set.of("type", "properties", "required", "additionalProperties",
            "items", "enum", "const", "uniqueItems", "description", "title", "anyOf", "oneOf", "allOf", "minimum",
            "maximum", "exclusiveMinimum", "exclusiveMaximum", "minLength", "maxLength", "minItems", "maxItems");

    private ResponseSchemaPolicy() {
    }

    public record Problem(String path, String message) {
    }

    /** Problems of a schema (empty = acceptable). {@code path} is a JSON-pointer-like location inside the schema. */
    public static List<Problem> check(JsonNode schema) {
        List<Problem> problems = new ArrayList<>();
        if (schema == null || !schema.isObject()) {
            return List.of(new Problem("", "must be a JSON Schema object"));
        }
        if (schema.toString().length() > MAX_BYTES) {
            return List.of(new Problem("", "is larger than " + MAX_BYTES + " characters"));
        }
        int[] nodes = {0};
        schema(schema, "", 1, nodes, problems);
        if (nodes[0] > MAX_NODES && problems.isEmpty()) {
            problems.add(new Problem("", "has more than " + MAX_NODES + " schema nodes"));
        }
        return problems;
    }

    private static void schema(JsonNode node, String path, int depth, int[] nodes, List<Problem> out) {
        if (depth > MAX_DEPTH) {
            out.add(new Problem(path, "is nested deeper than " + MAX_DEPTH + " levels"));
            return;
        }
        if (++nodes[0] > MAX_NODES || !node.isObject()) {
            if (!node.isObject()) {
                out.add(new Problem(path, "must be a schema object"));
            }
            return;
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> field = it.next();
            String name = field.getKey();
            JsonNode value = field.getValue();
            String at = path + "/" + name;
            if (!KEYWORDS.contains(name)) {
                out.add(new Problem(at, "keyword '" + name + "' is not supported"
                        + (name.startsWith("$") || name.equals("pattern") || name.equals("format")
                        ? " (references, patterns and formats are not allowed)" : "")));
                continue;
            }
            switch (name) {
                case "type" -> checkType(value, at, out);
                case "properties" -> {
                    if (!value.isObject()) {
                        out.add(new Problem(at, "must be an object of schemas"));
                    } else {
                        value.fields().forEachRemaining(p -> schema(p.getValue(), at + "/" + p.getKey(), depth + 1,
                                nodes, out));
                    }
                }
                case "required" -> {
                    if (!value.isArray() || !allText(value)) {
                        out.add(new Problem(at, "must be a list of property names"));
                    }
                }
                case "additionalProperties" -> {
                    if (value.isObject()) {
                        schema(value, at, depth + 1, nodes, out);
                    } else if (!value.isBoolean()) {
                        out.add(new Problem(at, "must be true, false or a schema"));
                    }
                }
                case "items" -> schema(value, at, depth + 1, nodes, out);
                case "enum" -> {
                    if (!value.isArray() || value.isEmpty() || value.size() > MAX_ENUM_VALUES) {
                        out.add(new Problem(at, "must be a list of 1 to " + MAX_ENUM_VALUES + " values"));
                    }
                }
                case "uniqueItems" -> {
                    if (!value.isBoolean()) {
                        out.add(new Problem(at, "must be true or false"));
                    }
                }
                case "description", "title" -> {
                    if (!value.isTextual() || value.textValue().length() > 1000) {
                        out.add(new Problem(at, "must be text of at most 1000 characters"));
                    }
                }
                default -> {
                    if (SCHEMA_LIST_KEYWORDS.contains(name)) {
                        if (!value.isArray() || value.isEmpty() || value.size() > 20) {
                            out.add(new Problem(at, "must be a list of 1 to 20 schemas"));
                        } else {
                            for (int i = 0; i < value.size(); i++) {
                                schema(value.get(i), at + "/" + i, depth + 1, nodes, out);
                            }
                        }
                    } else if (COUNT_KEYWORDS.contains(name) && (!value.isNumber()
                            || value.decimalValue().signum() < 0
                            || value.decimalValue().stripTrailingZeros().scale() > 0)) {
                        out.add(new Problem(at, "must be a non-negative integer"));
                    } else if (NUMBER_KEYWORDS.contains(name) && !value.isNumber()) {
                        out.add(new Problem(at, "must be a number"));
                    }
                    // const: any JSON value
                }
            }
        }
    }

    private static void checkType(JsonNode value, String at, List<Problem> out) {
        boolean ok = value.isTextual() ? TYPES.contains(value.textValue())
                : value.isArray() && !value.isEmpty() && allText(value)
                && java.util.stream.StreamSupport.stream(value.spliterator(), false)
                .allMatch(t -> TYPES.contains(t.textValue()));
        if (!ok) {
            out.add(new Problem(at, "must be one of " + TYPES + " (or a list of them)"));
        }
    }

    private static boolean allText(JsonNode array) {
        for (JsonNode item : array) {
            if (!item.isTextual()) {
                return false;
            }
        }
        return true;
    }
}
