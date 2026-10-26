package com.flowforge.api;

import com.flowforge.api.dto.ExecuteWorkflowRequest;
import com.flowforge.api.dto.ExecutionNodesResponse;
import com.flowforge.api.dto.ExecutionResponse;
import com.flowforge.api.dto.ExecutionSummaryResponse;
import com.flowforge.api.dto.PageResponse;
import com.flowforge.api.dto.ExecutionStartedResponse;
import com.flowforge.application.execution.ExecutionQueryService;
import com.flowforge.application.execution.WorkflowExecutionService;
import com.flowforge.domain.execution.ExecutionStatus;
import com.flowforge.domain.execution.NodeExecutionStatus;
import io.swagger.v3.oas.annotations.Parameter;
import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiPaths.BASE)
@Tag(name = "Executions", description = "Run workflows and inspect or cancel their executions")
public class ExecutionController {

    private final WorkflowExecutionService service;
    private final ExecutionQueryService queries;
    private final ExecutionEventStream eventStream;

    public ExecutionController(WorkflowExecutionService service, ExecutionQueryService queries,
                               ExecutionEventStream eventStream) {
        this.service = service;
        this.queries = queries;
        this.eventStream = eventStream;
    }

    @GetMapping(value = "/executions/{executionId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Live status changes of an execution (Server-Sent Events)",
            description = "First event is `snapshot` (persisted status of the execution and every node). Then "
                    + "`execution.started`, `node.started`, `node.succeeded`, `node.failed`, `node.skipped` and one of "
                    + "`execution.completed`, `execution.failed`, `execution.cancelled`, after which the stream ends. "
                    + "Events carry statuses and timing, never node input/output (use GET /executions/{id}). Reconnecting "
                    + "always starts with a fresh snapshot; an execution that already finished yields the snapshot only.")
    @ApiResponse(responseCode = "200", description = "text/event-stream")
    @ApiResponse(responseCode = "404", description = "Execution not found",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "Too many live connections",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<SseEmitter> events(@PathVariable UUID executionId) {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-cache, no-transform")
                .header("X-Accel-Buffering", "no") // tell nginx not to buffer the stream
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(eventStream.open(executionId));
    }

    @GetMapping("/workflows/{id}/executions")
    @Operation(summary = "List a workflow's executions, newest first",
            description = "Paginated, optionally filtered by status. Each row has the duration and node counts; "
                    + "history stays available after the workflow is edited or archived.")
    @ApiResponse(responseCode = "404", description = "Workflow not found",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "200", description = "A page of executions")
    public PageResponse<ExecutionSummaryResponse> list(
            @PathVariable UUID id,
            @Parameter(description = "Repeatable status filter") @RequestParam(required = false) List<ExecutionStatus> status,
            @Parameter(description = "Zero-based page index") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, 1..100") @RequestParam(defaultValue = "20") int size) {
        return PageResponse.of(queries.list(id, status, page, size), ExecutionSummaryResponse::from);
    }

    @GetMapping("/executions/{executionId}/nodes")
    @Operation(summary = "Node-level results of an execution, in execution order",
            description = "Inputs, outputs, errors, timing and the node name from the execution's own snapshot. "
                    + "Optional status filter.")
    @ApiResponse(responseCode = "404", description = "Not found",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "200", description = "Node results")
    public ExecutionNodesResponse nodes(@PathVariable UUID executionId,
                                        @RequestParam(required = false) List<NodeExecutionStatus> status) {
        return ExecutionNodesResponse.from(queries.nodes(executionId, status));
    }

    @PostMapping(value = "/workflows/{id}/execute", consumes = {MediaType.APPLICATION_JSON_VALUE, MediaType.ALL_VALUE})
    @Operation(summary = "Execute the saved workflow asynchronously",
            description = "Validates and snapshots the saved definition, queues the run and returns its id. Poll "
                    + "GET /executions/{executionId} for progress. Unsaved editor changes are not included.")
    @ApiResponse(responseCode = "202", description = "Queued; Location points to the execution")
    @ApiResponse(responseCode = "404", description = "Workflow not found",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Workflow is archived",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "Workflow is invalid (issues included)",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "501", description = "A node type has no executor yet",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "Execution queue is full",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ExecutionStartedResponse> execute(@PathVariable UUID id,
                                                            @RequestBody(required = false) ExecuteWorkflowRequest request) {
        WorkflowExecutionService.Started started = service.start(id, request == null ? null : request.input());
        return ResponseEntity.accepted()
                .location(URI.create(ApiPaths.BASE + "/executions/" + started.executionId()))
                .body(new ExecutionStartedResponse(started.executionId(), started.workflowId(), started.status()));
    }

    @GetMapping("/executions/{executionId}")
    @Operation(summary = "Get an execution with its node runs")
    @ApiResponse(responseCode = "404", description = "Not found",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "200", description = "The execution")
    public ExecutionResponse get(@PathVariable UUID executionId) {
        return ExecutionResponse.from(service.get(executionId));
    }

    @PostMapping("/executions/{executionId}/cancel")
    @Operation(summary = "Request cancellation of a queued or running execution",
            description = "Cancellation is asynchronous: the response may still show QUEUED or RUNNING.")
    @ApiResponse(responseCode = "202", description = "Cancellation requested")
    @ApiResponse(responseCode = "409", description = "Execution already finished",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ExecutionResponse> cancel(@PathVariable UUID executionId) {
        return ResponseEntity.accepted().body(ExecutionResponse.from(service.cancel(executionId)));
    }
}
