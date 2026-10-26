package com.flowforge.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.api.error.GlobalExceptionHandler;
import com.flowforge.application.ConflictException;
import com.flowforge.application.execution.ExecutionCapacityException;
import com.flowforge.application.execution.ExecutionNotSupportedException;
import com.flowforge.application.execution.ExecutionPersistenceService.ExecutionView;
import com.flowforge.application.execution.ExecutionQueryService;
import com.flowforge.application.execution.WorkflowExecutionService;
import com.flowforge.config.AppConfig;
import com.flowforge.domain.execution.ErrorDetails;
import com.flowforge.domain.execution.ExecutionStatus;
import com.flowforge.domain.workflow.WorkflowDefinition;
import com.flowforge.infrastructure.persistence.NodeExecutionEntity;
import com.flowforge.infrastructure.persistence.WorkflowEntity;
import com.flowforge.infrastructure.persistence.WorkflowExecutionEntity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ExecutionController.class)
@Import({AppConfig.class, GlobalExceptionHandler.class})
class ExecutionControllerTest {

    private static final UUID WORKFLOW = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID EXECUTION = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired
    MockMvc mvc;

    @MockitoBean
    WorkflowExecutionService service;

    @MockitoBean
    ExecutionQueryService queries;

    @MockitoBean
    ExecutionEventStream eventStream;

    private static ExecutionView failedView() {
        WorkflowEntity workflow = new WorkflowEntity(WORKFLOW, "W", null, WorkflowDefinition.empty());
        WorkflowExecutionEntity execution = WorkflowExecutionEntity.queued(EXECUTION, workflow);
        execution.markRunning(Instant.parse("2026-01-01T00:00:00Z"));
        execution.markFailed(Instant.parse("2026-01-01T00:00:01Z"), ErrorDetails.sanitized("NODE_ERROR", "Node 'x' failed: boom"));
        NodeExecutionEntity node = new NodeExecutionEntity(UUID.randomUUID(), EXECUTION, "x", "core.logger", 0);
        node.markRunning(Instant.parse("2026-01-01T00:00:00Z"), new ObjectMapper().createObjectNode().put("a", 1));
        node.markFailed(Instant.parse("2026-01-01T00:00:01Z"), ErrorDetails.sanitized("NODE_ERROR", "boom"));
        return new ExecutionView(execution, List.of(node), List.of());
    }

    @Test
    void executeReturns202WithExecutionIdAndLocation() throws Exception {
        given(service.start(eq(WORKFLOW), any())).willReturn(
                new WorkflowExecutionService.Started(EXECUTION, WORKFLOW, ExecutionStatus.QUEUED));

        mvc.perform(post("/api/v1/workflows/" + WORKFLOW + "/execute").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":{\"a\":1}}"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/v1/executions/" + EXECUTION))
                .andExpect(jsonPath("$.executionId").value(EXECUTION.toString()))
                .andExpect(jsonPath("$.status").value("QUEUED"));
    }

    @Test
    void executeWithoutBodyPassesNoInput() throws Exception {
        given(service.start(eq(WORKFLOW), isNull())).willReturn(
                new WorkflowExecutionService.Started(EXECUTION, WORKFLOW, ExecutionStatus.QUEUED));

        mvc.perform(post("/api/v1/workflows/" + WORKFLOW + "/execute")).andExpect(status().isAccepted());
    }

    @Test
    void unsupportedNodeTypesAre501AndAFullQueueIs503() throws Exception {
        given(service.start(eq(WORKFLOW), any())).willThrow(new ExecutionNotSupportedException(List.of("core.logger")));
        mvc.perform(post("/api/v1/workflows/" + WORKFLOW + "/execute"))
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.type").value("https://flowforge.local/problems/node-type-not-executable"))
                .andExpect(jsonPath("$.nodeTypes[0]").value("core.logger"));

        given(service.start(eq(WORKFLOW), any())).willThrow(new ExecutionCapacityException("full"));
        mvc.perform(post("/api/v1/workflows/" + WORKFLOW + "/execute"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("https://flowforge.local/problems/execution-capacity"));
    }

    @Test
    void getReturnsExecutionWithNodeRunsAndErrors() throws Exception {
        given(service.get(EXECUTION)).willReturn(failedView());

        mvc.perform(get("/api/v1/executions/" + EXECUTION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.error.code").value("NODE_ERROR"))
                .andExpect(jsonPath("$.nodes[0].nodeId").value("x"))
                .andExpect(jsonPath("$.nodes[0].status").value("FAILED"))
                .andExpect(jsonPath("$.nodes[0].input.a").value(1))
                .andExpect(jsonPath("$.nodes[0].output").doesNotExist())
                .andExpect(jsonPath("$.nodes[0].error.message").value("boom"));
    }

    @Test
    void cancelIs202OrConflictWhenFinished() throws Exception {
        given(service.cancel(EXECUTION)).willReturn(failedView());
        mvc.perform(post("/api/v1/executions/" + EXECUTION + "/cancel")).andExpect(status().isAccepted());

        given(service.cancel(EXECUTION)).willThrow(new ConflictException("finished"));
        mvc.perform(post("/api/v1/executions/" + EXECUTION + "/cancel")).andExpect(status().isConflict());
    }

    @Test
    void listPassesFiltersAndMapsSummaries() throws Exception {
        ExecutionView view = failedView();
        var summary = new ExecutionQueryService.Summary(view.execution(), 3, 1, 1, 1, "x");
        given(queries.list(eq(WORKFLOW), eq(List.of(ExecutionStatus.FAILED)), eq(1), eq(5)))
                .willReturn(new org.springframework.data.domain.PageImpl<>(List.of(summary),
                        org.springframework.data.domain.PageRequest.of(1, 5), 6));

        mvc.perform(get("/api/v1/workflows/" + WORKFLOW + "/executions").param("status", "FAILED")
                        .param("page", "1").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(6))
                .andExpect(jsonPath("$.items[0].id").value(EXECUTION.toString()))
                .andExpect(jsonPath("$.items[0].status").value("FAILED"))
                .andExpect(jsonPath("$.items[0].durationMs").value(1000))
                .andExpect(jsonPath("$.items[0].nodeCount").value(3))
                .andExpect(jsonPath("$.items[0].failedNodes").value(1))
                .andExpect(jsonPath("$.items[0].failedNodeId").value("x"))
                .andExpect(jsonPath("$.items[0].error.code").value("NODE_ERROR"))
                .andExpect(jsonPath("$.items[0].nodes").doesNotExist());
        mvc.perform(get("/api/v1/workflows/" + WORKFLOW + "/executions").param("status", "BOGUS"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void nodesEndpointReturnsNodeRunsWithDurations() throws Exception {
        ExecutionView view = failedView();
        given(queries.nodes(eq(EXECUTION), any())).willReturn(
                new ExecutionQueryService.NodesView(view.execution(), view.nodes()));

        mvc.perform(get("/api/v1/executions/" + EXECUTION + "/nodes").param("status", "FAILED", "SKIPPED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionId").value(EXECUTION.toString()))
                .andExpect(jsonPath("$.nodes[0].nodeId").value("x"))
                .andExpect(jsonPath("$.nodes[0].nodeName").value("x"))
                .andExpect(jsonPath("$.nodes[0].durationMs").value(1000))
                .andExpect(jsonPath("$.nodes[0].error.message").value("boom"))
                .andExpect(jsonPath("$.nodes[0].input.a").value(1));
    }
}
