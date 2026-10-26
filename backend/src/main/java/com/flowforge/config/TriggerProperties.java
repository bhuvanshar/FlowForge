package com.flowforge.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Automatic trigger settings ({@code flowforge.triggers.*}).
 *
 * @param allowUnauthenticatedWebhooks webhook triggers may use {@code authentication: "none"} only when true
 *                                     (off by default: every webhook needs a token or a signature)
 * @param webhookMaxBodyBytes          larger webhook bodies are rejected with 413 before they are parsed
 * @param signatureTolerance           accepted clock difference for {@code X-FlowForge-Timestamp}
 * @param replayCacheSize              signed deliveries remembered (per process) to reject replays
 * @param schedulerEnabled             run the cron scheduler in this process
 * @param schedulerPollInterval        how often due schedules are checked (cron precision is this interval)
 * @param missedRunGrace               an occurrence older than this when it is handled counts as missed (downtime)
 * @param schedulerBatchSize           schedules handled per poll
 */
@ConfigurationProperties(prefix = "flowforge.triggers")
public record TriggerProperties(Boolean allowUnauthenticatedWebhooks, Integer webhookMaxBodyBytes,
                                Duration signatureTolerance, Integer replayCacheSize, Boolean schedulerEnabled,
                                Duration schedulerPollInterval, Duration missedRunGrace, Integer schedulerBatchSize) {

    public TriggerProperties {
        allowUnauthenticatedWebhooks = allowUnauthenticatedWebhooks != null && allowUnauthenticatedWebhooks;
        webhookMaxBodyBytes = webhookMaxBodyBytes == null ? 256 * 1024 : webhookMaxBodyBytes;
        signatureTolerance = signatureTolerance == null ? Duration.ofMinutes(5) : signatureTolerance;
        replayCacheSize = replayCacheSize == null ? 10_000 : replayCacheSize;
        schedulerEnabled = schedulerEnabled == null || schedulerEnabled;
        schedulerPollInterval = schedulerPollInterval == null ? Duration.ofSeconds(5) : schedulerPollInterval;
        missedRunGrace = missedRunGrace == null ? Duration.ofSeconds(60) : missedRunGrace;
        schedulerBatchSize = schedulerBatchSize == null ? 50 : schedulerBatchSize;
    }

    public static TriggerProperties defaults() {
        return new TriggerProperties(null, null, null, null, null, null, null, null);
    }
}
