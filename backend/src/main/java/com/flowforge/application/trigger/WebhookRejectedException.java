package com.flowforge.application.trigger;

import java.util.Map;

/**
 * A webhook delivery that is refused before any execution is created. Carries the HTTP status, the problem type code
 * (see {@code https://flowforge.local/problems/<code>}) and response headers such as {@code Allow}.
 */
public class WebhookRejectedException extends RuntimeException {

    private final int status;
    private final String code;
    private final String title;
    private final Map<String, String> headers;

    public WebhookRejectedException(int status, String code, String title, String detail, Map<String, String> headers) {
        super(detail);
        this.status = status;
        this.code = code;
        this.title = title;
        this.headers = Map.copyOf(headers);
    }

    public WebhookRejectedException(int status, String code, String title, String detail) {
        this(status, code, title, detail, Map.of());
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String title() {
        return title;
    }

    public Map<String, String> headers() {
        return headers;
    }
}
