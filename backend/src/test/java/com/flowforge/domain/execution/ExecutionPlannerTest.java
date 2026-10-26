package com.flowforge.domain.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.domain.workflow.EdgeDefinition;
import com.flowforge.domain.workflow.NodeDefinition;
import com.flowforge.domain.workflow.NodePosition;
import com.flowforge.domain.workflow.WorkflowDefinition;
import com.flowforge.infrastructure.catalog.JsonNodeTypeRegistry;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExecutionPlannerTest {

    private final ExecutionPlanner planner = new ExecutionPlanner(new JsonNodeTypeRegistry(new ObjectMapper()));

    private static NodeDefinition node(String id, String type) {
        return new NodeDefinition(id, type, 1, "Node " + id, new NodePosition(0, 0), Map.of());
    }

    private static EdgeDefinition edge(String id, String source, String handle, String target) {
        return new EdgeDefinition(id, source, handle, target, "main");
    }

    private ExecutionPlan plan(List<NodeDefinition> nodes, EdgeDefinition... edges) {
        return planner.plan(new WorkflowDefinition(1, nodes, Arrays.asList(edges), null));
    }

    @Test
    void startsAtTheTriggerEvenWhenItIsDefinedLast() {
        ExecutionPlan plan = plan(List.of(node("b", "core.logger"), node("a", "core.logger"),
                node("t", "core.manualTrigger")), edge("e1", "t", "main", "a"), edge("e2", "a", "main", "b"));

        assertThat(plan.nodeIds()).containsExactly("t", "a", "b");
        assertThat(plan.trigger().isTrigger()).isTrue();
        assertThat(plan.steps()).extracting(ExecutionPlan.Step::index).containsExactly(0, 1, 2);
    }

    @Test
    void breaksTiesByDefinitionOrderDeterministically() {
        List<NodeDefinition> nodes = List.of(node("t", "core.manualTrigger"), node("c", "core.condition"),
                node("z", "core.logger"), node("y", "core.logger"), node("x", "core.logger"), node("w", "core.logger"));
        EdgeDefinition[] edges = {edge("e1", "t", "main", "c"), edge("e2", "c", "true", "x"),
                edge("e3", "c", "false", "y"), edge("e4", "x", "main", "w"), edge("e5", "t", "main", "z")};

        ExecutionPlan plan = plan(nodes, edges);

        // Ready set after t: {c, z} -> c (index 1) first; after c: {z, y, x} -> z, y, x; then w.
        assertThat(plan.nodeIds()).containsExactly("t", "c", "z", "y", "x", "w");
        for (int i = 0; i < 10; i++) {
            assertThat(plan(nodes, edges)).isEqualTo(plan);
        }
    }

    @Test
    void recordsTheSingleIncomingEdgeAndOutgoingEdges() {
        ExecutionPlan plan = plan(List.of(node("t", "core.manualTrigger"), node("c", "core.condition"),
                node("a", "core.logger")), edge("e1", "t", "main", "c"), edge("e2", "c", "true", "a"));

        assertThat(plan.trigger().incoming()).isEmpty();
        assertThat(plan.steps().get(2).incoming()).get().extracting(EdgeDefinition::id).isEqualTo("e2");
        assertThat(plan.outgoing().get("c")).extracting(EdgeDefinition::id).containsExactly("e2");
        assertThat(plan.outgoing().get("a")).isEmpty();
        assertThat(plan.steps().get(1).type().outputs()).containsExactly("true", "false");
    }

    @Test
    void rejectsJoinsInsteadOfGuessingSemantics() {
        assertThatThrownBy(() -> plan(List.of(node("t", "core.manualTrigger"), node("c", "core.condition"),
                        node("j", "core.logger")),
                edge("e1", "t", "main", "c"), edge("e2", "c", "true", "j"), edge("e3", "c", "false", "j")))
                .isInstanceOf(PlanningException.class).hasMessageContaining("joins are not supported");
    }

    @Test
    void rejectsLoopsAndUnreachableNodes() {
        assertThatThrownBy(() -> plan(List.of(node("t", "core.manualTrigger"), node("a", "core.logger"),
                        node("b", "core.logger"), node("c", "core.logger")),
                edge("e1", "t", "main", "a"), edge("e2", "b", "main", "c"), edge("e3", "c", "main", "b")))
                .isInstanceOf(PlanningException.class).hasMessageContaining("loop");
        assertThatThrownBy(() -> plan(List.of(node("t", "core.manualTrigger"), node("orphan", "core.logger"))))
                .isInstanceOf(PlanningException.class).hasMessageContaining("unreachable");
    }

    @Test
    void rejectsMissingOrDuplicateTriggersUnknownTypesAndBadEdges() {
        assertThatThrownBy(() -> plan(List.of(node("a", "core.logger"))))
                .isInstanceOf(PlanningException.class).hasMessageContaining("no trigger");
        assertThatThrownBy(() -> plan(List.of(node("t", "core.manualTrigger"), node("t2", "core.manualTrigger"))))
                .isInstanceOf(PlanningException.class).hasMessageContaining("More than one trigger");
        assertThatThrownBy(() -> plan(List.of(node("t", "core.manualTrigger"), node("x", "vendor.thing")),
                edge("e1", "t", "main", "x")))
                .isInstanceOf(PlanningException.class).hasMessageContaining("Unknown node type");
        assertThatThrownBy(() -> plan(List.of(node("t", "core.manualTrigger"), node("a", "core.logger")),
                edge("e1", "t", "main", "a"), edge("e2", "a", "main", "t")))
                .isInstanceOf(PlanningException.class).hasMessageContaining("trigger cannot have incoming");
        assertThatThrownBy(() -> plan(List.of(node("t", "core.manualTrigger")), edge("e1", "t", "main", "ghost")))
                .isInstanceOf(PlanningException.class).hasMessageContaining("missing node");
        assertThatThrownBy(() -> plan(List.of(node("t", "core.manualTrigger"), node("t", "core.logger"))))
                .isInstanceOf(PlanningException.class).hasMessageContaining("Duplicate node id");
    }
}
