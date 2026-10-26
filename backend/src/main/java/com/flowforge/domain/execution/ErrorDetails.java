package com.flowforge.domain.execution;

import java.util.regex.Pattern;

/**
 * Sanitized error information safe to persist and show in the UI. Never holds stack traces.
 * Build instances with {@link #sanitized(String, String)}.
 */
public record ErrorDetails(String code, String message) {

    public static final int MAX_CODE_LENGTH = 100;
    public static final int MAX_MESSAGE_LENGTH = 2000;
    public static final String UNKNOWN_CODE = "UNKNOWN_ERROR";

    private static final Pattern CODE_FORMAT = Pattern.compile("^[A-Z][A-Z0-9_.]{0,99}$");
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\p{Cntrl}&&[^\\n\\t]]");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)\\b(password|passwd|secret|token|api[_-]?key|authorization)(\\s*[:=]\\s*)"
                    + "((?:bearer|basic)\\s+\\S+|\"[^\"]*\"|\\S+)");
    private static final Pattern BEARER = Pattern.compile("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]+");

    public ErrorDetails {
        if (code == null || !CODE_FORMAT.matcher(code).matches()) {
            throw new IllegalArgumentException("Invalid error code: " + code);
        }
        if (message != null && message.length() > MAX_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("Error message exceeds " + MAX_MESSAGE_LENGTH + " characters");
        }
    }

    /**
     * Normalizes an arbitrary code and message: unknown code formats become {@link #UNKNOWN_CODE}, control
     * characters are removed, obvious credentials ({@code password=...}, {@code Bearer ...}) are redacted, and the
     * message is truncated to {@link #MAX_MESSAGE_LENGTH}.
     */
    public static ErrorDetails sanitized(String code, String rawMessage) {
        String safeCode = code != null && CODE_FORMAT.matcher(code).matches() ? code : UNKNOWN_CODE;
        if (rawMessage == null || rawMessage.isBlank()) {
            return new ErrorDetails(safeCode, null);
        }
        String message = CONTROL_CHARS.matcher(rawMessage).replaceAll("");
        message = SECRET_ASSIGNMENT.matcher(message).replaceAll("$1$2[REDACTED]");
        message = BEARER.matcher(message).replaceAll("Bearer [REDACTED]");
        message = message.strip();
        if (message.length() > MAX_MESSAGE_LENGTH) {
            message = message.substring(0, MAX_MESSAGE_LENGTH - 1) + "…";
        }
        return new ErrorDetails(safeCode, message);
    }
}
