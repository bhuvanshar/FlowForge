package com.flowforge.api;

import com.flowforge.api.dto.HealthResponse;
import com.flowforge.application.HealthService;
import com.flowforge.config.FlowForgeProperties;
import com.flowforge.domain.ComponentStatus;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiPaths.BASE + "/health")
public class HealthController {

    private final HealthService healthService;
    private final FlowForgeProperties properties;

    public HealthController(HealthService healthService, FlowForgeProperties properties) {
        this.healthService = healthService;
        this.properties = properties;
    }

    @GetMapping
    public ResponseEntity<HealthResponse> health() {
        HealthResponse body = HealthResponse.from(healthService.report(), properties.version());
        HttpStatus status = body.status() == ComponentStatus.UP ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(body);
    }
}
