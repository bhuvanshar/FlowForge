package com.flowforge.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

/**
 * Request bodies of {@code /api/v1/credentials}. Records holding secrets override {@code toString()} so that a logged
 * request never shows them.
 */
public final class CredentialRequests {

    private CredentialRequests() {
    }

    /** @param secret the secret fields of the type, e.g. {@code {"token": "…"}}; write-only */
    public record Create(@NotNull @Size(max = 40) String type, @NotNull String name, String description,
                         @NotNull @Size(max = 20) List<String> allowedHosts, @Size(max = 10) Map<String, String> fields,
                         @NotNull @Size(max = 10) Map<String, String> secret) {

        @Override
        public String toString() {
            return "Create[type=" + type + ", name=" + name + ", secret=<hidden>]";
        }
    }

    /** Everything except the secret; {@code fields} are the type's non-secret fields. */
    public record Update(@NotNull String name, String description, @NotNull @Size(max = 20) List<String> allowedHosts,
                         @Size(max = 10) Map<String, String> fields, @NotNull Long lockVersion) {
    }

    /** New secret values; {@code lockVersion} is optional. */
    public record Rotate(@NotNull @Size(max = 10) Map<String, String> secret, Long lockVersion) {

        @Override
        public String toString() {
            return "Rotate[secret=<hidden>]";
        }
    }
}
