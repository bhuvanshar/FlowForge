package com.flowforge.domain.validation;

import java.util.List;
import java.util.Map;

/** Port: validates one node's {@code configuration} against its node type's configuration contract. */
public interface ConfigurationValidator {

    /**
     * @param path    location inside the configuration, e.g. {@code url} or {@code variables[1].name}; empty for the
     *                configuration object itself
     * @param message human-readable, safe to show in the editor
     */
    record Violation(String path, String message) {
    }

    /** Returns no violations for types it does not know (the graph validator reports unknown types). */
    List<Violation> validate(String type, Map<String, Object> configuration);
}
