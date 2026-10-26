package com.flowforge.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.type.format.jackson.JacksonJsonFormatMapper;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * JSON conventions shared by the API and JSONB columns. Hibernate serializes JSONB with Spring's
 * {@link ObjectMapper}, so stored JSON and API JSON follow the same rules.
 */
@Configuration
public class JpaJsonConfig {

    /**
     * Null record/bean properties are omitted (e.g. an absent {@code editor}), but null entries inside maps are kept:
     * {@code configuration: {"note": null}} must survive a round trip. ({@code spring.jackson.default-property-inclusion}
     * would drop both.)
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer nullHandlingCustomizer() {
        return builder -> builder.postConfigurer(mapper -> mapper.setDefaultPropertyInclusion(
                JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.ALWAYS)));
    }

    @Bean
    public HibernatePropertiesCustomizer jsonFormatMapperCustomizer(ObjectMapper objectMapper) {
        return properties -> properties.put(AvailableSettings.JSON_FORMAT_MAPPER,
                new JacksonJsonFormatMapper(objectMapper));
    }
}
