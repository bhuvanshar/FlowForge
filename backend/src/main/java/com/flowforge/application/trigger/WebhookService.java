package com.flowforge.application.trigger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowforge.application.ConflictException;
import com.flowforge.application.ResourceNotFoundException;
import com.flowforge.application.execution.WorkflowExecutionService;
import com.flowforge.config.TriggerProperties;
import com.flowforge.domain.execution.TriggerType;
import com.flowforge.infrastructure.persistence.WebhookTriggerEntity;
import com.flowforge.infrastructure.persistence.WebhookTriggerRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

/**
 * Handles deliveries to {@code /api/v1/hooks/{webhookId}}. Checks run in this order and stop at the first failure:
 * <ol>
 *   <li>registration exists and is active (the workflow is ACTIVE) - otherwise 404, indistinguishable from unknown;</li>
 *   <li>HTTP method matches the trigger - 405 with {@code Allow};</li>
 *   <li>authentication ({@code signature}: HMAC + timestamp window; {@code token}: shared secret) - 401;</li>
 *   <li>signed deliveries are not replayed - 409;</li>
 *   <li>a non-empty body is JSON - 415 for other media types, 400 for malformed JSON.</li>
 * </ol>
 * The body size limit (413) is enforced by the controller while reading. Accepted deliveries start an execution and
 * return its id; the run itself is asynchronous.
 */
