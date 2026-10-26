package com.flowforge.application.trigger;

import com.flowforge.application.InvalidRequestException;
import com.flowforge.application.ResourceNotFoundException;
import com.flowforge.application.TriggerSynchronizer;
import com.flowforge.domain.workflow.NodeDefinition;
import com.flowforge.domain.workflow.WorkflowStatus;
import com.flowforge.infrastructure.persistence.ScheduleTriggerEntity;
import com.flowforge.infrastructure.persistence.ScheduleTriggerRepository;
import com.flowforge.infrastructure.persistence.WebhookTriggerEntity;
import com.flowforge.infrastructure.persistence.WebhookTriggerRepository;
import com.flowforge.infrastructure.persistence.WorkflowEntity;
import com.flowforge.infrastructure.persistence.WorkflowRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Trigger registrations: the persisted, runtime form of a workflow's webhook or schedule trigger node.
 *
 * <p><b>Lifecycle.</b> {@link #synchronize} runs in the transaction of every workflow write:
 * <ul>
 *   <li>Webhook: a registration (public webhook id + secret, both server-generated) is created as soon as a saved
 *   definition contains a webhook trigger, so its URL can be configured at the sender before activation. It is
 *   {@code active} exactly while the workflow is ACTIVE and the trigger node exists. It is never deleted with the
 *   node or on deactivation, so the URL and secret survive deactivate/activate; it goes away with the workflow.</li>
 *   <li>Schedule: a registration exists once the workflow was activated with a schedule trigger. {@code nextFireAt} is
 *   computed from "now" on (re)activation and whenever cron or time zone change; otherwise it is left alone so a save
 *   never skips or repeats an occurrence. Deactivating stops it; runs missed while inactive are not caught up.</li>
 * </ul>
 */
@Service
public class TriggerRegistrationService implements TriggerSynchronizer {

    public static final String WEBHOOK_PATH = "/api/v1/hooks/";

    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> AUTHENTICATION = Set.of("signature", "token", "none");
    private static final Set<String> INPUT_MODES = Set.of("body", "request");
    private static final Set<String> POLICIES = Set.of("skip", "runOnce");

    private final WebhookTriggerRepository webhooks;
    private final ScheduleTriggerRepository schedules;
    private final WorkflowRepository workflows;
    private final Clock clock;
    private final WebhookSecretStore secrets;
    private final SecureRandom random = new SecureRandom();

    public TriggerRegistrationService(WebhookTriggerRepository webhooks, ScheduleTriggerRepository schedules,
                                      WorkflowRepository workflows, Clock clock, WebhookSecretStore secrets) {
        this.secrets = secrets;
        this.webhooks = webhooks;
        this.schedules = schedules;
        this.workflows = workflows;
        this.clock = clock;
    }

    public record WebhookView(String webhookId, String path, String nodeId, String method, String authentication,
                              String inputMode, boolean active, String secretHint) {
    }

    public record ScheduleView(String nodeId, String cron, String timezone, String missedRunPolicy, boolean active,
                               Instant nextFireAt, Instant lastFireAt, UUID lastExecutionId, Instant lastMissedAt,
                               String lastError) {
    }

    /** {@code triggerType} is the type of the definition's trigger node (null when it has none). */
    public record TriggersView(UUID workflowId, WorkflowStatus status, String triggerType, WebhookView webhook,
                               ScheduleView schedule) {
    }

    @Override
    @Transactional
    public void synchronize(WorkflowEntity workflow) {
        boolean active = workflow.getStatus() == WorkflowStatus.ACTIVE;
        syncWebhook(workflow.getId(), TriggerNodes.find(workflow.getDefinition(), TriggerNodes.WEBHOOK), active);
        syncSchedule(workflow.getId(), TriggerNodes.find(workflow.getDefinition(), TriggerNodes.SCHEDULE), active);
    }

    @Transactional(readOnly = true)
    public TriggersView view(UUID workflowId) {
        WorkflowEntity workflow = workflow(workflowId);
        Optional<NodeDefinition> webhookNode = TriggerNodes.find(workflow.getDefinition(), TriggerNodes.WEBHOOK);
        Optional<NodeDefinition> scheduleNode = TriggerNodes.find(workflow.getDefinition(), TriggerNodes.SCHEDULE);
        String triggerType = webhookNode.or(() -> scheduleNode)
                .or(() -> TriggerNodes.find(workflow.getDefinition(), TriggerNodes.MANUAL))
                .map(NodeDefinition::type).orElse(null);

        WebhookView webhook = webhookNode.flatMap(n -> webhooks.findById(workflowId)).map(w -> new WebhookView(
                w.getWebhookId(), WEBHOOK_PATH + w.getWebhookId(), w.getNodeId(), w.getHttpMethod(),
                w.getAuthentication(), w.getInputMode(), w.isActive(), hint(w))).orElse(null);

        ScheduleView schedule = scheduleNode.map(node -> {
            Map<String, Object> config = node.configuration();
            Optional<ScheduleTriggerEntity> row = schedules.findById(workflowId);
            boolean running = row.map(ScheduleTriggerEntity::isActive).orElse(false);
            return new ScheduleView(node.id(), string(config, "cron", null), string(config, "timezone", null),
                    string(config, "missedRunPolicy", null), running,
                    running ? row.get().getNextFireAt() : null,
                    row.map(ScheduleTriggerEntity::getLastFireAt).orElse(null),
                    row.map(ScheduleTriggerEntity::getLastExecutionId).orElse(null),
                    row.map(ScheduleTriggerEntity::getLastMissedAt).orElse(null),
                    row.map(ScheduleTriggerEntity::getLastError).orElse(null));
        }).orElse(null);
        return new TriggersView(workflowId, workflow.getStatus(), triggerType, webhook, schedule);
    }

    /** The webhook secret, for configuring the sender (decrypted on demand; never stored in plaintext with a keyring). */
    @Transactional(readOnly = true)
    public String webhookSecret(UUID workflowId) {
        return secrets.reveal(webhook(workflowId));
    }

    /** Replaces the webhook secret; deliveries signed with the old one are rejected from now on. */
    @Transactional
    public String rotateWebhookSecret(UUID workflowId) {
        WebhookTriggerEntity webhook = webhook(workflowId);
        String secret = randomToken(32);
        secrets.store(webhook, secret);
        return secret;
    }

    private void syncWebhook(UUID workflowId, Optional<NodeDefinition> node, boolean active) {
        Optional<WebhookTriggerEntity> existing = webhooks.findById(workflowId);
        if (node.isEmpty()) {
            existing.ifPresent(w -> w.setActive(false));
            return;
        }
        WebhookTriggerEntity webhook = existing.orElseGet(() -> {
            WebhookTriggerEntity created = new WebhookTriggerEntity(workflowId, randomToken(24));
            secrets.store(created, randomToken(32));
            return created;
        });
        Map<String, Object> config = node.get().configuration();
        // Inactive drafts may hold invalid values; ACTIVE workflows are validated, so these fallbacks never apply then.
        webhook.configure(node.get().id(), pick(config, "method", METHODS, "POST"),
                pick(config, "authentication", AUTHENTICATION, "signature"),
                pick(config, "inputMode", INPUT_MODES, "body"));
        webhook.setActive(active);
        if (existing.isEmpty()) {
            webhooks.save(webhook); // persist after configuring: the insert captures the state at this point
        }
    }

    private void syncSchedule(UUID workflowId, Optional<NodeDefinition> node, boolean active) {
        Optional<ScheduleTriggerEntity> existing = schedules.findById(workflowId);
        if (node.isEmpty() || !active) {
            existing.ifPresent(s -> s.setActive(false));
            return;
        }
        Map<String, Object> config = node.get().configuration();
        String cron = string(config, "cron", "");
        String timezone = string(config, "timezone", "");
        CronSchedule schedule;
        try {
            schedule = CronSchedule.parse(cron, timezone);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("Schedule trigger: cron or time zone " + e.getMessage());
        }
        boolean keepNext = existing.map(s -> s.isActive() && s.getCron().equals(cron)
                && s.getTimezone().equals(timezone)).orElse(false);
        Instant next = keepNext ? existing.get().getNextFireAt() : schedule.next(clock.instant());
        if (next == null) {
            throw new InvalidRequestException("Schedule trigger: the cron expression never fires.");
        }
        ScheduleTriggerEntity row = existing.orElseGet(() -> new ScheduleTriggerEntity(workflowId));
        row.configure(node.get().id(), cron, timezone, pick(config, "missedRunPolicy", POLICIES, "skip"), next);
        row.setActive(true);
        if (existing.isEmpty()) {
            schedules.save(row);
        }
    }

    private WorkflowEntity workflow(UUID id) {
        return workflows.findById(id).orElseThrow(() -> new ResourceNotFoundException("Workflow " + id + " not found."));
    }

    private WebhookTriggerEntity webhook(UUID workflowId) {
        WorkflowEntity workflow = workflow(workflowId);
        return TriggerNodes.find(workflow.getDefinition(), TriggerNodes.WEBHOOK)
                .flatMap(n -> webhooks.findById(workflowId))
                .orElseThrow(() -> new ResourceNotFoundException("Workflow " + workflowId
                        + " has no webhook trigger. Add one and save the workflow first."));
    }

    private String randomToken(int bytes) {
        byte[] buffer = new byte[bytes];
        random.nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }

    /** Last four characters, or "…????" when the secret cannot be decrypted on this server. */
    private String hint(WebhookTriggerEntity webhook) {
        try {
            String secret = secrets.reveal(webhook);
            return "…" + secret.substring(secret.length() - 4);
        } catch (com.flowforge.application.credential.CredentialCryptoException e) {
            return "…????";
        }
    }

    private static String pick(Map<String, Object> config, String key, Set<String> allowed, String fallback) {
        String value = string(config, key, fallback);
        return allowed.contains(value) ? value : fallback;
    }

    private static String string(Map<String, Object> config, String key, String fallback) {
        return config.get(key) instanceof String s ? s : fallback;
    }
}
