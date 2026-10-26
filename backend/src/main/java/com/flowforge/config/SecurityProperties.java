package com.flowforge.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Request hardening ({@code flowforge.security.*}).
 *
 * @param allowedHostNames  values accepted in the {@code Host} header (without port). Requests for any other name are
 *                          refused: this defeats DNS rebinding against the unauthenticated localhost API. Add the public
 *                          name of a tunnel here if webhooks are exposed through one.
 * @param maxRequestBytes   request body limit of the management API (webhooks have their own limit)
 */
@ConfigurationProperties(prefix = "flowforge.security")
public record SecurityProperties(List<String> allowedHostNames, Integer maxRequestBytes) {

    public SecurityProperties {
        allowedHostNames = allowedHostNames == null || allowedHostNames.isEmpty()
                ? List.of("localhost", "127.0.0.1", "[::1]")
                : allowedHostNames.stream().map(String::strip).filter(h -> !h.isEmpty()).toList();
        maxRequestBytes = maxRequestBytes == null ? 1024 * 1024 : maxRequestBytes;
    }
}
