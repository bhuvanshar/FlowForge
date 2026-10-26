package com.flowforge.api;

import com.flowforge.api.dto.TriggersResponse;
import com.flowforge.api.dto.WebhookSecretResponse;
import com.flowforge.application.trigger.TriggerRegistrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiPaths.BASE + "/workflows/{id}/triggers")
@Tag(name = "Triggers", description = "Webhook and schedule trigger registrations of a workflow")
@ApiResponse(responseCode = "404", description = "Workflow not found (or, for the secret endpoints, no webhook trigger)",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
public class TriggerController {

    private final TriggerRegistrationService service;

    public TriggerController(TriggerRegistrationService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Get the trigger registration state",
            description = "Webhook id, path, method and authentication mode; schedule next and last occurrence.")
    @ApiResponse(responseCode = "200", description = "Trigger state")
    public TriggersResponse get(@PathVariable UUID id) {
        return TriggersResponse.from(service.view(id));
    }

    @GetMapping("/webhook/secret")
    @Operation(summary = "Reveal the webhook secret", description = "Never cached (Cache-Control: no-store).")
    @ApiResponse(responseCode = "200", description = "The secret")
    public ResponseEntity<WebhookSecretResponse> secret(@PathVariable UUID id) {
        return noStore(service.webhookSecret(id));
    }

    @PostMapping("/webhook/secret/rotate")
    @Operation(summary = "Rotate the webhook secret",
            description = "Generates a new secret; deliveries using the old one are rejected immediately.")
    @ApiResponse(responseCode = "200", description = "The new secret")
    public ResponseEntity<WebhookSecretResponse> rotate(@PathVariable UUID id) {
        return noStore(service.rotateWebhookSecret(id));
    }

    private static ResponseEntity<WebhookSecretResponse> noStore(String secret) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new WebhookSecretResponse(secret));
    }
}