@Service
public class WebhookService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WebhookService.class);

    public static final String SIGNATURE_HEADER = "x-flowforge-signature";
    public static final String TIMESTAMP_HEADER = "x-flowforge-timestamp";
    public static final String TOKEN_HEADER = "x-flowforge-token";

    /** Never copied into the execution input (inputMode "request"). */
    private static final Set<String> HIDDEN_HEADERS = Set.of("authorization", "proxy-authorization", "cookie",
            SIGNATURE_HEADER, TOKEN_HEADER);
    private static final int MAX_HEADERS = 50;
    private static final int MAX_HEADER_VALUE = 1024;

    private final WebhookTriggerRepository webhooks;
    private final WorkflowExecutionService executions;
    private final ObjectMapper objectMapper;
    private final TriggerProperties properties;
    private final Clock clock;
    private final ReplayGuard replays;
    private final WebhookSecretStore secrets;

    public WebhookService(WebhookTriggerRepository webhooks, WorkflowExecutionService executions,
                          ObjectMapper objectMapper, TriggerProperties properties, Clock clock,
                          WebhookSecretStore secrets) {
        this.secrets = secrets;
        this.webhooks = webhooks;
        this.executions = executions;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
        this.replays = new ReplayGuard(properties.replayCacheSize());
    }

    /**
     * One HTTP delivery.
     *
     * @param headers header names in lower case
     * @param body    raw body bytes (already size-checked), empty when there is none
     */
    public record Delivery(String webhookId, String method, Map<String, List<String>> headers,
                           Map<String, List<String>> query, String contentType, byte[] body) {
    }

    public WorkflowExecutionService.Started receive(Delivery delivery) {
        WebhookTriggerEntity webhook = webhooks.findByWebhookId(delivery.webhookId())
                .filter(WebhookTriggerEntity::isActive)
                .orElseThrow(WebhookService::notFound);
        if (!webhook.getHttpMethod().equals(delivery.method())) {
            throw new WebhookRejectedException(405, "method-not-allowed", "Method not allowed",
                    "This webhook accepts " + webhook.getHttpMethod() + " requests.",
                    Map.of("Allow", webhook.getHttpMethod()));
        }
        Instant now = clock.instant();
        authenticate(webhook, delivery, now);
        JsonNode body = parseBody(delivery);
        JsonNode input = "request".equals(webhook.getInputMode()) ? envelope(webhook, delivery, body, now)
                : body != null ? body : objectMapper.createObjectNode();
        try {
            return executions.startTriggered(webhook.getWorkflowId(), TriggerType.WEBHOOK, input);
        } catch (ConflictException | ResourceNotFoundException e) {
            throw notFound(); // deactivated or deleted between the lookup and the start
        }
    }

    private void authenticate(WebhookTriggerEntity webhook, Delivery delivery, Instant now) {
        switch (webhook.getAuthentication()) {
            case "signature" -> verifySignature(webhook, delivery, now);
            case "token" -> {
                String token = bearer(header(delivery, "authorization"));
                if (token == null) {
                    token = header(delivery, TOKEN_HEADER);
                }
                if (!WebhookSignatures.matches(secret(webhook), token)) {
                    throw unauthorized("Missing or invalid webhook token.",
                            Map.of("WWW-Authenticate", "Bearer realm=\"flowforge-webhook\""));
                }
            }
            case "none" -> {
                if (!properties.allowUnauthenticatedWebhooks()) {
                    throw unauthorized("Unauthenticated webhooks are disabled on this server.", Map.of());
                }
            }
            default -> throw unauthorized("Unsupported webhook authentication.", Map.of());
        }
    }

    private void verifySignature(WebhookTriggerEntity webhook, Delivery delivery, Instant now) {
        String timestamp = header(delivery, TIMESTAMP_HEADER);
        String signature = header(delivery, SIGNATURE_HEADER);
        if (timestamp == null || signature == null) {
            throw unauthorized("Missing X-FlowForge-Timestamp or X-FlowForge-Signature header.", Map.of());
        }
        if (!timestamp.matches("[0-9]{1,12}")) {
            throw unauthorized("X-FlowForge-Timestamp must be Unix time in seconds.", Map.of());
        }
        Instant signedAt = Instant.ofEpochSecond(Long.parseLong(timestamp));
        long skew = Math.abs(now.getEpochSecond() - signedAt.getEpochSecond());
        if (skew > properties.signatureTolerance().toSeconds()) {
            throw unauthorized("X-FlowForge-Timestamp is outside the accepted window of "
                    + properties.signatureTolerance().toSeconds() + " seconds.", Map.of());
        }
        String expected = WebhookSignatures.sign(secret(webhook), timestamp, delivery.body());
        String actual = signature.strip().toLowerCase(Locale.ROOT);
        if (!WebhookSignatures.matches(expected, actual)) {
            throw unauthorized("Invalid webhook signature.", Map.of());
        }
        Instant expiresAt = signedAt.plus(properties.signatureTolerance()).plusSeconds(1);
        switch (replays.record(webhook.getWebhookId() + ":" + actual, expiresAt, now)) {
            case REPLAYED -> throw new WebhookRejectedException(409, "webhook-replayed", "Replayed delivery",
                    "This signed delivery was already received. Sign every delivery with a fresh timestamp.");
            case FULL -> throw new WebhookRejectedException(429, "too-many-requests", "Too many requests",
                    "Too many webhook deliveries in the signature window. Retry later.",
                    Map.of("Retry-After", String.valueOf(properties.signatureTolerance().toSeconds())));
            case ACCEPTED -> {
            }
        }
    }

    /** The secret to verify against; 503 (no details) when it cannot be decrypted on this server. */
    private String secret(WebhookTriggerEntity webhook) {
        try {
            return secrets.reveal(webhook);
        } catch (com.flowforge.application.credential.CredentialCryptoException e) {
            log.error("Webhook {} cannot be verified: {}", webhook.getWebhookId(), e.getMessage());
            throw new WebhookRejectedException(503, "webhook-unavailable", "Webhook unavailable",
                    "This webhook cannot verify requests at the moment.");
        }
    }

    private JsonNode parseBody(Delivery delivery) {
        if (delivery.body().length == 0) {
            return null;
        }
        if (!isJson(delivery.contentType())) {
            throw new WebhookRejectedException(415, "unsupported-media-type", "Unsupported media type",
                    "Webhook bodies must be JSON (Content-Type: application/json).",
                    Map.of("Accept", "application/json"));
        }
        try {
            return objectMapper.readTree(delivery.body());
        } catch (JsonProcessingException e) {
            throw new WebhookRejectedException(400, "invalid-request", "Invalid request",
                    "The request body is not valid JSON.");
        } catch (java.io.IOException e) {
            throw new WebhookRejectedException(400, "invalid-request", "Invalid request",
                    "The request body could not be read.");
        }
    }

    /** {@code inputMode: "request"}: the request as data, without credentials. */
    private JsonNode envelope(WebhookTriggerEntity webhook, Delivery delivery, JsonNode body, Instant receivedAt) {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("method", delivery.method());
        envelope.put("path", TriggerRegistrationService.WEBHOOK_PATH + webhook.getWebhookId());
        envelope.set("query", multiMap(delivery.query(), Integer.MAX_VALUE));
        Map<String, List<String>> visible = new TreeMap<>();
        delivery.headers().forEach((name, values) -> {
            if (!HIDDEN_HEADERS.contains(name)) {
                visible.put(name, values);
            }
        });
        envelope.set("headers", multiMap(visible, MAX_HEADERS));
        envelope.set("body", body);
        envelope.put("receivedAt", receivedAt.toString());
        return envelope;
    }

    /** Single values as strings, repeated ones as arrays. */
    private ObjectNode multiMap(Map<String, List<String>> values, int limit) {
        ObjectNode node = objectMapper.createObjectNode();
        values.entrySet().stream().limit(limit).forEach(entry -> {
            List<String> list = entry.getValue().stream()
                    .map(v -> v.length() > MAX_HEADER_VALUE ? v.substring(0, MAX_HEADER_VALUE) : v).toList();
            if (list.size() == 1) {
                node.put(entry.getKey(), list.getFirst());
            } else {
                list.forEach(node.putArray(entry.getKey())::add);
            }
        });
        return node;
    }

    private static boolean isJson(String contentType) {
        if (contentType == null) {
            return false;
        }
        String type = contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        return type.equals("application/json") || (type.startsWith("application/") && type.endsWith("+json"));
    }

    private static String header(Delivery delivery, String name) {
        List<String> values = delivery.headers().get(name);
        return values == null || values.isEmpty() ? null : values.getFirst();
    }

    private static String bearer(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        return authorization.substring(7).strip();
    }

    private static WebhookRejectedException notFound() {
        return new WebhookRejectedException(404, "not-found", "Resource not found",
                "No active webhook with this id.");
    }

    private static WebhookRejectedException unauthorized(String detail, Map<String, String> headers) {
        return new WebhookRejectedException(401, "unauthorized", "Unauthorized", detail, headers);
    }
}
