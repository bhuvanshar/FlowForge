package com.flowforge.application;

import com.flowforge.domain.ComponentStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class HealthService {

    private final List<HealthProbe> probes;
    private final Clock clock;

    public HealthService(List<HealthProbe> probes, Clock clock) {
        this.probes = List.copyOf(probes);
        this.clock = clock;
    }

    public HealthReport report() {
        Map<String, ComponentStatus> components = probes.stream()
                .collect(Collectors.toMap(HealthProbe::componentName, HealthProbe::check));
        ComponentStatus overall = components.values().stream().allMatch(s -> s == ComponentStatus.UP)
                ? ComponentStatus.UP
                : ComponentStatus.DOWN;
        return new HealthReport(overall, components, clock.instant());
    }

    public record HealthReport(ComponentStatus status, Map<String, ComponentStatus> components, Instant timestamp) {
    }
}
