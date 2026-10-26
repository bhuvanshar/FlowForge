package com.flowforge.support;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for integration tests against a real, initially empty PostgreSQL. One container is started per JVM
 * and shared by all subclasses (singleton pattern), so the Spring context is cached across test classes too.
 * Flyway runs all migrations on context start. The test profile allows outbound HTTP to localhost so the real HTTP
 * Request executor can call {@link LocalHttpServer}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class PostgresIntegrationTest {

    @ServiceConnection
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void credentialKeys(DynamicPropertyRegistry registry) {
        TestKeys.register(registry);
    }
}
