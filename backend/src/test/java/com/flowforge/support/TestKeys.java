package com.flowforge.support;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * Credential encryption key for integration tests: generated per JVM, never stored anywhere (keys must not live in
 * the repository, test keys included).
 */
public final class TestKeys {

    public static final String KEY_ID = "test-1";
    public static final String KEY = random();

    private TestKeys() {
    }

    public static String random() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    public static void register(DynamicPropertyRegistry registry) {
        registry.add("flowforge.credentials.keys", () -> KEY_ID + ":" + KEY);
    }
}
