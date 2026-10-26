package com.flowforge.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.application.execution.ExecutionStateManager;
import com.flowforge.application.execution.WorkflowExecutionService;
import com.flowforge.application.trigger.ScheduleRunner;
import com.flowforge.config.TriggerProperties;
import com.flowforge.domain.execution.ExecutionStatus;
import com.flowforge.support.PostgresIntegrationTest;
import com.flowforge.support.Workflows;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Scheduler semantics with explicit instants ({@link ScheduleRunner#tick}); background polling is off in the test
 * profile. Covers on-time runs, duplicate prevention (repeated and concurrent polls, unique index), missed-run
 * policies after downtime, and recovery after a restart.
 */
class ScheduleTriggerIT extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ScheduleRunner runner;

    @Autowired
    ExecutionStateManager states;

    @Autowired
    ScheduleTriggerRepository schedules;

    @Autowired
    WorkflowRepository workflows;

    @Autowired
    WorkflowExecutionRepository executions;

    @Autowired
    WorkflowExecutionService executionService;

    @Autowired
    PlatformTransactionManager transactionManager;

    @BeforeEach
    void clean() throws InterruptedException {
        long until = System.currentTimeMillis() + 5_000;
        while (states.activeCount() > 0 && System.currentTimeMillis() < until) {
            Thread.sleep(10);
        }
        jdbc.execute("TRUNCATE workflows CASCADE");
    }

    private UUID activeSchedule(String cron, String policy) throws Exception {
        String id = json.readTree(mvc.perform(post("/api/v1/workflows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Cron\",\"definition\":" + Workflows.schedule(cron, "UTC", policy) + "}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
        mvc.perform(post("/api/v1/workflows/" + id + "/activate")).andExpect(status().isOk());
        return UUID.fromString(id);
    }

    private Instant nextFireAt(UUID workflowId) {
        return jdbc.queryForObject("select next_fire_at from schedule_triggers where workflow_id = ?",
                Timestamp.class, workflowId).toInstant();
    }

    private void setNextFireAt(UUID workflowId, Instant next) {
        jdbc.update("update schedule_triggers set next_fire_at = ? where workflow_id = ?", Timestamp.from(next),
                workflowId);
    }

    private List<Instant> scheduledRuns(UUID workflowId) {
        return jdbc.queryForList("select scheduled_for from workflow_executions where workflow_id = ? "
                        + "and trigger_type = 'SCHEDULE' order by scheduled_for", Timestamp.class, workflowId)
                .stream().map(Timestamp::toInstant).toList();
    }

    private JsonNode awaitFinished(UUID executionId) throws Exception {
        long until = System.currentTimeMillis() + 10_000;
        while (true) {
            JsonNode execution = json.readTree(mvc.perform(get("/api/v1/executions/" + executionId))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            if (ExecutionStatus.valueOf(execution.get("status").asText()).isTerminal()) {
                return execution;
            }
            if (System.currentTimeMillis() > until) {
                throw new AssertionError("Timed out; last state " + execution);
            }
            Thread.sleep(25);
        }
    }

    // ----------------------------------------------------------------------------------------------------------------

    @Test
    void aDueOccurrenceRunsOnceAndTheScheduleAdvances() throws Exception {
        UUID id = activeSchedule("*/5 * * * *", "skip");
        Instant occurrence = nextFireAt(id);
        assertThat(occurrence.getEpochSecond() % 300).isZero();

        assertThat(runner.tick(occurrence.minusSeconds(1)).started()).isEmpty(); // not due yet

        ScheduleRunner.Tick tick = runner.tick(occurrence.plusSeconds(2));
        assertThat(tick.started()).hasSize(1);
        JsonNode done = awaitFinished(tick.started().getFirst());
        assertThat(done.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(done.get("triggerType").asText()).isEqualTo("SCHEDULE");
        assertThat(Instant.parse(done.get("scheduledFor").asText())).isEqualTo(occurrence);
        JsonNode input = done.get("nodes").get(0).get("output");
        assertThat(Instant.parse(input.get("scheduledFor").asText())).isEqualTo(occurrence);
        assertThat(input.get("timezone").asText()).isEqualTo("UTC");
        assertThat(input.get("late").asBoolean()).isFalse();

        assertThat(nextFireAt(id)).isEqualTo(occurrence.plus(5, ChronoUnit.MINUTES));
        JsonNode view = json.readTree(mvc.perform(get("/api/v1/workflows/" + id + "/triggers"))
                .andReturn().getResponse().getContentAsString()).get("schedule");
        assertThat(Instant.parse(view.get("lastFireAt").asText())).isEqualTo(occurrence);
        assertThat(view.get("lastExecutionId").asText()).isEqualTo(tick.started().getFirst().toString());

        // Polling again at the same moment finds nothing due: no duplicate.
        assertThat(runner.tick(occurrence.plusSeconds(3)).started()).isEmpty();
        assertThat(scheduledRuns(id)).containsExactly(occurrence);
    }

    @Test
    void concurrentPollsClaimEachOccurrenceOnce() throws Exception {
        UUID id = activeSchedule("0 * * * *", "skip");
        Instant due = nextFireAt(id).plusSeconds(1);
        int pollers = 6;
        CyclicBarrier start = new CyclicBarrier(pollers);
        ExecutorService pool = Executors.newFixedThreadPool(pollers);
        try {
            List<Future<ScheduleRunner.Tick>> results = new ArrayList<>();
            for (int i = 0; i < pollers; i++) {
                Callable<ScheduleRunner.Tick> poll = () -> {
                    start.await();
                    return runner.tick(due);
                };
                results.add(pool.submit(poll));
            }
            int started = 0;
            for (Future<ScheduleRunner.Tick> result : results) {
                started += result.get().started().size();
            }
            assertThat(started).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(scheduledRuns(id)).hasSize(1);
    }

    @Test
    void theDatabaseRejectsASecondRunForTheSameOccurrence() throws Exception {
        UUID id = activeSchedule("0 * * * *", "skip");
        Instant occurrence = nextFireAt(id);
        UUID first = runner.tick(occurrence).started().getFirst();
        awaitFinished(first);

        // Even if next_fire_at were rewound (e.g. a manual edit or a second scheduler with stale state) ...
        setNextFireAt(id, occurrence);
        ScheduleRunner.Tick again = runner.tick(occurrence.plusSeconds(1));
        assertThat(again.started()).isEmpty();
        assertThat(again.duplicates()).isEqualTo(1);
        assertThat(nextFireAt(id)).isAfter(occurrence);

        // ... and the unique index backs this up for any writer.
        assertThatThrownBy(() -> jdbc.update("insert into workflow_executions (id, workflow_id, workflow_version, "
                        + "workflow_snapshot, status, trigger_type, scheduled_for) "
                        + "select gen_random_uuid(), workflow_id, workflow_version, workflow_snapshot, 'QUEUED', "
                        + "'SCHEDULE', scheduled_for from workflow_executions where id = ?", first))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_workflow_executions_schedule_occurrence");
        assertThat(scheduledRuns(id)).containsExactly(occurrence);
    }

    @Test
    void afterDowntimeTheSkipPolicyRecordsTheMissedRunAndWaitsForTheNextOccurrence() throws Exception {
        UUID id = activeSchedule("*/10 * * * *", "skip");
        Instant now = Instant.parse("2030-06-01T12:34:00Z");
        setNextFireAt(id, Instant.parse("2030-06-01T11:00:00Z")); // last seen before a 1.5 h outage

        ScheduleRunner.Tick tick = runner.tick(now);

        assertThat(tick.started()).isEmpty();
        assertThat(tick.missed()).isEqualTo(1);
        assertThat(scheduledRuns(id)).isEmpty();
        assertThat(nextFireAt(id)).isEqualTo(Instant.parse("2030-06-01T12:40:00Z"));
        assertThat(jdbc.queryForObject("select last_missed_at from schedule_triggers where workflow_id = ?",
                Timestamp.class, id).toInstant()).isEqualTo(Instant.parse("2030-06-01T12:30:00Z"));
    }

    @Test
    void afterDowntimeTheRunOncePolicyRunsTheLatestMissedOccurrenceOnce() throws Exception {
        UUID id = activeSchedule("*/10 * * * *", "runOnce");
        Instant now = Instant.parse("2030-06-01T12:34:00Z");
        setNextFireAt(id, Instant.parse("2030-06-01T11:00:00Z"));

        ScheduleRunner.Tick tick = runner.tick(now);

        assertThat(tick.started()).hasSize(1);
        JsonNode done = awaitFinished(tick.started().getFirst());
        assertThat(done.get("nodes").get(0).get("output").get("late").asBoolean()).isTrue();
        assertThat(scheduledRuns(id)).containsExactly(Instant.parse("2030-06-01T12:30:00Z"));
        assertThat(nextFireAt(id)).isEqualTo(Instant.parse("2030-06-01T12:40:00Z"));
        assertThat(runner.tick(now.plusSeconds(5)).started()).isEmpty();
    }

    @Test
    void anOccurrenceWithinTheGracePeriodStillRunsOnTime() throws Exception {
        UUID id = activeSchedule("*/10 * * * *", "skip");
        Instant occurrence = Instant.parse("2030-06-01T12:30:00Z");
        setNextFireAt(id, occurrence);

        ScheduleRunner.Tick tick = runner.tick(occurrence.plusSeconds(45)); // e.g. a quick restart

        assertThat(tick.started()).hasSize(1);
        assertThat(scheduledRuns(id)).containsExactly(occurrence);
    }

    @Test
    void aRestartedSchedulerContinuesFromThePersistedState() throws Exception {
        UUID id = activeSchedule("*/10 * * * *", "skip");
        Instant occurrence = Instant.parse("2030-06-01T12:30:00Z");
        setNextFireAt(id, occurrence);
        // "Crash" after the occurrence was claimed and committed but before a worker picked it up: the run is
        // QUEUED and owned by nobody, and next_fire_at already points at the following occurrence.
        new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(s -> {
            var schedule = schedules.lockIfDue(id, occurrence).getFirst();
            var prepared = executionService.prepare(workflows.findById(id).orElseThrow(), null);
            executionService.enqueueScheduled(prepared, occurrence);
            schedule.advance(Instant.parse("2030-06-01T12:40:00Z"));
        });

        // Restart: recovery fails the orphan; a new runner instance (no in-memory state) takes over.
        states.recoverInterruptedExecutions();
        JsonNode orphan = json.readTree(mvc.perform(get("/api/v1/workflows/" + id + "/executions"))
                .andReturn().getResponse().getContentAsString()).get("items").get(0);
        assertThat(orphan.get("status").asText()).isEqualTo("FAILED");
        assertThat(orphan.get("error").get("code").asText()).isEqualTo("EXECUTION_INTERRUPTED");

        ScheduleRunner restarted = new ScheduleRunner(schedules, workflows, executions, executionService,
                transactionManager, json, TriggerProperties.defaults(), Clock.systemUTC());
        assertThat(restarted.tick(occurrence.plusSeconds(30)).started()).isEmpty(); // occurrence not retried
        ScheduleRunner.Tick next = restarted.tick(Instant.parse("2030-06-01T12:40:01Z"));
        assertThat(next.started()).hasSize(1);
        assertThat(scheduledRuns(id)).containsExactly(occurrence, Instant.parse("2030-06-01T12:40:00Z"));
    }

    @Test
    void deactivatedSchedulesDoNotFire() throws Exception {
        UUID id = activeSchedule("* * * * *", "runOnce");
        mvc.perform(post("/api/v1/workflows/" + id + "/deactivate")).andExpect(status().isOk());

        assertThat(runner.tick(Instant.now().plus(1, ChronoUnit.DAYS)).started()).isEmpty();
        assertThat(scheduledRuns(id)).isEmpty();
    }

    @Test
    void manualRunsOfScheduledWorkflowsAreRecordedAsManual() throws Exception {
        UUID id = activeSchedule("0 0 1 1 *", "skip");
        String body = mvc.perform(post("/api/v1/workflows/" + id + "/execute")).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        JsonNode done = awaitFinished(UUID.fromString(json.readTree(body).get("executionId").asText()));
        assertThat(done.get("triggerType").asText()).isEqualTo("MANUAL");
        assertThat(done.has("scheduledFor")).isFalse();
    }
}
