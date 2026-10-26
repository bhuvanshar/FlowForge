package com.flowforge.api;

import com.flowforge.api.dto.ExecutionStartedResponse;
import com.flowforge.application.PayloadTooLargeException;
import com.flowforge.application.execution.WorkflowExecutionService;
import com.flowforge.application.trigger.WebhookService;
import com.flowforge.config.TriggerProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/** Public entry point of webhook triggers. Everything else under {@code /api/v1} is the management API. */
@RestController
@RequestMapping(ApiPaths.BASE + "/hooks")
@Tag(name = "Webhooks", description = "Receive webhook deliveries for active workflows")
public class WebhookController {

    private final WebhookService service;
    private final TriggerProperties properties;

    public WebhookController(WebhookService service, TriggerProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @RequestMapping(value = "/{webhookId}", method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT,
            RequestMethod.PATCH, RequestMethod.DELETE})
    @Operation(summary = "Deliver a webhook",
            description = "Starts the workflow whose active webhook trigger has this id. Authenticate with "
                    + "X-FlowForge-Timestamp + X-FlowForge-Signature (sha256=HMAC-SHA256(secret, timestamp + '.' + "
                    + "body)) or, for token triggers, Authorization: Bearer <secret> / X-FlowForge-Token. "
                    + "Bodies must be JSON.")
    @ApiResponse(responseCode = "202", description = "Accepted; the execution was queued")
    @ApiResponse(responseCode = "401", description = "Missing or invalid credentials",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Unknown webhook, or its workflow is not active",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "405", description = "Method not configured for this webhook (see Allow)",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Signed delivery replayed",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "413", description = "Body larger than flowforge.triggers.webhook-max-body-bytes",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "415", description = "Body is not JSON",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ExecutionStartedResponse> receive(@PathVariable String webhookId,
                                                            HttpServletRequest request) throws IOException {
        byte[] body = readBody(request);
        WorkflowExecutionService.Started started = service.receive(new WebhookService.Delivery(webhookId,
                request.getMethod(), headers(request), query(request), request.getContentType(), body));
        return ResponseEntity.accepted()
                .location(java.net.URI.create(ApiPaths.BASE + "/executions/" + started.executionId()))
                .body(new ExecutionStartedResponse(started.executionId(), started.workflowId(), started.status()));
    }

    /** Reads at most the configured limit; rejects by Content-Length first, then while streaming (chunked bodies). */
    private byte[] readBody(HttpServletRequest request) throws IOException {
        int limit = properties.webhookMaxBodyBytes();
        if (request.getContentLengthLong() > limit) {
            throw tooLarge(limit);
        }
        try (InputStream in = request.getInputStream()) {
            byte[] body = in.readNBytes(limit + 1);
            if (body.length > limit) {
                throw tooLarge(limit);
            }
            return body;
        }
    }

    private static PayloadTooLargeException tooLarge(int limit) {
        return new PayloadTooLargeException("Webhook body exceeds " + limit + " bytes.");
    }

    private static Map<String, List<String>> headers(HttpServletRequest request) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            headers.put(name.toLowerCase(Locale.ROOT), Collections.list(request.getHeaders(name)));
        }
        return headers;
    }

    /** Query string only; form parameters are never parsed (bodies must be JSON). */
    private static Map<String, List<String>> query(HttpServletRequest request) {
        Map<String, List<String>> query = new LinkedHashMap<>();
        String raw = request.getQueryString();
        if (raw == null || raw.isEmpty()) {
            return query;
        }
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            String[] kv = pair.split("=", 2);
            String key = decode(kv[0]);
            query.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(kv.length > 1 ? decode(kv[1]) : "");
        }
        return query;
    }

    private static String decode(String value) {
        try {
            return java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }
}
