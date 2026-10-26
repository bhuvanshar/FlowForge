package com.flowforge.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.domain.validation.GraphValidator;
import com.flowforge.domain.validation.ValidationCode;
import com.flowforge.domain.workflow.EdgeDefinition;
import com.flowforge.domain.workflow.NodeDefinition;
import com.flowforge.infrastructure.catalog.JsonNodeTypeRegistry;
import com.flowforge.infrastructure.catalog.JsonSchemaConfigurationValidator;
import com.flowforge.domain.workflow.NodePosition;
import com.flowforge.domain.workflow.WorkflowDefinition;
import com.flowforge.domain.workflow.WorkflowStatus;
import com.flowforge.infrastructure.persistence.WorkflowEntity;
import com.flowforge.infrastructure.persistence.WorkflowRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class WorkflowServiceTest {

    @Mock
    WorkflowRepository repository;

    WorkflowService service;

    private static final UUID ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        JsonNodeTypeRegistry registry = new JsonNodeTypeRegistry(mapper);
        service = new WorkflowService(repository, mapper,
                new GraphValidator(registry, new JsonSchemaConfigurationValidator(mapper, registry)), workflow -> { });
    }

    private WorkflowEntity existing(WorkflowStatus status, long lockVersion) {
        WorkflowEntity e = new WorkflowEntity(ID, "Orders", "desc", WorkflowDefinition.empty());
        e.changeStatus(status);
        ReflectionTestUtils.setField(e, "lockVersion", lockVersion);
        when(repository.findById(ID)).thenReturn(Optional.of(e));
        return e;
    }

    private static WorkflowDefinition oneNode() {
        return new WorkflowDefinition(1, List.of(new NodeDefinition("a", "core.noOp", 1, "A",
                new NodePosition(0, 0), Map.of())), List.of(), null);
    }

    private void echoSave() {
        when(repository.saveAndFlush(any(WorkflowEntity.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void createNormalizesFieldsAndDefaultsToEmptyDraft() {
        echoSave();

        WorkflowEntity created = service.create("  Orders  ", "   ", null);

        assertThat(created.getName()).isEqualTo("Orders");
        assertThat(created.getDescription()).isNull();
        assertThat(created.getStatus()).isEqualTo(WorkflowStatus.DRAFT);
        assertThat(created.getVersion()).isEqualTo(1);
        assertThat(created.getDefinition()).isEqualTo(WorkflowDefinition.empty());
    }

    @Test
    void createRejectsBlankName() {
        assertThatThrownBy(() -> service.create("   ", null, null)).isInstanceOf(InvalidRequestException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void createRejectsOversizedDefinition() {
        Map<String, Object> big = Map.of("blob", "x".repeat(WorkflowService.MAX_DEFINITION_BYTES));
        WorkflowDefinition huge = new WorkflowDefinition(1, List.of(new NodeDefinition("a", "core.noOp", 1, "A",
                new NodePosition(0, 0), big)), List.of(), null);

        assertThatThrownBy(() -> service.create("x", null, huge)).isInstanceOf(PayloadTooLargeException.class);
    }

    @Test
    void getThrowsNotFound() {
        when(repository.findById(ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(ID)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateBumpsDefinitionVersionOnlyWhenDefinitionChanges() {
        WorkflowEntity e = existing(WorkflowStatus.DRAFT, 3);
        echoSave();

        service.update(ID, "Renamed", null, null, WorkflowDefinition.empty(), 3);
        assertThat(e.getVersion()).isEqualTo(1);
        assertThat(e.getName()).isEqualTo("Renamed");

        service.update(ID, "Renamed", null, WorkflowStatus.INACTIVE, oneNode(), 3);
        assertThat(e.getVersion()).isEqualTo(2);
        assertThat(e.getStatus()).isEqualTo(WorkflowStatus.INACTIVE);
    }

    private static WorkflowDefinition validGraph() {
        return new WorkflowDefinition(1, List.of(
                new NodeDefinition("t", "core.manualTrigger", 1, "Start", new NodePosition(0, 0), Map.of()),
                new NodeDefinition("l", "core.logger", 1, "Log", new NodePosition(200, 0), Map.of("message", "hi"))),
                List.of(new EdgeDefinition("e1", "t", "main", "l", "main")), null);
    }

    @Test
    void activationRequiresAValidGraphAndWritesNothingOtherwise() {
        WorkflowEntity e = existing(WorkflowStatus.DRAFT, 0);

        assertThatThrownBy(() -> service.update(ID, "Renamed", null, WorkflowStatus.ACTIVE, oneNode(), 0))
                .isInstanceOfSatisfying(WorkflowValidationException.class, ex -> assertThat(ex.result().issues())
                        .extracting(i -> i.code()).contains(ValidationCode.UNKNOWN_NODE_TYPE, ValidationCode.NO_TRIGGER));
        assertThat(e.getStatus()).isEqualTo(WorkflowStatus.DRAFT);
        assertThat(e.getName()).isEqualTo("Orders");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void activatingAValidGraphSucceeds() {
        WorkflowEntity e = existing(WorkflowStatus.DRAFT, 0);
        echoSave();

        service.update(ID, "Orders", null, WorkflowStatus.ACTIVE, validGraph(), 0);

        assertThat(e.getStatus()).isEqualTo(WorkflowStatus.ACTIVE);
    }

    @Test
    void draftsMaySaveInvalidGraphs() {
        WorkflowEntity e = existing(WorkflowStatus.DRAFT, 0);
        echoSave();
        service.update(ID, "Orders", null, null, oneNode(), 0);
        assertThat(e.getDefinition()).isEqualTo(oneNode());
    }

    /** Module 11: an ACTIVE workflow fires automatically, so it has to stay valid while it is active. */
    @Test
    void activeWorkflowsMustStayValid() {
        WorkflowEntity e = existing(WorkflowStatus.ACTIVE, 0);

        assertThatThrownBy(() -> service.update(ID, "Orders", null, null, WorkflowDefinition.empty(), 0))
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessageContaining("deactivate it to save an incomplete graph");
        assertThatThrownBy(() -> service.update(ID, "Orders", null, WorkflowStatus.ACTIVE, oneNode(), 0))
                .isInstanceOf(WorkflowValidationException.class);
        verify(repository, never()).saveAndFlush(any());

        echoSave();
        service.update(ID, "Orders", null, WorkflowStatus.INACTIVE, oneNode(), 0);
        assertThat(e.getStatus()).isEqualTo(WorkflowStatus.INACTIVE);
    }

    @Test
    void everyWriteSynchronizesTriggers() {
        List<WorkflowEntity> synced = new java.util.ArrayList<>();
        ObjectMapper mapper = new ObjectMapper();
        JsonNodeTypeRegistry registry = new JsonNodeTypeRegistry(mapper);
        service = new WorkflowService(repository, mapper,
                new GraphValidator(registry, new JsonSchemaConfigurationValidator(mapper, registry)), synced::add);
        WorkflowEntity e = existing(WorkflowStatus.DRAFT, 0);
        echoSave();

        service.update(ID, "Orders", null, null, validGraph(), 0);
        service.activate(ID);
        assertThat(e.getStatus()).isEqualTo(WorkflowStatus.ACTIVE);
        service.deactivate(ID);
        assertThat(e.getStatus()).isEqualTo(WorkflowStatus.INACTIVE);
        service.archive(ID);

        assertThat(synced).hasSize(4).allSatisfy(w -> assertThat(w).isSameAs(e));
    }

    @Test
    void activationValidatesTheSavedDefinitionAndRejectsArchived() {
        WorkflowEntity e = existing(WorkflowStatus.DRAFT, 0);
        assertThatThrownBy(() -> service.activate(ID)).isInstanceOf(WorkflowValidationException.class);
        assertThat(e.getStatus()).isEqualTo(WorkflowStatus.DRAFT);

        e.changeStatus(WorkflowStatus.ARCHIVED);
        assertThatThrownBy(() -> service.activate(ID)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.deactivate(ID)).isInstanceOf(ConflictException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void updateWithStaleLockVersionIsConflict() {
        existing(WorkflowStatus.DRAFT, 5);

        assertThatThrownBy(() -> service.update(ID, "x", null, null, WorkflowDefinition.empty(), 4))
                .isInstanceOf(ConflictException.class).hasMessageContaining("expected lockVersion 4, current 5");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void archivedWorkflowIsReadOnlyUntilRestored() {
        WorkflowEntity e = existing(WorkflowStatus.ARCHIVED, 0);
        echoSave();

        assertThatThrownBy(() -> service.update(ID, "x", null, null, WorkflowDefinition.empty(), 0))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.update(ID, "x", null, WorkflowStatus.ARCHIVED, WorkflowDefinition.empty(), 0))
                .isInstanceOf(ConflictException.class);

        service.update(ID, "x", null, WorkflowStatus.DRAFT, WorkflowDefinition.empty(), 0);
        assertThat(e.getStatus()).isEqualTo(WorkflowStatus.DRAFT);
    }

    @Test
    void archiveIsSoftAndIdempotent() {
        WorkflowEntity e = existing(WorkflowStatus.ACTIVE, 0);
        echoSave();

        service.archive(ID);
        service.archive(ID);

        assertThat(e.getStatus()).isEqualTo(WorkflowStatus.ARCHIVED);
        verify(repository).saveAndFlush(e); // second call is a no-op
        verify(repository, never()).delete(any(WorkflowEntity.class));
    }

    @Test
    void permanentDeleteRequiresArchivedWorkflow() {
        WorkflowEntity e = existing(WorkflowStatus.DRAFT, 0);

        assertThatThrownBy(() -> service.deletePermanently(ID)).isInstanceOf(ConflictException.class);
        verify(repository, never()).delete(any(WorkflowEntity.class));

        e.changeStatus(WorkflowStatus.ARCHIVED);
        service.deletePermanently(ID);
        verify(repository).delete(e);
    }

    @Test
    void duplicateCopiesDefinitionIntoNewDraft() {
        WorkflowEntity source = existing(WorkflowStatus.ACTIVE, 7);
        source.updateDefinition(oneNode());
        echoSave();

        WorkflowEntity copy = service.duplicate(ID, null);

        assertThat(copy.getId()).isNotEqualTo(ID);
        assertThat(copy.getName()).isEqualTo("Orders (copy)");
        assertThat(copy.getStatus()).isEqualTo(WorkflowStatus.DRAFT);
        assertThat(copy.getVersion()).isEqualTo(1);
        assertThat(copy.getDefinition()).isEqualTo(oneNode());
        assertThat(copy.getDescription()).isEqualTo("desc");
    }

    @Test
    void duplicateTruncatesLongNamesAndAcceptsExplicitName() {
        WorkflowEntity source = existing(WorkflowStatus.DRAFT, 0);
        source.rename("n".repeat(200), null);
        echoSave();

        assertThat(service.duplicate(ID, " ").getName()).hasSize(200).endsWith(" (copy)");
        assertThat(service.duplicate(ID, " Custom ").getName()).isEqualTo("Custom");
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchBuildsSortedBoundedPageRequest() {
        when(repository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(Page.empty());

        service.search("ord", List.of(WorkflowStatus.ACTIVE), 2, 50, "name,asc");

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAll(any(Specification.class), captor.capture());
        Pageable pageable = captor.getValue();
        assertThat(pageable.getPageNumber()).isEqualTo(2);
        assertThat(pageable.getPageSize()).isEqualTo(50);
        assertThat(pageable.getSort().getOrderFor("name").isAscending()).isTrue();
        assertThat(pageable.getSort().getOrderFor("name").isIgnoreCase()).isTrue();
        assertThat(pageable.getSort().getOrderFor("id").isAscending()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchDefaultsToUpdatedAtDescending() {
        when(repository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        service.search(null, null, 0, 20, null);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAll(any(Specification.class), captor.capture());
        assertThat(captor.getValue().getSort().toString()).isEqualTo("updatedAt: DESC,id: ASC");
    }

    @Test
    void searchRejectsBadPagingAndSort() {
        assertThatThrownBy(() -> service.search(null, null, -1, 20, null)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.search(null, null, 0, 101, null)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.search(null, null, 0, 0, null)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.search(null, null, 0, 10, "definition,asc"))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.search(null, null, 0, 10, "name,sideways"))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.search(null, null, 0, 10, "name,asc,x"))
                .isInstanceOf(InvalidRequestException.class);
        verify(repository, never()).findAll(any(Specification.class), eq(Pageable.unpaged()));
    }
}
