package com.flowforge.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flowforge.api.error.GlobalExceptionHandler;
import com.flowforge.application.ConflictException;
import com.flowforge.application.InvalidRequestException;
import com.flowforge.application.ResourceNotFoundException;
import com.flowforge.application.WorkflowService;
import com.flowforge.application.WorkflowValidationException;
import com.flowforge.application.WorkflowValidationService;
import com.flowforge.domain.validation.ValidationCode;
import com.flowforge.domain.validation.ValidationIssue;
import com.flowforge.domain.validation.ValidationResult;
import com.flowforge.config.AppConfig;
import com.flowforge.domain.workflow.WorkflowDefinition;
import com.flowforge.domain.workflow.WorkflowStatus;
import com.flowforge.infrastructure.persistence.WorkflowEntity;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(WorkflowController.class)
@Import({AppConfig.class, GlobalExceptionHandler.class})
class WorkflowControllerTest {

    private static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String EMPTY_DEF = "{\"schemaVersion\":1,\"nodes\":[],\"edges\":[]}";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    WorkflowService service;

    @MockitoBean
    WorkflowValidationService validationService;

    @MockitoBean
    com.flowforge.application.execution.ExecutionQueryService executionQueries;

    private static WorkflowEntity entity() {
        WorkflowEntity e = new WorkflowEntity(ID, "Orders", null, WorkflowDefinition.empty());
        ReflectionTestUtils.setField(e, "lockVersion", 0L);
        return e;
    }

    @Test
    void createReturns201WithLocationAndBody() throws Exception {
        given(service.create(eq("Orders"), isNull(), any())).willReturn(entity());

        mockMvc.perform(post("/api/v1/workflows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Orders\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/workflows/" + ID))
                .andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.lockVersion").value(0))
                .andExpect(jsonPath("$.definition.schemaVersion").value(1))
                .andExpect(jsonPath("$.description").doesNotExist());
    }

    @Test
    void createValidatesRequiredFieldsAndDefinitionStructure() throws Exception {
        String badDefinition = "{\"name\":\"\",\"definition\":{\"schemaVersion\":2,\"nodes\":[{\"id\":\"bad id\","
                + "\"type\":\"x\",\"typeVersion\":0,\"name\":\"n\",\"position\":{\"x\":0,\"y\":0},"
                + "\"configuration\":{}}],\"edges\":[]}}";

        mockMvc.perform(post("/api/v1/workflows").contentType(MediaType.APPLICATION_JSON).content(badDefinition))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://flowforge.local/problems/validation-failed"))
                .andExpect(jsonPath("$.errors[?(@.field=='name')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field=='definition.schemaVersion')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field=='definition.nodes[0].id')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field=='definition.nodes[0].type')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field=='definition.nodes[0].typeVersion')]").exists());
    }

