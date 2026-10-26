package com.flowforge.api;

import com.flowforge.api.dto.CreateWorkflowRequest;
import com.flowforge.api.dto.DuplicateWorkflowRequest;
import com.flowforge.api.dto.PageResponse;
import com.flowforge.api.dto.UpdateWorkflowRequest;
import com.flowforge.api.dto.ValidateWorkflowRequest;
import com.flowforge.api.dto.ValidationResultResponse;
import com.flowforge.api.dto.WorkflowMapper;
import com.flowforge.api.dto.WorkflowResponse;
import com.flowforge.api.dto.WorkflowSummaryResponse;
import com.flowforge.application.WorkflowService;
import com.flowforge.application.WorkflowValidationService;
import com.flowforge.application.execution.ExecutionQueryService;
import com.flowforge.domain.workflow.WorkflowStatus;
import com.flowforge.infrastructure.persistence.WorkflowEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiPaths.BASE + "/workflows")
@Tag(name = "Workflows", description = "Create, read, update, duplicate and delete workflow definitions")
@ApiResponse(responseCode = "400", description = "Validation failed or invalid request",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
public class WorkflowController {

    private final WorkflowService service;
    private final WorkflowValidationService validationService;
    private final ExecutionQueryService executionQueries;

    public WorkflowController(WorkflowService service, WorkflowValidationService validationService,
                              ExecutionQueryService executionQueries) {
        this.service = service;
        this.validationService = validationService;
        this.executionQueries = executionQueries;
    }

    @PostMapping
    @Operation(summary = "Create a workflow (status DRAFT, version 1)")
    @ApiResponse(responseCode = "201", description = "Created; Location header points to the new workflow")
    public ResponseEntity<WorkflowResponse> create(@Valid @RequestBody CreateWorkflowRequest request) {
        WorkflowEntity created = service.create(request.name(), request.description(), request.definition());
        return ResponseEntity.created(location(created.getId())).body(WorkflowMapper.toResponse(created));
    }

    @GetMapping
    @Operation(summary = "List workflows",
            description = "Paginated and sortable. Archived workflows are hidden unless status=ARCHIVED is requested. "
                    + "Each item carries lastExecution (newest run of any kind) when the workflow ever ran.")
    @ApiResponse(responseCode = "200", description = "A page of workflow summaries")
    public PageResponse<WorkflowSummaryResponse> list(
            @Parameter(description = "Case-insensitive substring of the name") @RequestParam(required = false) String name,
            @Parameter(description = "Repeatable status filter") @RequestParam(required = false) List<WorkflowStatus> status,
            @Parameter(description = "Zero-based page index") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, 1..100") @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "property[,asc|desc]; properties: name, status, version, createdAt, updatedAt",
                    example = "updatedAt,desc") @RequestParam(required = false) String sort) {
        var found = service.search(name, status, page, size, sort);
        // One extra query for the whole page: the newest execution of each listed workflow.
        var latest = executionQueries.latestByWorkflow(found.getContent().stream().map(WorkflowEntity::getId).toList());
        return PageResponse.of(found, workflow -> WorkflowMapper.toSummary(workflow, latest.get(workflow.getId())));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a workflow including its definition")
    @ApiResponse(responseCode = "404", description = "Not found",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "200", description = "The workflow")
    public WorkflowResponse get(@PathVariable UUID id) {
        return WorkflowMapper.toResponse(service.get(id));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Replace a workflow's editable fields",
            description = "Requires the lockVersion from the last read. The definition version increments only when "
                    + "the definition actually changed.")
    @ApiResponse(responseCode = "404", description = "Not found",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "The resulting status is ACTIVE (activation, or saving an active "
            + "workflow) but the graph is invalid; the Problem Detail carries the validation issues",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Stale lockVersion, or workflow is archived",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "413", description = "Definition too large",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "200", description = "Saved")
    public WorkflowResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateWorkflowRequest request) {
        return WorkflowMapper.toResponse(service.update(id, request.name(), request.description(), request.status(),
                request.definition(), request.lockVersion()));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a workflow",
            description = "Default: soft delete, sets status ARCHIVED (idempotent). With permanent=true: hard delete of "
                    + "an already archived workflow, including its executions.")
    @ApiResponse(responseCode = "204", description = "Archived or permanently deleted")
    @ApiResponse(responseCode = "404", description = "Not found",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "permanent=true on a workflow that is not archived",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<Void> delete(@PathVariable UUID id,
                                       @RequestParam(defaultValue = "false") boolean permanent) {
        if (permanent) {
            service.deletePermanently(id);
        } else {
            service.archive(id);
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/{id}/duplicate", consumes = {MediaType.APPLICATION_JSON_VALUE, MediaType.ALL_VALUE})
    @Operation(summary = "Duplicate a workflow into a new DRAFT (version 1)",
            description = "The request body is optional.")
    @ApiResponse(responseCode = "201", description = "Created; Location header points to the copy")
    @ApiResponse(responseCode = "404", description = "Not found",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<WorkflowResponse> duplicate(@PathVariable UUID id,
                                                      @Valid @RequestBody(required = false) DuplicateWorkflowRequest request) {
        WorkflowEntity copy = service.duplicate(id, request == null ? null : request.name());
        return ResponseEntity.created(location(copy.getId())).body(WorkflowMapper.toResponse(copy));
    }

    @PostMapping("/{id}/activate")
    @Operation(summary = "Activate a workflow",
            description = "Sets status ACTIVE after validating the saved definition. Its webhook starts accepting "
                    + "deliveries / its schedule starts firing (next occurrence after now). Idempotent.")
    @ApiResponse(responseCode = "200", description = "Active")
    @ApiResponse(responseCode = "404", description = "Not found",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Workflow is archived",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "The saved graph is invalid; the Problem Detail carries the issues",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public WorkflowResponse activate(@PathVariable UUID id) {
        return WorkflowMapper.toResponse(service.activate(id));
    }

    @PostMapping("/{id}/deactivate")
    @Operation(summary = "Deactivate a workflow",
            description = "Sets an ACTIVE workflow to INACTIVE: its webhook answers 404 and its schedule stops. "
                    + "Runs already started continue. Webhook id and secret are kept for reactivation. Idempotent.")
    @ApiResponse(responseCode = "200", description = "Not active")
    @ApiResponse(responseCode = "404", description = "Not found",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Workflow is archived",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public WorkflowResponse deactivate(@PathVariable UUID id) {
        return WorkflowMapper.toResponse(service.deactivate(id));
    }

    @PostMapping(value = "/{id}/validate", consumes = {MediaType.APPLICATION_JSON_VALUE, MediaType.ALL_VALUE})
    @Operation(summary = "Validate a workflow graph without executing it",
            description = "Validates the definition in the request body (e.g. unsaved editor state) or, without a body, "
                    + "the stored definition. Always 200 for an existing workflow: check `valid` and `issues`.")
    @ApiResponse(responseCode = "200", description = "Validation result")
    @ApiResponse(responseCode = "404", description = "Not found",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ValidationResultResponse validate(@PathVariable UUID id,
                                             @Valid @RequestBody(required = false) ValidateWorkflowRequest request) {
        return ValidationResultResponse.from(
                validationService.validate(id, request == null ? null : request.definition()));
    }

    private static URI location(UUID id) {
        return URI.create(ApiPaths.BASE + "/workflows/" + id);
    }
}
