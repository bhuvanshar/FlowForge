package com.flowforge.api.dto;

import com.flowforge.application.trigger.TriggerRegistrationService.ScheduleView;
import com.flowforge.application.trigger.TriggerRegistrationService.TriggersView;
import com.flowforge.application.trigger.TriggerRegistrationService.WebhookView;
import com.flowforge.domain.workflow.WorkflowStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Runtime state of a workflow's trigger. {@code webhook} is present when the saved definition has a webhook trigger,
 * {@code schedule} when it has a schedule trigger. {@code active} is true while the trigger fires automatically.
 */
public record TriggersResponse(UUID workflowId, WorkflowStatus status, String triggerType, Webhook webhook,
                               Schedule schedule) {

    /** {@code path} is relative to the backend origin; the secret itself is only returned by the secret endpoints. */
    public record Webhook(String webhookId, String path, String nodeId, String method, String authentication,
                          String inputMode, boolean active, String secretHint) {

        static Webhook from(WebhookView v) {
            return v == null ? null : new Webhook(v.webhookId(), v.path(), v.nodeId(), v.method(), v.authentication(),
                    v.inputMode(), v.active(), v.secretHint());
        }
    }

    /** {@code nextFireAt} is null while inactive; {@code lastMissedAt}/{@code lastError} describe skipped occurrences. */
    public record Schedule(String nodeId, String cron, String timezone, String missedRunPolicy, boolean active,
                           Instant nextFireAt, Instant lastFireAt, UUID lastExecutionId, Instant lastMissedAt,
                           String lastError) {

        static Schedule from(ScheduleView v) {
            return v == null ? null : new Schedule(v.nodeId(), v.cron(), v.timezone(), v.missedRunPolicy(), v.active(),
                    v.nextFireAt(), v.lastFireAt(), v.lastExecutionId(), v.lastMissedAt(), v.lastError());
        }
    }

    public static TriggersResponse from(TriggersView v) {
        return new TriggersResponse(v.workflowId(), v.status(), v.triggerType(), Webhook.from(v.webhook()),
                Schedule.from(v.schedule()));
    }
}
