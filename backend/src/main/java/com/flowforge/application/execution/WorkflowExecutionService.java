package com.flowforge.application.execution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.application.ConflictException;
import com.flowforge.application.InvalidRequestException;
import com.flowforge.application.PayloadTooLargeException;
import com.flowforge.application.WorkflowService;
import com.flowforge.application.WorkflowValidationException;
import com.flowforge.domain.execution.ErrorDetails;
import com.flowforge.domain.execution.ExecutionPlan;
import com.flowforge.domain.execution.ExecutionPlanner;
import com.flowforge.domain.execution.ExecutionStatus;
import com.flowforge.domain.execution.TriggerType;
import com.flowforge.domain.validation.GraphValidator;
import com.flowforge.domain.validation.ValidationResult;
import com.flowforge.domain.workflow.WorkflowStatus;
import com.flowforge.infrastructure.persistence.WorkflowEntity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;

/**
 * Starts, inspects and cancels workflow executions.
 *
 * <p>Starting is synchronous up to "QUEUED": load the saved workflow, validate it (Module 6), plan it, check that
 * every node type has an executor, snapshot the definition and create the execution rows. The run itself happens on
 * an execution worker thread; the caller gets the execution id immediately and polls for the result.
 *
 * <p>Three entry points share these steps: {@link #start} (manual, any non-archived workflow), {@link #startTriggered}
 * (webhooks; the workflow must be ACTIVE) and {@link #enqueueScheduled} + {@link #dispatch} (the scheduler creates
 * the QUEUED run inside its own transaction and hands it to a worker only after that transaction committed).
 */
@Service
public class WorkflowExecutionService {

    /** Same limit as workflow definitions. */
    public static final int MAX_INPUT_BYTES = 512 * 1024;

    private final WorkflowService workflows;
    private final GraphValidator validator;
    private final ExecutionPlanner planner;
    private final NodeExecutorRegistry executors;
    private final ExecutionPersistenceService persistence;
    private final ExecutionStateManager states;
    private final ExecutionEngine engine;
    private final TaskExecutor workers;
    private final ObjectMapper objectMapper;
    private final int maxActivePerWorkflow;

    public WorkflowExecutionService(WorkflowService workflows, GraphValidator validator, ExecutionPlanner planner,
                                    NodeExecutorRegistry executors, ExecutionPersistenceService persistence,
                                    ExecutionStateManager states, ExecutionEngine engine, TaskExecutor executionWorkers,
                                    ObjectMapper objectMapper, com.flowforge.config.ExecutionProperties properties) {
        this.maxActivePerWorkflow = properties.maxActivePerWorkflow();
        this.workflows = workflows;
        this.validator = validator;
        this.planner = planner;
        this.executors = executors;
        this.persistence = persistence;
        this.states = states;
        this.engine = engine;
        this.workers = executionWorkers;
        this.objectMapper = objectMapper;
    }

    public record Started(UUID executionId, UUID workflowId, ExecutionStatus status) {
    }

    /** A workflow checked and planned for execution, with its effective trigger input. */
    public record Prepared(WorkflowEntity workflow, ExecutionPlan plan, JsonNode input) {
    }

    /** A QUEUED execution that has not been handed to a worker yet. */
    public record Pending(Prepared prepared, ExecutionPersistenceService.Created created) {
    }

    /**
     * Manual run (editor "Run" or {@code POST /workflows/{id}/execute}).
     *
     * @param input trigger input; when null, the trigger's configured test input (or {@code {}}) is used
     */
    public Started start(UUID workflowId, JsonNode input) {
        Prepared prepared = prepare(workflows.get(workflowId), input);
        return dispatch(new Pending(prepared, persistence.createQueued(prepared.workflow(), prepared.plan())));
    }

    /**
     * Run started by an automatic trigger outside a transaction (webhooks). Only ACTIVE workflows run automatically;
     * the check happens here, after the trigger lookup, so a deactivation racing with a delivery is still honoured.
     */
    public Started startTriggered(UUID workflowId, TriggerType triggerType, JsonNode input) {
        WorkflowEntity workflow = workflows.get(workflowId);
        if (workflow.getStatus() != WorkflowStatus.ACTIVE) {
            throw new ConflictException("Workflow " + workflowId + " is not active.");
        }
        Prepared prepared = prepare(workflow, input);
        return dispatch(new Pending(prepared,
                persistence.createQueued(workflow, prepared.plan(), triggerType, null)));
    }

