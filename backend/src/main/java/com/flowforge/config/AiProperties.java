package com.flowforge.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI Model node limits ({@code flowforge.ai.*}). Outbound access itself is governed by {@code flowforge.http.*}
 * (allowlist, non-public addresses), the same as for the HTTP Request node.
 *
 * @param maxPromptChars   resolved system + user prompt above this fails the node before anything is sent
 * @param maxResponseBytes larger provider responses fail with AI_RESPONSE_TOO_LARGE
 * @param retryBaseDelay   first wait before a retry; doubles each time (plus up to 25 % jitter)
 * @param retryMaxDelay    upper bound of the computed wait
 * @param maxRetryAfter    a provider's {@code Retry-After} is honoured up to this long (longer: no retry)
 */
@ConfigurationProperties(prefix = "flowforge.ai")
public record AiProperties(Integer maxPromptChars, Integer maxResponseBytes, Duration retryBaseDelay,
                           Duration retryMaxDelay, Duration maxRetryAfter) {

    public AiProperties {
        maxPromptChars = maxPromptChars == null ? 100_000 : maxPromptChars;
        maxResponseBytes = maxResponseBytes == null ? 1024 * 1024 : maxResponseBytes;
        retryBaseDelay = retryBaseDelay == null ? Duration.ofMillis(500) : retryBaseDelay;
        retryMaxDelay = retryMaxDelay == null ? Duration.ofSeconds(8) : retryMaxDelay;
        maxRetryAfter = maxRetryAfter == null ? Duration.ofSeconds(10) : maxRetryAfter;
    }

    public static AiProperties defaults() {
        return new AiProperties(null, null, null, null, null);
    }
}
