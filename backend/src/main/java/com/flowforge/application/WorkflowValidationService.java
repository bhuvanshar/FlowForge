package com.flowforge.application;

import com.flowforge.domain.validation.GraphValidator;
import com.flowforge.domain.validation.ValidationResult;
import com.flowforge.domain.workflow.WorkflowDefinition;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Validates workflow graphs. Never executes anything. */
@Service
@Transactional(readOnly = true)
public class WorkflowValidationService {

    private final WorkflowService workflows;
    private final GraphValidator validator;

    public WorkflowValidationService(WorkflowService workflows, GraphValidator validator) {
        this.workflows = workflows;
        this.validator = validator;
    }

    /**
     * Validates {@code candidate} (e.g. the editor's unsaved canvas) in the context of workflow {@code id}, or the stored
     * definition when {@code candidate} is null. The workflow must exist either way.
     */
    public ValidationResult validate(UUID id, WorkflowDefinition candidate) {
        WorkflowDefinition stored = workflows.get(id).getDefinition();
        return validator.validate(candidate != null ? candidate : stored);
    }
}
