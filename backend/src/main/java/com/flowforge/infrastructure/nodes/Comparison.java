package com.flowforge.infrastructure.nodes;

import com.fasterxml.jackson.databind.JsonNode;
import com.flowforge.application.execution.NodeExecutionException;
import com.flowforge.application.expression.ExpressionResolver;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Type-aware comparisons for {@code core.condition}. No coercion surprises:
 * <ul>
 *   <li>equals / notEquals: numerically when both sides are numbers (or numeric text), as booleans when both are
 *       booleans (or "true"/"false"), otherwise by text</li>
 *   <li>greater/less variants: both sides must be numbers, else {@code TYPE_MISMATCH}</li>
 *   <li>contains / notContains: substring of text, or an array element equal to the value (by text)</li>
 *   <li>startsWith / endsWith: the left side must be text or a number</li>
 *   <li>isEmpty / isNotEmpty: missing, null, "", [] and {} are empty</li>
 * </ul>
 */
final class Comparison {

    static final String TYPE_MISMATCH = "TYPE_MISMATCH";
    private static final Pattern NUMBER = Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?");

    private Comparison() {
    }

    static boolean evaluate(String operator, JsonNode left, JsonNode right) {
        return switch (operator) {
            case "equals" -> equal(left, right);
            case "notEquals" -> !equal(left, right);
            case "greaterThan" -> compare(operator, left, right) > 0;
            case "greaterThanOrEqual" -> compare(operator, left, right) >= 0;
            case "lessThan" -> compare(operator, left, right) < 0;
            case "lessThanOrEqual" -> compare(operator, left, right) <= 0;
            case "contains" -> contains(operator, left, right);
            case "notContains" -> !contains(operator, left, right);
            case "startsWith" -> text(operator, left).startsWith(ExpressionResolver.stringify(right));
            case "endsWith" -> text(operator, left).endsWith(ExpressionResolver.stringify(right));
            case "isEmpty" -> empty(left);
            case "isNotEmpty" -> !empty(left);
            default -> throw new NodeExecutionException(NodeConfig.INVALID_CONFIGURATION,
                    "Unsupported operator '" + operator + "'.");
        };
    }

    static Optional<BigDecimal> number(JsonNode value) {
        if (value == null) {
            return Optional.empty();
        }
        if (value.isNumber()) {
            return Optional.of(value.decimalValue());
        }
        if (value.isTextual() && NUMBER.matcher(value.textValue().trim()).matches()) {
            return Optional.of(new BigDecimal(value.textValue().trim()));
        }
        return Optional.empty();
    }

    private static Optional<Boolean> bool(JsonNode value) {
        if (value != null && value.isBoolean()) {
            return Optional.of(value.booleanValue());
        }
        if (value != null && value.isTextual() && ("true".equals(value.textValue()) || "false".equals(value.textValue()))) {
            return Optional.of(Boolean.parseBoolean(value.textValue()));
        }
        return Optional.empty();
    }

    private static boolean equal(JsonNode left, JsonNode right) {
        Optional<BigDecimal> l = number(left);
        Optional<BigDecimal> r = number(right);
        if (l.isPresent() && r.isPresent()) {
            return l.get().compareTo(r.get()) == 0;
        }
        Optional<Boolean> lb = bool(left);
        Optional<Boolean> rb = bool(right);
        if (lb.isPresent() && rb.isPresent()) {
            return lb.get().equals(rb.get());
        }
        return ExpressionResolver.stringify(left).equals(ExpressionResolver.stringify(right));
    }

    private static int compare(String operator, JsonNode left, JsonNode right) {
        Optional<BigDecimal> l = number(left);
        Optional<BigDecimal> r = number(right);
        if (l.isEmpty() || r.isEmpty()) {
            throw new NodeExecutionException(TYPE_MISMATCH, "'" + operator + "' needs two numbers, got "
                    + describe(left) + " and " + describe(right) + ".");
        }
        return l.get().compareTo(r.get());
    }

    private static boolean contains(String operator, JsonNode left, JsonNode right) {
        String needle = ExpressionResolver.stringify(right);
        if (left != null && left.isArray()) {
            for (JsonNode item : left) {
                if (ExpressionResolver.stringify(item).equals(needle)) {
                    return true;
                }
            }
            return false;
        }
        return text(operator, left).contains(needle);
    }

    private static String text(String operator, JsonNode value) {
        if (value != null && (value.isTextual() || value.isNumber())) {
            return value.asText();
        }
        throw new NodeExecutionException(TYPE_MISMATCH, "'" + operator + "' needs text, got " + describe(value) + ".");
    }

    private static boolean empty(JsonNode value) {
        return value == null || value.isMissingNode() || value.isNull()
                || (value.isTextual() && value.textValue().isEmpty())
                || (value.isContainerNode() && value.isEmpty());
    }

    static String describe(JsonNode value) {
        if (value == null || value.isMissingNode()) {
            return "nothing";
        }
        if (value.isNull()) {
            return "null";
        }
        String text = value.isTextual() ? "\"" + value.textValue() + "\"" : value.toString();
        String shown = text.length() > 40 ? text.substring(0, 37) + "..." : text;
        return value.getNodeType().name().toLowerCase() + " " + shown;
    }
}