    /**
     * Creates the QUEUED run of one schedule occurrence in the caller's transaction. Call {@link #dispatch} after
     * that transaction committed; if it rolls back, the run never existed.
     */
    public Pending enqueueScheduled(Prepared prepared, Instant occurrence) {
        return new Pending(prepared,
                persistence.createQueued(prepared.workflow(), prepared.plan(), TriggerType.SCHEDULE, occurrence));
    }

    /** Checks that the workflow can run now and resolves its input. Writes nothing. */
    public Prepared prepare(WorkflowEntity workflow, JsonNode input) {
        if (workflow.getStatus() == WorkflowStatus.ARCHIVED) {
            throw new ConflictException("Workflow " + workflow.getId() + " is archived and cannot be executed.");
        }
        ValidationResult validation = validator.validate(workflow.getDefinition());
        if (!validation.valid()) {
            throw new WorkflowValidationException("The workflow cannot be executed: it has "
                    + validation.errorCount() + " validation error(s).", validation);
        }
        ExecutionPlan plan = planner.plan(workflow.getDefinition());
        List<String> missing = executors.missing(plan.steps().stream().map(s -> s.node().type()).toList());
        if (!missing.isEmpty()) {
            throw new ExecutionNotSupportedException(missing);
        }
        JsonNode triggerInput = input != null ? input : defaultInput(plan);
        checkSize(triggerInput);
        if (persistence.activeCount(workflow.getId()) >= maxActivePerWorkflow) {
            throw new ExecutionCapacityException("Workflow " + workflow.getId() + " already has " + maxActivePerWorkflow
                    + " queued or running executions. Try again later.");
        }
        return new Prepared(workflow, plan, triggerInput);
    }

    /**
     * Hands a committed QUEUED execution to a worker. When the queue is full the execution is recorded as FAILED
     * ({@code EXECUTION_REJECTED}) and {@link ExecutionCapacityException} is thrown.
     */
    public Started dispatch(Pending pending) {
        Prepared prepared = pending.prepared();
        ExecutionPersistenceService.Created created = pending.created();
        UUID workflowId = prepared.workflow().getId();
        CancellationToken token = states.register(created.executionId());
        ExecutionJob job = new ExecutionJob(created.executionId(), workflowId, prepared.workflow().getVersion(),
                prepared.plan(), created.nodeExecutionIds(), prepared.input(), token, prepared.workflow().getOwnerId());
        try {
            workers.execute(() -> {
                try {
                    engine.run(job);
                } finally {
                    states.unregister(job.executionId());
                }
            });
        } catch (TaskRejectedException e) {
            states.unregister(created.executionId());
            persistence.abandon(created.executionId(), ExecutionStatus.FAILED,
                    ErrorDetails.sanitized("EXECUTION_REJECTED", "Too many executions are queued."));
            throw new ExecutionCapacityException("Too many executions are queued. Try again later.");
        }
        return new Started(created.executionId(), workflowId, ExecutionStatus.QUEUED);
    }

    public ExecutionPersistenceService.ExecutionView get(UUID executionId) {
        return persistence.find(executionId);
    }

    /**
     * Requests cancellation. Running and queued executions stop asynchronously (the engine records CANCELLED);
     * the returned view may still show the previous status.
     */
    public ExecutionPersistenceService.ExecutionView cancel(UUID executionId) {
        ExecutionStatus status = persistence.find(executionId).execution().getStatus();
        if (status.isTerminal()) {
            throw new ConflictException("Execution " + executionId + " has already finished (" + status + ").");
        }
        states.cancel(executionId);
        return persistence.find(executionId);
    }

    private JsonNode defaultInput(ExecutionPlan plan) {
        Object testInput = plan.trigger().node().configuration().get("testInput");
        return testInput != null ? objectMapper.valueToTree(testInput) : objectMapper.createObjectNode();
    }

    private void checkSize(JsonNode input) {
        try {
            if (objectMapper.writeValueAsBytes(input).length > MAX_INPUT_BYTES) {
                throw new PayloadTooLargeException("Execution input exceeds " + MAX_INPUT_BYTES + " bytes.");
            }
        } catch (JsonProcessingException e) {
            throw new InvalidRequestException("Execution input is not valid JSON.");
        }
    }
}
