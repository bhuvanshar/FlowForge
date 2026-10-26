package com.flowforge.application.trigger;

import com.flowforge.domain.validation.NodeTypeDefinition;
import com.flowforge.domain.validation.NodeTypeRegistry;
import com.flowforge.domain.workflow.NodeDefinition;
import com.flowforge.domain.workflow.WorkflowDefinition;
import java.util.Optional;

/** Trigger node type identifiers (see {@code schemas/node-types.v1.json}). */
public final class TriggerNodes {

    public static final String MANUAL = "core.manualTrigger";
    public static final String WEBHOOK = "core.webhookTrigger";
    public static final String SCHEDULE = "core.scheduleTrigger";

    private TriggerNodes() {
    }

    /** The first node of the given type, if any. A valid graph has exactly one trigger. */
    public static Optional<NodeDefinition> find(WorkflowDefinition definition, String type) {
        return definition.nodes().stream().filter(n -> type.equals(n.type())).findFirst();
    }

    /** The trigger node of a definition, if it has any node whose type is a trigger. */
    public static Optional<NodeDefinition> trigger(WorkflowDefinition definition, NodeTypeRegistry registry) {
        return definition.nodes().stream()
                .filter(n -> registry.find(n.type()).map(NodeTypeDefinition::isTrigger).orElse(false))
                .findFirst();
    }
}