    @Test
    void unknownPropertiesAndMalformedJsonAreBadRequests() throws Exception {
        mockMvc.perform(post("/api/v1/workflows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"definitoin\":{}}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mockMvc.perform(post("/api/v1/workflows").contentType(MediaType.APPLICATION_JSON).content("{nope"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/workflows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"definition\":{\"schemaVersion\":1,\"nodes\":[],\"edges\":[],\"extra\":1}}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getReturns404ProblemDetailForUnknownId() throws Exception {
        given(service.get(ID)).willThrow(new ResourceNotFoundException("Workflow " + ID + " not found."));

        mockMvc.perform(get("/api/v1/workflows/" + ID))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://flowforge.local/problems/not-found"));
    }

    @Test
    void malformedIdIs400() throws Exception {
        mockMvc.perform(get("/api/v1/workflows/not-a-uuid")).andExpect(status().isBadRequest());
    }

    @Test
    void listMapsPageAndPassesFilters() throws Exception {
        given(service.search(eq("ord"), eq(List.of(WorkflowStatus.ACTIVE, WorkflowStatus.DRAFT)), eq(1), eq(5),
                eq("name,asc")))
                .willReturn(new PageImpl<>(List.of(entity()), org.springframework.data.domain.PageRequest.of(1, 5), 6));

        mockMvc.perform(get("/api/v1/workflows").param("name", "ord").param("status", "ACTIVE", "DRAFT")
                        .param("page", "1").param("size", "5").param("sort", "name,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("Orders"))
                .andExpect(jsonPath("$.items[0].nodeCount").value(0))
                .andExpect(jsonPath("$.items[0].definition").doesNotExist())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.totalElements").value(6))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void listRejectsUnknownStatusAndServiceValidationErrors() throws Exception {
        mockMvc.perform(get("/api/v1/workflows").param("status", "BOGUS")).andExpect(status().isBadRequest());

        given(service.search(any(), any(), eq(0), eq(20), eq("bad")))
                .willThrow(new InvalidRequestException("Unsupported sort"));
        mockMvc.perform(get("/api/v1/workflows").param("sort", "bad"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://flowforge.local/problems/invalid-request"));
    }

    @Test
    void updateRequiresLockVersionAndDefinition() throws Exception {
        mockMvc.perform(put("/api/v1/workflows/" + ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field=='lockVersion')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field=='definition')]").exists());
    }

    @Test
    void updateReturns200AndPassesLockVersion() throws Exception {
        given(service.update(eq(ID), eq("Orders"), isNull(), isNull(), any(), eq(4L))).willReturn(entity());

        mockMvc.perform(put("/api/v1/workflows/" + ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Orders\",\"lockVersion\":4,\"definition\":" + EMPTY_DEF + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ID.toString()));
    }

    @Test
    void updateConflictIs409() throws Exception {
        given(service.update(any(), any(), any(), any(), any(), eq(1L)))
                .willThrow(new ConflictException("stale"));

        mockMvc.perform(put("/api/v1/workflows/" + ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"lockVersion\":1,\"definition\":" + EMPTY_DEF + "}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://flowforge.local/problems/conflict"));
    }

    @Test
    void deleteArchivesByDefaultAndHardDeletesWhenPermanent() throws Exception {
        mockMvc.perform(delete("/api/v1/workflows/" + ID)).andExpect(status().isNoContent());
        verify(service).archive(ID);

        mockMvc.perform(delete("/api/v1/workflows/" + ID).param("permanent", "true"))
                .andExpect(status().isNoContent());
        verify(service).deletePermanently(ID);
    }

    @Test
    void duplicateAcceptsMissingBodyAndReturns201() throws Exception {
        given(service.duplicate(ID, null)).willReturn(entity());

        mockMvc.perform(post("/api/v1/workflows/" + ID + "/duplicate"))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"));
    }

    @Test
    void duplicatePassesExplicitName() throws Exception {
        given(service.duplicate(ID, "Copy of orders")).willReturn(entity());

        mockMvc.perform(post("/api/v1/workflows/" + ID + "/duplicate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Copy of orders\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void validateReturnsStructuredResultForBodyDefinition() throws Exception {
        ValidationResult result = new ValidationResult(List.of(
                ValidationIssue.of(ValidationCode.UNSUPPORTED_MERGE, "joined", "n2", "e7", "edges[3]"),
                ValidationIssue.of(ValidationCode.BRANCH_NOT_CONNECTED, "false open", "c1", null, "nodes[1]")),
                List.of());
        given(validationService.validate(eq(ID), any())).willReturn(result);

        mockMvc.perform(post("/api/v1/workflows/" + ID + "/validate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"definition\":" + EMPTY_DEF + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errorCount").value(1))
                .andExpect(jsonPath("$.warningCount").value(1))
                .andExpect(jsonPath("$.issues[0].code").value("UNSUPPORTED_MERGE"))
                .andExpect(jsonPath("$.issues[0].severity").value("ERROR"))
                .andExpect(jsonPath("$.issues[0].nodeId").value("n2"))
                .andExpect(jsonPath("$.issues[0].edgeId").value("e7"))
                .andExpect(jsonPath("$.issues[0].path").value("edges[3]"))
                .andExpect(jsonPath("$.issues[1].severity").value("WARNING"))
                .andExpect(jsonPath("$.issues[1].edgeId").doesNotExist());
        verify(validationService).validate(eq(ID), org.mockito.ArgumentMatchers.argThat(d -> d != null && d.nodes().isEmpty()));
    }

    @Test
    void validateWithoutBodyValidatesStoredDefinition() throws Exception {
        given(validationService.validate(ID, null)).willReturn(new ValidationResult(List.of(), List.of("t")));

        mockMvc.perform(post("/api/v1/workflows/" + ID + "/validate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.executionOrder[0]").value("t"));
    }

    @Test
    void validateUnknownWorkflowIs404() throws Exception {
        given(validationService.validate(eq(ID), any())).willThrow(new ResourceNotFoundException("nope"));

        mockMvc.perform(post("/api/v1/workflows/" + ID + "/validate")).andExpect(status().isNotFound());
    }

    @Test
    void activatingAnInvalidWorkflowIs422WithIssues() throws Exception {
        ValidationResult result = new ValidationResult(List.of(
                ValidationIssue.of(ValidationCode.NO_TRIGGER, "no trigger", null, null, "nodes")), List.of());
        given(service.update(any(), any(), any(), any(), any(), eq(0L)))
                .willThrow(new WorkflowValidationException("cannot activate", result));

        mockMvc.perform(put("/api/v1/workflows/" + ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"status\":\"ACTIVE\",\"lockVersion\":0,\"definition\":" + EMPTY_DEF + "}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://flowforge.local/problems/workflow-invalid"))
                .andExpect(jsonPath("$.issues[0].code").value("NO_TRIGGER"));
    }
}
