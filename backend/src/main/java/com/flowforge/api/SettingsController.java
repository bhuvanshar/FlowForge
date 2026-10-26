package com.flowforge.api;

import com.flowforge.application.SettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiPaths.BASE + "/settings")
@Tag(name = "Settings", description = "Read-only view of the server configuration")
public class SettingsController {

    private final SettingsService service;

    public SettingsController(SettingsService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Effective server settings (read-only)",
            description = "Limits, outbound allowlist, trigger and security settings as the server runs with them. "
                    + "Contains no secrets; change values with environment variables and restart.")
    public ResponseEntity<SettingsService.Settings> get() {
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(service.current());
    }
}
