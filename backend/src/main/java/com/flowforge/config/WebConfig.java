package com.flowforge.config;

import java.util.regex.Pattern;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS for the management API: only the configured browser origins (exact {@code scheme://host[:port]}, never
 * {@code *}), only the methods and headers the frontend uses, no cookies or other browser credentials. Webhooks
 * ({@code /api/v1/hooks}) get no CORS headers: they are called by servers, not browser pages.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private static final Pattern ORIGIN = Pattern.compile("^https?://[A-Za-z0-9.\\-\\[\\]:]+$");
    private static final String[] MANAGEMENT_PATHS = {"/api/v1/workflows/**", "/api/v1/executions/**",
            "/api/v1/credentials/**", "/api/v1/credentials", "/api/v1/workflows", "/api/v1/samples/**", "/api/v1/samples", "/api/v1/settings",
            "/api/v1/health"};

    private final FlowForgeProperties properties;

    public WebConfig(FlowForgeProperties properties) {
        this.properties = properties;
        for (String origin : properties.allowedOrigins()) {
            if (!ORIGIN.matcher(origin).matches()) {
                throw new IllegalStateException("flowforge.allowed-origins entry '" + origin + "' must be an exact "
                        + "origin such as http://localhost:5173 (wildcards and paths are not allowed)");
            }
        }
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (properties.allowedOrigins().isEmpty()) {
            return;
        }
        for (String path : MANAGEMENT_PATHS) {
            registry.addMapping(path)
                    .allowedOrigins(properties.allowedOrigins().toArray(String[]::new))
                    .allowedMethods("GET", "POST", "PUT", "DELETE")
                    .allowedHeaders("Content-Type", "Accept", "Last-Event-ID", "Cache-Control")
                    .exposedHeaders("Location")
                    .allowCredentials(false)
                    .maxAge(600);
        }
    }
}
