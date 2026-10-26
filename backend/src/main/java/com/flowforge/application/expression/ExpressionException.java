package com.flowforge.application.expression;

/** An expression is malformed or refers to an unknown root. Executors report it as {@code EXPRESSION_ERROR}. */
public class ExpressionException extends RuntimeException {

    public static final String CODE = "EXPRESSION_ERROR";

    public ExpressionException(String message) {
        super(message);
    }
}
