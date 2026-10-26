package com.flowforge.application.expression;

import java.util.regex.Pattern;

/**
 * The expression grammar. {@link #TEMPLATE_PATTERN} is the same string as {@code $defs/safeString.pattern} in
 * {@code node-config.v1.schema.json} and {@code TEMPLATE_PATTERN} in the frontend (checked by tests).
 */
public final class ExpressionSyntax {

    /** A property path: identifiers separated by dots, optionally indexed, e.g. {@code nodes.http1.output.items[0].id}. */
    public static final String PATH = "[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*|\\[[0-9]+\\])*";

    /** Text in which every {@code {{ }}} holds only a {@link #PATH}. */
    public static final String TEMPLATE_PATTERN =
            "^[^{]*(?:(?:\\{(?!\\{)|\\{\\{\\s*" + PATH + "\\s*\\}\\})[^{]*)*$";

    static final Pattern TEMPLATE = Pattern.compile(TEMPLATE_PATTERN);
    static final Pattern REFERENCE = Pattern.compile("\\{\\{\\s*(" + PATH + ")\\s*\\}\\}");
    static final Pattern WHOLE_REFERENCE = Pattern.compile("^\\{\\{\\s*(" + PATH + ")\\s*\\}\\}$");
    static final Pattern PATH_ONLY = Pattern.compile("^" + PATH + "$");
    static final Pattern SEGMENT = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)|\\[([0-9]+)\\]");

    private ExpressionSyntax() {
    }
}
