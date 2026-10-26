package com.flowforge.application.trigger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowforge.application.execution.ExecutionCapacityException;
import com.flowforge.application.execution.WorkflowExecutionService;
import com.flowforge.config.TriggerProperties;
import com.flowforge.domain.workflow.WorkflowStatus;
import com.flowforge.infrastructure.persistence.ScheduleTriggerEntity;
import com.flowforge.infrastructure.persistence.ScheduleTriggerRepository;
import com.flowforge.infrastructure.persistence.WorkflowEntity;
import com.flowforge.infrastructure.persistence.WorkflowExecutionRepository;
import com.flowforge.infrastructure.persistence.WorkflowRepository;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Polls the {@code schedule_triggers} table and starts due scheduled runs.
 *
 * <p><b>One occurrence, one run.</b> Each due schedule is handled in its own short transaction that locks the row
 * ({@code FOR UPDATE SKIP LOCKED}), creates the QUEUED execution with {@code scheduled_for = occurrence} and advances
 * {@code next_fire_at}; all of it commits or none of it does. The partial unique index on
 * {@code (workflow_id, scheduled_for)} is the last line of defence: even a second scheduler process on the same
 * database cannot insert a second run for the same occurrence. The execution is handed to a worker only after the
 * commit; if the process dies in between, startup recovery marks it {@code EXECUTION_INTERRUPTED} and the occurrence
 * is not retried.
 *
 * <p><b>Timing.</b> Occurrences are checked every {@code schedulerPollInterval}; several occurrences that fall between
 * two polls coalesce into one run (the latest). An occurrence handled more than {@code missedRunGrace} after its time
 * counts as missed (the server was down or blocked) and the schedule's {@code missedRunPolicy} applies: {@code skip}
 * records it and waits for the next future occurrence, {@code runOnce} runs once for the latest missed occurrence.
 *
 * <p><b>Restart recovery</b> needs no extra state: {@code next_fire_at} is persisted, so the first poll after a restart
 * sees every overdue schedule and applies the same rules.
 */
@Component
public class ScheduleRunner {

    private static final Logger log = LoggerFactory.getLogger(ScheduleRunner.class);

    private final ScheduleTriggerRepository schedules;
    private final WorkflowRepository workflows;
    private final WorkflowExecutionRepository executions;
    private final WorkflowExecutionService executionService;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;
    private final TriggerProperties properties;
    private final Clock clock;
    private ScheduledExecutorService timer;

    public ScheduleRunner(ScheduleTriggerRepository schedules, WorkflowRepository workflows,
                          WorkflowExecutionRepository executions, WorkflowExecutionService executionService,
                          PlatformTransactionManager transactionManager, ObjectMapper objectMapper,
                          TriggerProperties properties, Clock clock) {
        this.schedules = schedules;
        this.workflows = workflows;
        this.executions = executions;
        this.executionService = executionService;
        this.transactions = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
    }

    public enum Outcome { STARTED, MISSED, DUPLICATE, FAILED, NOT_DUE }

    /** What one poll did, by outcome; {@code started} lists the created execution ids. */
    public record Tick(List<UUID> started, int missed, int duplicates, int failed) {
    }

    private record Claim(Outcome outcome, WorkflowExecutionService.Pending pending) {
    }

