package com.flowforge.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI flowForgeOpenApi(FlowForgeProperties properties) {
        return new OpenAPI().info(new Info()
                .title("FlowForge API")
                .version(properties.version())
                .description("REST API of the FlowForge workflow automation backend. Errors are RFC 9457 Problem Details."));
    }
}
