package com.flowforge.application.expression;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Matcher;
import org.springframework.stereotype.Component;

/**
 * Resolves {@code {{ path }}} references against an {@link ExpressionScope}. Pure property lookup on JSON trees: there
 * is no evaluation, so configuration can never run code.
 *
 * <p>Rules:
 * <ul>
 *   <li>A value that is exactly one reference ({@code "{{ input.total }}"}) resolves to the referenced JSON value with its
 *       type (number, object, ...); a missing value becomes {@code null}.</li>
 *   <li>References inside other text are replaced by their text form: strings as-is, numbers and booleans as written,
 *       objects and arrays as compact JSON, missing or null as the empty string.</li>
 *   <li>Unknown roots, or anything inside {@code {{ }}} other than a path, raise {@link ExpressionException}.</li>
 * </ul>
 */
@Component
public class ExpressionResolver {

    /** Resolves a template to a JSON value (typed when the template is a single reference). */
    public JsonNode value(String template, ExpressionScope scope) {
        check(template);
        Matcher whole = ExpressionSyntax.WHOLE_REFERENCE.matcher(template);
        if (whole.matches()) {
            JsonNode value = lookup(whole.group(1), scope);
            return value.isMissingNode() ? NullNode.getInstance() : value;
        }
        return TextNode.valueOf(text(template, scope));
    }

    /** Resolves a template to text. */
    public String text(String template, ExpressionScope scope) {
        check(template);
        Matcher matcher = ExpressionSyntax.REFERENCE.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(stringify(lookup(matcher.group(1), scope))));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** Resolves every string inside a JSON value (keys are left untouched). */
    public JsonNode json(JsonNode value, ExpressionScope scope) {
        if (value == null) {
            return NullNode.getInstance();
        }
        if (value.isTextual()) {
            return value(value.textValue(), scope);
        }
        if (value.isArray()) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            value.forEach(item -> out.add(json(item, scope)));
            return out;
        }
        if (value.isObject()) {
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            for (Iterator<Map.Entry<String, JsonNode>> it = value.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> field = it.next();
                out.set(field.getKey(), json(field.getValue(), scope));
            }
            return out;
        }
        return value;
    }

    /** Looks up a full path such as {@code vars.total}; returns {@link MissingNode} when absent. */
    public JsonNode lookup(String path, ExpressionScope scope) {
        Matcher segments = segments(path);
        segments.find();
        String root = segments.group(1);
        if (root == null) {
            throw new ExpressionException("A reference must start with a name: '" + path + "'.");
        }
        JsonNode current = switch (root) {
            case "input" -> scope.input();
            case "trigger" -> JsonNodeFactory.instance.objectNode().set("body", scope.context().triggerInput());
            case "vars" -> scope.context().variables();
            case "nodes" -> nodesRoot(segments, path, scope);
            default -> throw new ExpressionException("Unknown reference '" + root + "' in '" + path
                    + "'. Use input, trigger, vars or nodes.");
        };
        return walk(current, segments);
    }

    /** Looks up a plain path ({@code order.total}) relative to {@code root}; returns {@link MissingNode} when absent. */
    public JsonNode lookupIn(JsonNode root, String path) {
        return walk(root, segments(path));
    }

    /** The text form used when a reference is embedded in text. */
    public static String stringify(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return "";
        }
        if (value.isTextual()) {
            return value.textValue();
        }
        if (value.isValueNode()) {
            return value.asText();
        }
        return value.toString();
    }

    private static JsonNode nodesRoot(Matcher segments, String path, ExpressionScope scope) {
        if (!segments.find() || segments.group(1) == null) {
            throw new ExpressionException("Use nodes.<nodeId>.output in '" + path + "'.");
        }
        return scope.context().outputOf(segments.group(1))
                .<JsonNode>map(output -> JsonNodeFactory.instance.objectNode().set("output", output))
                .orElse(MissingNode.getInstance());
    }

    private static JsonNode walk(JsonNode current, Matcher segments) {
        while (segments.find()) {
            if (current == null || current.isMissingNode()) {
                return MissingNode.getInstance();
            }
            if (segments.group(1) != null) {
                current = current.isObject() ? current.path(segments.group(1)) : MissingNode.getInstance();
            } else {
                int index = Integer.parseInt(segments.group(2));
                current = current.isArray() ? current.path(index) : MissingNode.getInstance();
            }
        }
        return current == null ? MissingNode.getInstance() : current;
    }

    private static Matcher segments(String path) {
        if (path == null || !ExpressionSyntax.PATH_ONLY.matcher(path).matches()) {
            throw new ExpressionException("Not a valid path: '" + path + "'.");
        }
        return ExpressionSyntax.SEGMENT.matcher(path);
    }

    private static void check(String template) {
        if (template == null || !ExpressionSyntax.TEMPLATE.matcher(template).matches()) {
            throw new ExpressionException("Unsupported expression in '" + abbreviate(template)
                    + "'. Only {{ path.to.value }} references are allowed.");
        }
    }

    private static String abbreviate(String text) {
        return text == null ? "null" : text.length() <= 80 ? text : text.substring(0, 77) + "...";
    }
}
