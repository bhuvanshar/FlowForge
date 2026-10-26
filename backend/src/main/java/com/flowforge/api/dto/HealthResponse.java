package com.flowforge.api.dto;

import com.flowforge.application.HealthService.HealthReport;
import com.flowforge.domain.ComponentStatus;
import java.time.Instant;
import java.util.Map;

public record HealthResponse(ComponentStatus status, String version, Instant timestamp,
                             Map<String, ComponentStatus> components) {

    public static HealthResponse from(HealthReport report, String version) {
        return new HealthResponse(report.status(), version, report.timestamp(), report.components());
    }
}
