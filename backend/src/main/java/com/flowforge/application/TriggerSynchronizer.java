package com.flowforge.application;

import com.flowforge.infrastructure.persistence.WorkflowEntity;

/**
 * Keeps a workflow's automatic trigger registrations in line with its status and definition. {@link WorkflowService}
 * calls it in the same transaction as every write, so a workflow is ACTIVE exactly when its triggers are registered.
 */
@FunctionalInterface
public interface TriggerSynchronizer {

    void synchronize(WorkflowEntity workflow);
}
