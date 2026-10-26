package com.flowforge.application.credential;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * A decrypted credential, handed to a node executor for one request only. Never stored, logged or serialized:
 * {@link #toString()} shows no values.
 */
public record ResolvedCredential(UUID id, String name, String type, List<String> allowedHosts,
                                 Map<String, String> fields, Map<String, String> secrets) {

    /** True if the credential may be sent to {@code host} ({@code api.example.com} or {@code *.example.com}). */
    public boolean permitsHost(String host) {
        if (host == null) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        for (String allowed : allowedHosts) {
            if (allowed.startsWith("*.") ? h.endsWith(allowed.substring(1)) && h.length() > allowed.length() - 1
                    : h.equals(allowed)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return "ResolvedCredential[id=" + id + ", name=" + name + ", type=" + type + ", secrets=<hidden>]";
    }
}
