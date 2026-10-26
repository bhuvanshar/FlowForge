package com.flowforge.domain.validation;

import java.util.Locale;
import java.util.Set;

/** Request header names with special rules in the HTTP Request node and in credentials. */
public final class HeaderRules {

    /** Managed by the HTTP client or hop-by-hop: never set by workflows or credentials. */
    public static final Set<String> FORBIDDEN = Set.of("host", "content-length", "transfer-encoding", "connection",
            "keep-alive", "upgrade", "te", "trailer", "expect", "proxy-connection");

    /** Carry credentials: a literal value in workflow JSON would store a secret in plain text. Use a credential. */
    public static final Set<String> SENSITIVE = Set.of("authorization", "proxy-authorization", "cookie", "x-api-key",
            "api-key", "x-auth-token");

    private HeaderRules() {
    }

    public static boolean isForbidden(String name) {
        return name != null && FORBIDDEN.contains(name.toLowerCase(Locale.ROOT));
    }

    public static boolean isSensitive(String name) {
        return name != null && SENSITIVE.contains(name.toLowerCase(Locale.ROOT));
    }
}
