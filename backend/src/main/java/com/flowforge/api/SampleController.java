package com.flowforge.api;

import com.flowforge.api.dto.WorkflowMapper;
import com.flowforge.api.dto.WorkflowResponse;
import com.flowforge.application.SampleWorkflowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiPaths.BASE + "/samples")
@Tag(name = "Samples", description = "Ready-made example workflows")
public class SampleController {

    public record CreateSampleRequest(@Size(max = 36) String credentialId) {
    }

    private final SampleWorkflowService service;

    public SampleController(SampleWorkflowService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List the sample workflows")
    public List<SampleWorkflowService.Sample> list() {
        return service.list();
    }

    @PostMapping(value = "/{id}/create", consumes = {MediaType.APPLICATION_JSON_VALUE, MediaType.ALL_VALUE})
    @Operation(summary = "Create a DRAFT workflow from a sample",
            description = "Optional body {credentialId}: stored on the sample's AI Model node. Nothing is executed.")
    @ApiResponse(responseCode = "201", description = "Created; Location points to the new workflow")
    @ApiResponse(responseCode = "404", description = "Unknown sample",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<WorkflowResponse> create(@PathVariable String id,
                                                   @RequestBody(required = false) CreateSampleRequest request) {
        var created = service.create(id, request == null ? null : request.credentialId());
        return ResponseEntity.created(URI.create(ApiPaths.BASE + "/workflows/" + created.getId()))
                .body(WorkflowMapper.toResponse(created));
    }
}
