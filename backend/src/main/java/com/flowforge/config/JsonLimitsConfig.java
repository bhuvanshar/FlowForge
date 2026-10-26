package com.flowforge.config;

import com.fasterxml.jackson.core.StreamReadConstraints;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Parser limits for every JSON document the server reads (API bodies, webhook payloads, stored JSONB): bounded nesting,
 * string and number sizes, so a small hostile document cannot exhaust the stack or memory.
 */
@Configuration
public class JsonLimitsConfig {

    public static final int MAX_NESTING_DEPTH = 100;
    public static final int MAX_STRING_LENGTH = 2_000_000;
    public static final int MAX_NUMBER_LENGTH = 100;

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jsonReadLimits() {
        return builder -> builder.postConfigurer(mapper -> mapper.getFactory().setStreamReadConstraints(
                StreamReadConstraints.builder()
                        .maxNestingDepth(MAX_NESTING_DEPTH)
                        .maxStringLength(MAX_STRING_LENGTH)
                        .maxNumberLength(MAX_NUMBER_LENGTH)
                        .build()));
    }
}
