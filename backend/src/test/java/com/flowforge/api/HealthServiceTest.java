package com.flowforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowforge.application.HealthProbe;
import com.flowforge.application.HealthService;
import com.flowforge.domain.ComponentStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class HealthServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    private static HealthProbe probe(String name, ComponentStatus status) {
        return new HealthProbe() {
            @Override
            public String componentName() {
                return name;
            }

            @Override
            public ComponentStatus check() {
                return status;
            }
        };
    }

    @Test
    void overallIsUpWhenAllProbesUp() {
        var report = new HealthService(List.of(probe("database", ComponentStatus.UP)), clock).report();

        assertThat(report.status()).isEqualTo(ComponentStatus.UP);
        assertThat(report.timestamp()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void overallIsDownWhenAnyProbeDown() {
        var report = new HealthService(
                List.of(probe("database", ComponentStatus.UP), probe("other", ComponentStatus.DOWN)), clock).report();

        assertThat(report.status()).isEqualTo(ComponentStatus.DOWN);
        assertThat(report.components()).containsEntry("other", ComponentStatus.DOWN);
    }
}
