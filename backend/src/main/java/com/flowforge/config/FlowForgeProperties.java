package com.flowforge.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param version      application version reported by the health endpoint
 * @param allowedOrigins browser origins allowed to call the API (CORS); localhost only by default
 */
@ConfigurationProperties(prefix = "flowforge")
public record FlowForgeProperties(String version, List<String> allowedOrigins) {

    public FlowForgeProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }
}
