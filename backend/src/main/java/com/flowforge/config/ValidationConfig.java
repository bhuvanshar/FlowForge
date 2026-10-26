package com.flowforge.config;

import com.flowforge.domain.validation.ConfigurationValidator;
import com.flowforge.domain.validation.GraphValidator;
import com.flowforge.domain.validation.NodeTypeRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ValidationConfig {

    @Bean
    public GraphValidator graphValidator(NodeTypeRegistry registry, ConfigurationValidator configurationValidator) {
        return new GraphValidator(registry, configurationValidator);
    }
}
