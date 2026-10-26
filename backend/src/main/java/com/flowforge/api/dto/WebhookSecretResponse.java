package com.flowforge.api.dto;

/** The shared secret used to sign (signature) or authenticate (token) webhook deliveries. */
public record WebhookSecretResponse(String secret) {
}
