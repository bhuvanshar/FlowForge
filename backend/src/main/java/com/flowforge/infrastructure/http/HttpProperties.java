package com.flowforge.infrastructure.http;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Outbound HTTP settings ({@code flowforge.http.*}).
 *
 * @param allowedHosts          hostnames that may be called: exact ({@code api.example.com}, or an IP literal) or a
 *                              {@code *.example.com} wildcard for subdomains. Empty denies every request.
 * @param allowPrivateAddresses allow loopback/private/link-local/other non-public addresses (development only)
 * @param allowedPorts          ports that may be used; empty allows any port
 * @param maxResponseBytes      larger response bodies fail the node with RESPONSE_TOO_LARGE
 * @param maxRedirects          redirects followed per request (each one re-checked); more fail with TOO_MANY_REDIRECTS
 * @param connectTimeout        TCP connect limit (also capped by the node's timeout)
 * @param maxRequestBytes       larger (resolved) request bodies fail the node with REQUEST_TOO_LARGE
 */
@ConfigurationProperties(prefix = "flowforge.http")
public record HttpProperties(List<String> allowedHosts, Boolean allowPrivateAddresses, List<Integer> allowedPorts,
                             Integer maxResponseBytes, Integer maxRedirects, Duration connectTimeout,
                             Integer maxRequestBytes) {

    public HttpProperties(List<String> allowedHosts, Boolean allowPrivateAddresses, List<Integer> allowedPorts,
                          Integer maxResponseBytes, Integer maxRedirects, Duration connectTimeout) {
        this(allowedHosts, allowPrivateAddresses, allowedPorts, maxResponseBytes, maxRedirects, connectTimeout, null);
    }

    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public HttpProperties {
        allowedHosts = allowedHosts == null ? List.of() : allowedHosts.stream().map(String::trim)
                .filter(h -> !h.isEmpty()).toList();
        allowPrivateAddresses = allowPrivateAddresses != null && allowPrivateAddresses;
        allowedPorts = allowedPorts == null ? List.of() : List.copyOf(allowedPorts);
        maxResponseBytes = maxResponseBytes == null ? 1024 * 1024 : maxResponseBytes;
        maxRedirects = maxRedirects == null ? 3 : maxRedirects;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
        maxRequestBytes = maxRequestBytes == null ? 1024 * 1024 : maxRequestBytes;
    }
}
