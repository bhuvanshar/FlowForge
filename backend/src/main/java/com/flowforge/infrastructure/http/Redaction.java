package com.flowforge.infrastructure.http;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Hides secrets in what is stored in execution history (request echo, URLs, response headers, errors). */
public final class Redaction {

    public static final String REDACTED = "[REDACTED]";

    private static final Set<String> SENSITIVE_HEADERS = Set.of("authorization", "proxy-authorization", "cookie",
            "set-cookie", "x-api-key", "api-key", "x-auth-token", "x-csrf-token");
    private static final Pattern SENSITIVE_NAME = Pattern.compile("(?i).*(token|secret|password|passwd|api[-_]?key|"
            + "signature|session|credential).*");
    private static final Pattern SENSITIVE_QUERY = Pattern.compile(
            "(?i)([?&](?:[^=&]*(?:token|secret|password|passwd|key|signature|sig|auth|session|credential)[^=&]*)=)[^&#]*");

    private Redaction() {
    }

    public static boolean isSensitiveHeader(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return SENSITIVE_HEADERS.contains(n) || SENSITIVE_NAME.matcher(n).matches();
    }

    public static String header(String name, String value) {
        return isSensitiveHeader(name) ? REDACTED : value;
    }

    /** URL with values of sensitive query parameters replaced. */
    public static String url(URI uri) {
        return url(uri.toString());
    }

    public static String url(String url) {
        return SENSITIVE_QUERY.matcher(url).replaceAll("$1" + REDACTED);
    }
}
