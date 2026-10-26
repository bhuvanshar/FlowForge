package com.flowforge.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.application.security.OwnerContext;
import com.flowforge.domain.validation.GraphValidator;
import com.flowforge.domain.validation.ValidationResult;
import com.flowforge.domain.workflow.WorkflowDefinition;
import com.flowforge.domain.workflow.WorkflowStatus;
import com.flowforge.infrastructure.persistence.WorkflowEntity;
import com.flowforge.infrastructure.persistence.WorkflowRepository;
import com.flowforge.infrastructure.persistence.WorkflowSpecifications;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Workflow management use cases.
 *
 * <p><b>Deletion model.</b> {@link #archive} is the normal "delete": it sets status {@code ARCHIVED} (soft delete) so
 * execution history stays intact and the workflow can be restored with an update. {@link #deletePermanently} is the
 * hard delete and is only allowed for already archived workflows; it cascades to executions and node executions.
 *
 * <p><b>Activation.</b> An {@code ACTIVE} workflow runs automatically through its trigger (webhook or schedule), so it
 * must always have a valid graph (see {@link GraphValidator}): activating it, or saving it while it stays active,
 * fails with {@link WorkflowValidationException} and writes nothing when the graph is invalid. Drafts and inactive
 * workflows may be saved in any state so work in progress is never lost. Every write ends with
 * {@link TriggerSynchronizer#synchronize} in the same transaction, which registers or deactivates the triggers.
 *
 * <p><b>Concurrency.</b> Updates must carry the {@code lockVersion} the client last read. A mismatch is a conflict
 * (409). Races between the check and the write are caught by JPA's version column.
 */
@Service
@Transactional(readOnly = true)
public class WorkflowService {

    public static final int MAX_NAME_LENGTH = 200;
    /** Compact JSON size limit enforced here; stricter than the 1 MiB database cap to leave headroom for jsonb text. */
    public static final int MAX_DEFINITION_BYTES = 512 * 1024;
    public static final int MAX_PAGE_SIZE = 100;

    private static final String COPY_SUFFIX = " (copy)";
    private static final String DEFAULT_SORT = "updatedAt,desc";
    private static final Map<String, String> SORTABLE = Map.of(
            "name", "name",
            "status", "status",
            "version", "version",
            "createdAt", "createdAt",
            "updatedAt", "updatedAt");

    private final WorkflowRepository repository;
    private final ObjectMapper objectMapper;
    private final GraphValidator validator;
    private final TriggerSynchronizer triggers;
    private final OwnerContext owners;

    public WorkflowService(WorkflowRepository repository, ObjectMapper objectMapper, GraphValidator validator,
                           TriggerSynchronizer triggers) {
        this(repository, objectMapper, validator, triggers, () -> OwnerContext.LOCAL_OWNER);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public WorkflowService(WorkflowRepository repository, ObjectMapper objectMapper, GraphValidator validator,
                           TriggerSynchronizer triggers, OwnerContext owners) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.triggers = triggers;
        this.owners = owners;
    }

    @Transactional
    public WorkflowEntity create(String name, String description, WorkflowDefinition definition) {
        WorkflowDefinition effective = definition == null ? WorkflowDefinition.empty() : definition;
        checkDefinitionSize(effective);
        WorkflowEntity entity = new WorkflowEntity(UUID.randomUUID(), owners.currentOwnerId(), normalizeName(name),
                normalizeDescription(description), effective);
        return saved(entity);
    }

    public WorkflowEntity get(UUID id) {
        return repository.findById(id).orElseThrow(() -> notFound(id));
    }

    /**
     * Lists workflows. Without a status filter, archived workflows are hidden; pass {@code ARCHIVED} explicitly
     * to see them.
     *
     * @param sort {@code property[,asc|desc]}; null means {@code updatedAt,desc}
     */
    public Page<WorkflowEntity> search(String name, List<WorkflowStatus> statuses, int page, int size, String sort) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidRequestException("page must be >= 0 and size must be between 1 and " + MAX_PAGE_SIZE);
        }
        // Id as tie-breaker keeps pagination stable when the sort key has duplicates.
        Sort order = parseSort(sort).and(Sort.by(Sort.Direction.ASC, "id"));
        var spec = WorkflowSpecifications.nameContains(name).and(WorkflowSpecifications.statusIn(statuses));
        return repository.findAll(spec, PageRequest.of(page, size, order));
    }

    @Transactional
    public WorkflowEntity update(UUID id, String name, String description, WorkflowStatus status,
                                 WorkflowDefinition definition, long expectedLockVersion) {
        WorkflowEntity entity = get(id);
        if (entity.getLockVersion() != expectedLockVersion) {
            throw new ConflictException("Workflow " + id + " was modified by someone else (expected lockVersion "
                    + expectedLockVersion + ", current " + entity.getLockVersion() + "). Reload and retry.");
        }
        if (entity.getStatus() == WorkflowStatus.ARCHIVED && (status == null || status == WorkflowStatus.ARCHIVED)) {
            throw new ConflictException("Workflow " + id + " is archived and read-only. "
                    + "Set a status other than ARCHIVED to restore it.");
        }
        checkDefinitionSize(definition);
        WorkflowStatus target = status != null ? status : entity.getStatus();
        if (target == WorkflowStatus.ACTIVE) {
            requireValid(definition, entity.getStatus() == WorkflowStatus.ACTIVE
                    ? "The active workflow cannot be saved (deactivate it to save an incomplete graph)"
                    : "The workflow cannot be activated");
        }
        entity.rename(normalizeName(name), normalizeDescription(description));
        if (status != null) {
            entity.changeStatus(status);
        }
        entity.updateDefinition(definition);
        return saved(entity);
    }

    /**
     * Activates the saved definition: its trigger starts receiving webhooks or firing on schedule. Idempotent.
     * Archived workflows must be restored with an update first.
     */
    @Transactional
    public WorkflowEntity activate(UUID id) {
        WorkflowEntity entity = get(id);
        if (entity.getStatus() == WorkflowStatus.ARCHIVED) {
            throw new ConflictException("Workflow " + id + " is archived. Restore it before activating.");
        }
        requireValid(entity.getDefinition(), "The workflow cannot be activated");
        entity.changeStatus(WorkflowStatus.ACTIVE);
        return saved(entity);
    }

    /** Stops automatic triggering (status INACTIVE). Idempotent for workflows that are not active. */
    @Transactional
    public WorkflowEntity deactivate(UUID id) {
        WorkflowEntity entity = get(id);
        if (entity.getStatus() == WorkflowStatus.ARCHIVED) {
            throw new ConflictException("Workflow " + id + " is archived.");
        }
        if (entity.getStatus() == WorkflowStatus.ACTIVE) {
            entity.changeStatus(WorkflowStatus.INACTIVE);
        }
        return saved(entity);
    }

    /** Soft delete. Idempotent. Deactivates the triggers. */
    @Transactional
    public void archive(UUID id) {
        WorkflowEntity entity = get(id);
        if (entity.getStatus() != WorkflowStatus.ARCHIVED) {
            entity.changeStatus(WorkflowStatus.ARCHIVED);
            saved(entity);
        }
    }

    /** Hard delete; permanently removes the workflow and its execution history. Requires prior archiving. */
    @Transactional
    public void deletePermanently(UUID id) {
        WorkflowEntity entity = get(id);
        if (entity.getStatus() != WorkflowStatus.ARCHIVED) {
            throw new ConflictException("Workflow " + id + " must be archived before it can be permanently deleted.");
        }
        repository.delete(entity);
        repository.flush();
    }

    /** Copies name, description and definition into a new DRAFT workflow at version 1. */
    @Transactional
    public WorkflowEntity duplicate(UUID id, String newName) {
        WorkflowEntity source = get(id);
        String name = newName == null || newName.isBlank() ? copyName(source.getName()) : normalizeName(newName);
        return saved(new WorkflowEntity(UUID.randomUUID(), owners.currentOwnerId(), name, source.getDescription(),
                source.getDefinition()));
    }

    private WorkflowEntity saved(WorkflowEntity entity) {
        WorkflowEntity saved = repository.saveAndFlush(entity);
        triggers.synchronize(saved);
        return saved;
    }

    private void requireValid(WorkflowDefinition definition, String reason) {
        ValidationResult result = validator.validate(definition);
        if (!result.valid()) {
            throw new WorkflowValidationException(reason + ": it has " + result.errorCount()
                    + " validation error(s).", result);
        }
    }

    private static String copyName(String original) {
        int room = MAX_NAME_LENGTH - COPY_SUFFIX.length();
        String base = original.length() > room ? original.substring(0, room) : original;
        return base + COPY_SUFFIX;
    }

    private static Sort parseSort(String sort) {
        String[] parts = (sort == null || sort.isBlank() ? DEFAULT_SORT : sort).split(",", -1);
        String property = SORTABLE.get(parts[0].strip());
        if (property == null || parts.length > 2) {
            throw new InvalidRequestException("Unsupported sort '" + sort + "'. Use one of " + SORTABLE.keySet()
                    + " optionally followed by ,asc or ,desc.");
        }
        Sort.Direction direction = Sort.Direction.DESC;
        if (parts.length == 2) {
            direction = switch (parts[1].strip().toLowerCase()) {
                case "asc" -> Sort.Direction.ASC;
                case "desc" -> Sort.Direction.DESC;
                default -> throw new InvalidRequestException("Sort direction must be asc or desc.");
            };
        }
        Sort.Order order = new Sort.Order(direction, property);
        // Names sort case-insensitively regardless of the database collation.
        return Sort.by("name".equals(property) ? order.ignoreCase() : order);
    }

    private void checkDefinitionSize(WorkflowDefinition definition) {
        try {
            if (objectMapper.writeValueAsBytes(definition).length > MAX_DEFINITION_BYTES) {
                throw new PayloadTooLargeException("Workflow definition exceeds " + MAX_DEFINITION_BYTES + " bytes.");
            }
        } catch (JsonProcessingException e) {
            throw new InvalidRequestException("Workflow definition is not serializable to JSON.");
        }
    }

    private static String normalizeName(String name) {
        String stripped = name == null ? "" : name.strip();
        if (stripped.isEmpty() || stripped.length() > MAX_NAME_LENGTH) {
            throw new InvalidRequestException("Name must be 1 to " + MAX_NAME_LENGTH + " characters.");
        }
        return stripped;
    }

    private static String normalizeDescription(String description) {
        return description == null || description.isBlank() ? null : description.strip();
    }

    private static ResourceNotFoundException notFound(UUID id) {
        return new ResourceNotFoundException("Workflow " + id + " not found.");
    }
}
