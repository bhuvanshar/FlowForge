package com.flowforge.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Released migrations are immutable: Flyway refuses to start against a database whose history checksum differs from the
 * file, so editing V1..V8 would break every existing installation. Schema changes go into a new V9+ file; add its hash
 * here when it is released.
 */
class MigrationChecksumTest {

    private static final Map<String, String> RELEASED = new LinkedHashMap<>();

    static {
        RELEASED.put("V1__baseline.sql", "7a5603880e182498aea7ebfa4fb1e8f81da0099a5095435288085c642e4b1c9a");
        RELEASED.put("V2__create_workflows.sql", "6333cbffb37d6a8f276eccb500dfa69ccec0fb5945d8d7c2aac37af84005072e");
        RELEASED.put("V3__create_workflow_executions.sql",
                "d34945279cbf1f7fce7dc0d1d8e30665d5e584f937eacd5703d60825c162a6da");
        RELEASED.put("V4__create_node_executions.sql", "5510d9b8612c7552fb75bd18d33f169884c7a83db1a36b6f3bc508ba1af60dc8");
        RELEASED.put("V5__create_execution_logs.sql", "7669c857298b2dd404cc28e63faeb529e3acccf9dd10c89cac5dcda1b600830f");
        RELEASED.put("V6__create_triggers.sql", "1147689e36da781fc9bd9fa9c9ebd7488203838005c2be178f1901094c99cba9");
        RELEASED.put("V7__create_credentials.sql", "4ea2b96448fbfcbc061bc21b2411598f6ecdcc3aa2c0f828835492806320972b");
        RELEASED.put("V8__encrypt_webhook_secrets.sql", "d2800ec07e576bdc73a5843732967094631a96d20ce76b8596c19f47b53f524c");
    }

    @Test
    void releasedMigrationsAreUnchangedAndNumberedWithoutGaps() throws Exception {
        Map<String, String> actual = new TreeMap<>((a, b) -> Integer.compare(version(a), version(b)));
        for (Resource file : new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/*.sql")) {
            try (InputStream in = file.getInputStream()) {
                actual.put(file.getFilename(), HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(in.readAllBytes())));
            }
        }

        RELEASED.forEach((name, hash) -> assertThat(actual.get(name)).as("%s was modified after release", name)
                .isEqualTo(hash));
        int expected = 1;
        for (String name : actual.keySet()) {
            assertThat(version(name)).as("migration numbering: %s", name).isEqualTo(expected++);
            assertThat(name).matches("V\\d+__[a-z0-9_]+\\.sql");
        }
    }

    private static int version(String fileName) {
        return Integer.parseInt(fileName.substring(1, fileName.indexOf("__")));
    }
}
