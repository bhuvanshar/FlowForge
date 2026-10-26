package com.flowforge.application;

import com.flowforge.application.credential.SecretCipher;
import com.flowforge.config.AiProperties;
import com.flowforge.config.ExecutionProperties;
import com.flowforge.config.FlowForgeProperties;
import com.flowforge.config.SecurityProperties;
import com.flowforge.config.TriggerProperties;
import com.flowforge.infrastructure.http.HttpProperties;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * The server's effective configuration for the Settings page: read-only, no secrets (no keys, passwords or tokens; only
 * whether encryption is available and the name of the active key). Everything here is changed with environment variables
 * and a restart, not through the API.
 */
@Service
public class SettingsService {

    public record Application(String version) {
    }

    public record Execution(int workerThreads, int queueCapacity, long nodeTimeoutSeconds, long maxDurationSeconds,
                            int maxActivePerWorkflow, int maxStoredPayloadBytes) {
    }

    public record Outbound(List<String> allowedHosts, boolean allowPrivateAddresses, List<Integer> allowedPorts,
                           int maxResponseBytes, int maxRequestBytes, int maxRedirects) {
    }

    public record Triggers(boolean schedulerEnabled, long schedulerPollSeconds, boolean allowUnauthenticatedWebhooks,
                           int webhookMaxBodyBytes) {
    }

    public record Credentials(boolean encryptionAvailable, String activeKeyId, int keyCount) {
    }

    public record Ai(int maxPromptChars, int maxResponseBytes) {
    }

    public record Security(List<String> allowedHostNames, List<String> allowedOrigins, int maxRequestBytes) {
    }

    public record Settings(Application application, Execution execution, Outbound outbound, Triggers triggers,
                           Credentials credentials, Ai ai, Security security) {
    }

    private final FlowForgeProperties app;
    private final ExecutionProperties execution;
    private final HttpProperties http;
    private final TriggerProperties triggers;
    private final AiProperties ai;
    private final SecurityProperties security;
    private final SecretCipher cipher;

    public SettingsService(FlowForgeProperties app, ExecutionProperties execution, HttpProperties http,
                           TriggerProperties triggers, AiProperties ai, SecurityProperties security, SecretCipher cipher) {
        this.app = app;
        this.execution = execution;
        this.http = http;
        this.triggers = triggers;
        this.ai = ai;
        this.security = security;
        this.cipher = cipher;
    }

    public Settings current() {
        return new Settings(
                new Application(app.version()),
                new Execution(execution.workerThreads(), execution.queueCapacity(), execution.nodeTimeout().toSeconds(),
                        execution.maxDuration().toSeconds(), execution.maxActivePerWorkflow(),
                        execution.maxStoredPayloadBytes()),
                new Outbound(http.allowedHosts(), http.allowPrivateAddresses(), http.allowedPorts(),
                        http.maxResponseBytes(), http.maxRequestBytes(), http.maxRedirects()),
                new Triggers(triggers.schedulerEnabled(), triggers.schedulerPollInterval().toSeconds(),
                        triggers.allowUnauthenticatedWebhooks(), triggers.webhookMaxBodyBytes()),
                new Credentials(cipher.available(), cipher.activeKeyId(), cipher.keyIds().size()),
                new Ai(ai.maxPromptChars(), ai.maxResponseBytes()),
                new Security(security.allowedHostNames(), app.allowedOrigins(), security.maxRequestBytes()));
    }
}
