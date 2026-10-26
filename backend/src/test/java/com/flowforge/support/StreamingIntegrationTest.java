package com.flowforge.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Integration tests that need a real listening HTTP server (Server-Sent Events cannot be consumed through MockMvc).
 * Shares the PostgreSQL container with {@link PostgresIntegrationTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class StreamingIntegrationTest {

    @ServiceConnection
    protected static final PostgreSQLContainer<?> POSTGRES = PostgresIntegrationTest.POSTGRES;

    @LocalServerPort
    protected int port;

    protected String baseUrl() {
        return "http://127.0.0.1:" + port + "/api/v1";
    }

    @DynamicPropertySource
    static void credentialKeys(DynamicPropertyRegistry registry) {
        TestKeys.register(registry);
    }
}