    /** Starts polling after startup recovery (order 0 in {@code ExecutionStateManager}) finished. */
    @EventListener(ApplicationReadyEvent.class)
    @Order(100)
    public synchronized void start() {
        if (!properties.schedulerEnabled() || timer != null) {
            log.info("Schedule trigger polling is disabled");
            return;
        }
        timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ff-scheduler");
            thread.setDaemon(true);
            return thread;
        });
        long interval = properties.schedulerPollInterval().toMillis();
        timer.scheduleWithFixedDelay(this::pollSafely, 0, interval, TimeUnit.MILLISECONDS);
        log.info("Schedule trigger polling every {} ms", interval);
    }

    @PreDestroy
    public synchronized void stop() {
        if (timer != null) {
            timer.shutdownNow();
            timer = null;
        }
    }

    private void pollSafely() {
        try {
            tick(clock.instant());
        } catch (RuntimeException e) {
            log.error("Schedule poll failed", e);
        }
    }

    /** One poll at {@code now}. Safe to call concurrently (also from tests): each occurrence is claimed once. */
    public Tick tick(Instant now) {
        List<UUID> due = schedules.findDue(now, PageRequest.of(0, properties.schedulerBatchSize()));
        List<UUID> started = new java.util.ArrayList<>();
        int missed = 0;
        int duplicates = 0;
        int failed = 0;
        for (UUID workflowId : due) {
            Claim claim;
            try {
                claim = transactions.execute(status -> claim(workflowId, now));
            } catch (ExecutionCapacityException e) {
                // Admission can fill after prepare(). Roll back this occurrence and keep processing other schedules.
                log.warn("Scheduled run of workflow {} deferred: active execution limit reached", workflowId);
                failed++;
                continue;
            } catch (DataIntegrityViolationException e) {
                log.warn("Schedule of workflow {}: occurrence already has an execution; duplicate prevented",
                        workflowId);
                duplicates++;
                continue;
            }
            switch (claim.outcome()) {
                case STARTED -> {
                    try {
                        started.add(executionService.dispatch(claim.pending()).executionId());
                    } catch (ExecutionCapacityException e) {
                        log.warn("Scheduled run of workflow {} rejected: execution queue is full", workflowId);
                        failed++;
                    }
                }
                case MISSED -> missed++;
                case DUPLICATE -> duplicates++;
                case FAILED -> failed++;
                case NOT_DUE -> {
                }
            }
        }
        return new Tick(List.copyOf(started), missed, duplicates, failed);
    }

    private Claim claim(UUID workflowId, Instant now) {
        List<ScheduleTriggerEntity> locked = schedules.lockIfDue(workflowId, now);
        if (locked.isEmpty()) {
            return new Claim(Outcome.NOT_DUE, null); // claimed by a concurrent poll, or changed meanwhile
        }
        ScheduleTriggerEntity schedule = locked.getFirst();
        WorkflowEntity workflow = workflows.findById(workflowId).orElse(null);
        if (workflow == null || workflow.getStatus() != WorkflowStatus.ACTIVE) {
            schedule.setActive(false); // registrations follow the workflow status; repair a stale row
            return new Claim(Outcome.NOT_DUE, null);
        }
        CronSchedule cron;
        try {
            cron = CronSchedule.parse(schedule.getCron(), schedule.getTimezone());
        } catch (IllegalArgumentException e) {
            schedule.failed(schedule.getNextFireAt(), "Invalid schedule: " + e.getMessage());
            schedule.setActive(false);
            return new Claim(Outcome.FAILED, null);
        }

        Instant first = schedule.getNextFireAt();
        Instant latest = cron.latestUpTo(first, now);
        boolean late = first.isBefore(now.minus(properties.missedRunGrace()));
        Instant next = cron.next(now);
        if (next == null) {
            schedule.setActive(false); // the expression has no future occurrence
        } else {
            schedule.advance(next);
        }
        if (late && "skip".equals(schedule.getMissedRunPolicy())) {
            schedule.missed(latest);
            log.info("Schedule of workflow {}: skipped missed occurrence(s) up to {}", workflowId, latest);
            return new Claim(Outcome.MISSED, null);
        }
        if (executions.existsByWorkflowIdAndScheduledFor(workflowId, latest)) {
            return new Claim(Outcome.DUPLICATE, null);
        }

        WorkflowExecutionService.Prepared prepared;
        try {
            prepared = executionService.prepare(workflow, input(schedule, latest, now, late));
        } catch (RuntimeException e) {
            schedule.failed(latest, e.getMessage());
            log.warn("Schedule of workflow {}: occurrence {} not started: {}", workflowId, latest, e.getMessage());
            return new Claim(Outcome.FAILED, null);
        }
        WorkflowExecutionService.Pending pending = executionService.enqueueScheduled(prepared, latest);
        schedule.fired(latest, pending.created().executionId());
        return new Claim(Outcome.STARTED, pending);
    }

    private ObjectNode input(ScheduleTriggerEntity schedule, Instant occurrence, Instant now, boolean late) {
        ObjectNode input = objectMapper.createObjectNode();
        input.put("scheduledFor", occurrence.toString());
        input.put("firedAt", now.toString());
        input.put("timezone", schedule.getTimezone());
        input.put("cron", schedule.getCron());
        input.put("late", late);
        return input;
    }
}
