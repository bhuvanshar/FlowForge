package com.flowforge.domain.validation;

import static com.flowforge.domain.validation.ValidationCode.BRANCH_NOT_CONNECTED;
import static com.flowforge.domain.validation.ValidationCode.CONFIGURATION_INVALID;
import static com.flowforge.domain.validation.ValidationCode.CYCLE_DETECTED;
import static com.flowforge.domain.validation.ValidationCode.DISCONNECTED_NODE;
import static com.flowforge.domain.validation.ValidationCode.DUPLICATE_CONNECTION;
import static com.flowforge.domain.validation.ValidationCode.DUPLICATE_EDGE_ID;
import static com.flowforge.domain.validation.ValidationCode.DUPLICATE_NODE_ID;
import static com.flowforge.domain.validation.ValidationCode.DUPLICATE_NODE_NAME;
import static com.flowforge.domain.validation.ValidationCode.EDGE_SOURCE_NOT_FOUND;
import static com.flowforge.domain.validation.ValidationCode.EDGE_TARGET_NOT_FOUND;
import static com.flowforge.domain.validation.ValidationCode.INVALID_SOURCE_HANDLE;
import static com.flowforge.domain.validation.ValidationCode.INVALID_TARGET_HANDLE;
import static com.flowforge.domain.validation.ValidationCode.MULTIPLE_TRIGGERS;
import static com.flowforge.domain.validation.ValidationCode.NO_BRANCH_CONNECTED;
import static com.flowforge.domain.validation.ValidationCode.NO_TRIGGER;
import static com.flowforge.domain.validation.ValidationCode.SELF_LOOP;
import static com.flowforge.domain.validation.ValidationCode.TOO_MANY_EDGES;
import static com.flowforge.domain.validation.ValidationCode.TOO_MANY_NODES;
import static com.flowforge.domain.validation.ValidationCode.TOO_MANY_OUTGOING_EDGES;
import static com.flowforge.domain.validation.ValidationCode.TRIGGER_NOT_CONNECTED;
import static com.flowforge.domain.validation.ValidationCode.UNKNOWN_NODE_TYPE;
import static com.flowforge.domain.validation.ValidationCode.UNREACHABLE_NODE;
import static com.flowforge.domain.validation.ValidationCode.UNSUPPORTED_MERGE;
import static com.flowforge.domain.validation.ValidationCode.UNSUPPORTED_NODE_TYPE_VERSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.domain.workflow.EdgeDefinition;
import com.flowforge.domain.workflow.NodeDefinition;
import com.flowforge.domain.workflow.NodePosition;
import com.flowforge.domain.workflow.WorkflowDefinition;
import com.flowforge.infrastructure.catalog.JsonNodeTypeRegistry;
import com.flowforge.infrastructure.catalog.JsonSchemaConfigurationValidator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Exercises every rule against the real node catalog and configuration schemas. */
class GraphValidatorTest {

    private static final GraphValidator VALIDATOR;

    static {
        ObjectMapper mapper = new ObjectMapper();
        JsonNodeTypeRegistry registry = new JsonNodeTypeRegistry(mapper);
        VALIDATOR = new GraphValidator(registry, new JsonSchemaConfigurationValidator(mapper, registry));
    }

    // ----------------------------------------------------------------------------------------------------------------
    // Builders: nodes get a valid configuration for their type by default, so tests isolate one rule at a time.
    // ----------------------------------------------------------------------------------------------------------------

    private static Map<String, Object> validConfig(String type) {
        return switch (type) {
            case "core.logger" -> Map.of("message", "hi");
            case "core.condition" -> Map.of("field", "a", "operator", "equals", "value", "1");
            case "core.setVariables" -> Map.of("variables", List.of(Map.of("name", "a", "value", "1")));
            case "core.transform" -> Map.of("operations", List.of(Map.of("field", "a", "source", "x", "operation", "trim")));
            case "core.httpRequest" -> Map.of("method", "GET", "url", "https://x.io", "headers", List.of(),
                    "query", List.of(), "timeoutSeconds", 30);
            default -> Map.of();
        };
    }

    private static NodeDefinition node(String id, String type) {
        return node(id, type, validConfig(type));
    }

    private static NodeDefinition node(String id, String type, Map<String, Object> config) {
        return new NodeDefinition(id, type, 1, "Node " + id, new NodePosition(0, 0), config);
    }

    private static NodeDefinition trigger(String id) {
        return node(id, "core.manualTrigger");
    }

    private static NodeDefinition logger(String id) {
        return node(id, "core.logger");
    }

    private static NodeDefinition condition(String id) {
        return node(id, "core.condition");
    }

    private static EdgeDefinition edge(String id, String source, String target) {
        return edge(id, source, "main", target, "main");
    }

    private static EdgeDefinition edge(String id, String source, String sourceHandle, String target, String targetHandle) {
        return new EdgeDefinition(id, source, sourceHandle, target, targetHandle);
    }

    private static WorkflowDefinition graph(List<NodeDefinition> nodes, List<EdgeDefinition> edges) {
        return new WorkflowDefinition(1, nodes, edges, null);
    }

    private static ValidationResult validate(List<NodeDefinition> nodes, EdgeDefinition... edges) {
        return VALIDATOR.validate(graph(nodes, Arrays.asList(edges)));
    }

    private static List<ValidationCode> codes(ValidationResult result) {
        return result.issues().stream().map(ValidationIssue::code).toList();
    }

    private static ValidationIssue only(ValidationResult result, ValidationCode code) {
        List<ValidationIssue> matching = result.issues().stream().filter(i -> i.code() == code).toList();
        assertThat(matching).as("issues with code " + code + " in " + result.issues()).hasSize(1);
        return matching.get(0);
    }

    // ----------------------------------------------------------------------------------------------------------------

    @Nested
    class ValidGraphs {

        @Test
        void linearGraphPassesWithExecutionOrder() {
            ValidationResult result = validate(List.of(trigger("t"), node("v", "core.setVariables"), logger("l")),
                    edge("e1", "t", "v"), edge("e2", "v", "l"));

            assertThat(result.issues()).isEmpty();
            assertThat(result.valid()).isTrue();
            assertThat(result.executionOrder()).containsExactly("t", "v", "l");
        }

        @Test
        void conditionWithBothBranchesPasses() {
            ValidationResult result = validate(
                    List.of(trigger("t"), condition("c"), logger("yes"), node("no", "core.httpRequest")),
                    edge("e1", "t", "c"), edge("e2", "c", "true", "yes", "main"), edge("e3", "c", "false", "no", "main"));

            assertThat(result.issues()).isEmpty();
            assertThat(result.executionOrder()).containsExactly("t", "c", "yes", "no");
        }

        @Test
        void fanOutFromOneHandleIsAllowedAndOrderIsDeterministicByDefinitionPosition() {
            ValidationResult result = validate(List.of(trigger("t"), logger("b"), logger("a")),
                    edge("e1", "t", "a"), edge("e2", "t", "b"));

            assertThat(result.valid()).isTrue();
            assertThat(result.executionOrder()).containsExactly("t", "b", "a");
        }

        @Test
        void triggerAloneIsValidButWarns() {
            ValidationResult result = validate(List.of(trigger("t")));

            assertThat(result.valid()).isTrue();
            assertThat(only(result, TRIGGER_NOT_CONNECTED))
                    .extracting(ValidationIssue::severity, ValidationIssue::nodeId)
                    .containsExactly(Severity.WARNING, "t");
            assertThat(result.executionOrder()).containsExactly("t");
        }

        @Test
        void sameInputIsAlwaysValidatedIdentically() {
            List<NodeDefinition> nodes = List.of(trigger("t"), logger("a"), logger("b"), condition("c"), logger("d"));
            EdgeDefinition[] edges = {edge("e1", "t", "a"), edge("e2", "a", "b"), edge("e3", "b", "a"),
                    edge("e4", "c", "true", "d", "main"), edge("e5", "x", "d")};

            ValidationResult first = validate(nodes, edges);
            for (int i = 0; i < 5; i++) {
                assertThat(validate(nodes, edges)).isEqualTo(first);
            }
        }
    }

    @Nested
    class UniqueIds {

        @Test
        void duplicateNodeIdIsReportedOnTheSecondOccurrence() {
            ValidationResult result = validate(List.of(trigger("t"), logger("a"), logger("a")), edge("e1", "t", "a"));

            assertThat(only(result, DUPLICATE_NODE_ID))
                    .extracting(ValidationIssue::nodeId, ValidationIssue::path)
                    .containsExactly("a", "nodes[2].id");
        }

        @Test
        void duplicateEdgeIdIsReported() {
            ValidationResult result = validate(List.of(trigger("t"), logger("a"), logger("b")),
                    edge("e1", "t", "a"), edge("e1", "a", "b"));

            assertThat(only(result, DUPLICATE_EDGE_ID))
                    .extracting(ValidationIssue::edgeId, ValidationIssue::path)
                    .containsExactly("e1", "edges[1].id");
        }

        @Test
        void duplicateNodeNamesAreReported() {
            NodeDefinition first = new NodeDefinition("a", "core.logger", 1, "Same", new NodePosition(0, 0),
                    validConfig("core.logger"));
            NodeDefinition second = new NodeDefinition("b", "core.logger", 1, "Same", new NodePosition(0, 0),
                    validConfig("core.logger"));
            ValidationResult result = validate(List.of(trigger("t"), first, second), edge("e1", "t", "a"),
                    edge("e2", "a", "b"));

            assertThat(only(result, DUPLICATE_NODE_NAME))
                    .extracting(ValidationIssue::nodeId, ValidationIssue::path)
                    .containsExactly("b", "nodes[2].name");
        }
    }

    @Nested
    class NodeTypes {

        @Test
        void unknownTypeIsReportedAndItsConfigurationIsNotChecked() {
            ValidationResult result = validate(List.of(trigger("t"), node("x", "vendor.magic", Map.of("any", 1))),
                    edge("e1", "t", "x"));

            assertThat(only(result, UNKNOWN_NODE_TYPE))
                    .extracting(ValidationIssue::nodeId, ValidationIssue::path)
                    .containsExactly("x", "nodes[1].type");
            assertThat(codes(result)).doesNotContain(CONFIGURATION_INVALID);
        }

        @Test
        void unsupportedTypeVersionIsReported() {
            NodeDefinition v2 = new NodeDefinition("l", "core.logger", 2, "Log", new NodePosition(0, 0),
                    validConfig("core.logger"));
            ValidationResult result = validate(List.of(trigger("t"), v2), edge("e1", "t", "l"));

            assertThat(only(result, UNSUPPORTED_NODE_TYPE_VERSION).path()).isEqualTo("nodes[1].typeVersion");
        }
    }

    @Nested
    class Edges {

        @Test
        void missingEndpointsAreReportedPerSide() {
            ValidationResult result = validate(List.of(trigger("t"), logger("a")),
                    edge("e1", "t", "a"), edge("e2", "ghost", "a"), edge("e3", "a", "nowhere"));

            assertThat(only(result, EDGE_SOURCE_NOT_FOUND))
                    .extracting(ValidationIssue::edgeId, ValidationIssue::path)
                    .containsExactly("e2", "edges[1].source");
            assertThat(only(result, EDGE_TARGET_NOT_FOUND))
                    .extracting(ValidationIssue::edgeId, ValidationIssue::path)
                    .containsExactly("e3", "edges[2].target");
        }

        @Test
        void selfLoopIsReported() {
            ValidationResult result = validate(List.of(trigger("t"), logger("a")), edge("e1", "t", "a"),
                    edge("e2", "a", "a"));

            assertThat(only(result, SELF_LOOP))
                    .extracting(ValidationIssue::nodeId, ValidationIssue::edgeId)
                    .containsExactly("a", "e2");
            assertThat(codes(result)).doesNotContain(CYCLE_DETECTED, UNSUPPORTED_MERGE);
        }

        @Test
        void invalidSourceHandleNamesTheAvailableOutputs() {
            ValidationResult result = validate(List.of(trigger("t"), logger("a"), logger("b")),
                    edge("e1", "t", "a"), edge("e2", "a", "true", "b", "main"));

            ValidationIssue issue = only(result, INVALID_SOURCE_HANDLE);
            assertThat(issue.edgeId()).isEqualTo("e2");
            assertThat(issue.nodeId()).isEqualTo("a");
            assertThat(issue.path()).isEqualTo("edges[1].sourceHandle");
            assertThat(issue.message()).contains("'true'", "outputs: main");
            // The edge still counts for connectivity, so b is not additionally reported as unreachable.
            assertThat(codes(result)).doesNotContain(UNREACHABLE_NODE, DISCONNECTED_NODE);
        }

        @Test
        void triggersAcceptNoIncomingConnections() {
            ValidationResult result = validate(List.of(trigger("t"), logger("a")), edge("e1", "t", "a"),
                    edge("e2", "a", "t"));

            ValidationIssue issue = only(result, INVALID_TARGET_HANDLE);
            assertThat(issue.nodeId()).isEqualTo("t");
            assertThat(issue.message()).contains("trigger and cannot have incoming connections");
        }

        @Test
        void conditionOutputsAreOnlyTrueAndFalse() {
            ValidationResult result = validate(List.of(trigger("t"), condition("c"), logger("a")),
                    edge("e1", "t", "c"), edge("e2", "c", "main", "a", "main"));

            assertThat(only(result, INVALID_SOURCE_HANDLE).message()).contains("outputs: true, false");
        }

        @Test
        void duplicateConnectionIsReportedOnce() {
            ValidationResult result = validate(List.of(trigger("t"), logger("a")), edge("e1", "t", "a"),
                    edge("e2", "t", "a"));

            assertThat(only(result, DUPLICATE_CONNECTION).edgeId()).isEqualTo("e2");
            assertThat(codes(result)).doesNotContain(UNSUPPORTED_MERGE);
        }
    }

    @Nested
    class Triggers {

        @Test
        void emptyWorkflowHasNoTrigger() {
            ValidationResult result = validate(List.of());

            assertThat(codes(result)).containsExactly(NO_TRIGGER);
            assertThat(result.issues().get(0).message()).contains("empty");
            assertThat(result.executionOrder()).isEmpty();
        }

        @Test
        void graphWithoutTriggerReportsNoTriggerButNoReachabilityNoise() {
            ValidationResult result = validate(List.of(logger("a"), logger("b")), edge("e1", "a", "b"));

            assertThat(codes(result)).containsExactly(NO_TRIGGER);
            assertThat(result.issues().get(0).path()).isEqualTo("nodes");
        }

        @Test
        void everyTriggerAfterTheFirstIsReported() {
            ValidationResult result = validate(List.of(trigger("t1"), logger("a"), trigger("t2"), trigger("t3")),
                    edge("e1", "t1", "a"));

            assertThat(result.issues()).filteredOn(i -> i.code() == MULTIPLE_TRIGGERS)
                    .extracting(ValidationIssue::nodeId, ValidationIssue::path)
                    .containsExactly(tuple("t2", "nodes[2]"), tuple("t3", "nodes[3]"));
            assertThat(codes(result)).doesNotContain(DISCONNECTED_NODE, UNREACHABLE_NODE);
        }
    }

    @Nested
    class Cycles {

        @Test
        void cycleIsReportedWithItsRouteAndClosingEdge() {
            ValidationResult result = validate(List.of(trigger("t"), logger("a"), logger("b"), logger("c")),
                    edge("e1", "t", "a"), edge("e2", "a", "b"), edge("e3", "b", "c"), edge("e4", "c", "b"));

            ValidationIssue issue = only(result, CYCLE_DETECTED);
            assertThat(issue.edgeId()).isEqualTo("e4");
            assertThat(issue.nodeId()).isEqualTo("b");
            assertThat(issue.path()).isEqualTo("edges[3]");
            assertThat(issue.message()).contains("'Node b' → 'Node c' → 'Node b'");
            assertThat(result.executionOrder()).isEmpty();
        }

        @Test
        void eachIndependentCycleIsReported() {
            ValidationResult result = validate(
                    List.of(trigger("t"), logger("a"), logger("b"), condition("c"), logger("d")),
                    edge("e1", "t", "a"), edge("e2", "a", "c"), edge("e3", "c", "true", "a", "main"),
                    edge("e4", "c", "false", "d", "main"), edge("e5", "d", "b"), edge("e6", "b", "d"));

            assertThat(result.issues()).filteredOn(i -> i.code() == CYCLE_DETECTED)
                    .extracting(ValidationIssue::edgeId).containsExactly("e3", "e6");
        }

        @Test
        void cyclesOutsideTheReachablePartAreFoundToo() {
            ValidationResult result = validate(List.of(trigger("t"), logger("a"), logger("x"), logger("y")),
                    edge("e1", "t", "a"), edge("e2", "x", "y"), edge("e3", "y", "x"));

            assertThat(only(result, CYCLE_DETECTED).edgeId()).isEqualTo("e3");
            assertThat(result.issues()).filteredOn(i -> i.code() == UNREACHABLE_NODE)
                    .extracting(ValidationIssue::nodeId).containsExactly("x", "y");
        }

        @Test
        void diamondWithoutBackEdgeIsNotACycleButIsAnUnsupportedMerge() {
            ValidationResult result = validate(List.of(trigger("t"), condition("c"), logger("a"), logger("b"),
                            logger("j")),
                    edge("e1", "t", "c"), edge("e2", "c", "true", "a", "main"), edge("e3", "c", "false", "b", "main"),
                    edge("e4", "a", "j"), edge("e5", "b", "j"));

            assertThat(codes(result)).containsExactly(UNSUPPORTED_MERGE);
            assertThat(result.issues().get(0))
                    .extracting(ValidationIssue::nodeId, ValidationIssue::edgeId)
                    .containsExactly("j", "e5");
            assertThat(result.executionOrder()).containsExactly("t", "c", "a", "b", "j");
        }
    }

    @Nested
    class Connectivity {

        @Test
        void nodeWithoutAnyConnectionIsDisconnected() {
            ValidationResult result = validate(List.of(trigger("t"), logger("a"), logger("lonely")),
                    edge("e1", "t", "a"));

            assertThat(only(result, DISCONNECTED_NODE))
                    .extracting(ValidationIssue::nodeId, ValidationIssue::path, ValidationIssue::severity)
                    .containsExactly("lonely", "nodes[2]", Severity.ERROR);
            assertThat(codes(result)).doesNotContain(UNREACHABLE_NODE);
        }

        @Test
        void connectedIslandIsUnreachableFromTheTrigger() {
            ValidationResult result = validate(List.of(trigger("t"), logger("a"), logger("x"), logger("y")),
                    edge("e1", "t", "a"), edge("e2", "x", "y"));

            assertThat(result.issues()).filteredOn(i -> i.code() == UNREACHABLE_NODE)
                    .extracting(ValidationIssue::nodeId).containsExactly("x", "y");
            assertThat(result.executionOrder()).containsExactly("t", "a");
        }

        @Test
        void nodeOnlyFeedingIntoTheGraphIsUnreachable() {
            ValidationResult result = validate(List.of(trigger("t"), logger("a"), logger("feeder")),
                    edge("e1", "t", "a"), edge("e2", "feeder", "a"));

            assertThat(only(result, UNREACHABLE_NODE).nodeId()).isEqualTo("feeder");
            assertThat(only(result, UNSUPPORTED_MERGE).edgeId()).isEqualTo("e2");
        }
    }

    @Nested
    class ConditionBranches {

        @Test
        void conditionWithNoConnectedBranchIsAnError() {
            ValidationResult result = validate(List.of(trigger("t"), condition("c")), edge("e1", "t", "c"));

            ValidationIssue issue = only(result, NO_BRANCH_CONNECTED);
            assertThat(issue.nodeId()).isEqualTo("c");
            assertThat(issue.severity()).isEqualTo(Severity.ERROR);
            assertThat(result.valid()).isFalse();
        }

        @Test
        void conditionWithOneBranchWarnsAboutTheOther() {
            ValidationResult result = validate(List.of(trigger("t"), condition("c"), logger("a")),
                    edge("e1", "t", "c"), edge("e2", "c", "true", "a", "main"));

            ValidationIssue issue = only(result, BRANCH_NOT_CONNECTED);
            assertThat(issue.severity()).isEqualTo(Severity.WARNING);
            assertThat(issue.message()).contains("'false' branch");
            assertThat(result.valid()).isTrue();
        }

        @Test
        void bothBranchesIntoTheSameNodeIsAnUnsupportedMerge() {
            ValidationResult result = validate(List.of(trigger("t"), condition("c"), logger("a")),
                    edge("e1", "t", "c"), edge("e2", "c", "true", "a", "main"), edge("e3", "c", "false", "a", "main"));

            assertThat(codes(result)).containsExactly(UNSUPPORTED_MERGE);
        }
    }

    @Nested
    class Limits {

        @Test
        void tooManyNodes() {
            List<NodeDefinition> nodes = new ArrayList<>(List.of(trigger("t")));
            List<EdgeDefinition> edges = new ArrayList<>();
            String previous = "t";
            for (int i = 0; i < WorkflowDefinition.MAX_NODES; i++) {
                nodes.add(new NodeDefinition("n" + i, "core.logger", 1, "N" + i, new NodePosition(0, 0),
                        validConfig("core.logger")));
                edges.add(edge("e" + i, previous, "n" + i));
                previous = "n" + i;
            }

            ValidationResult result = VALIDATOR.validate(graph(nodes, edges));

            assertThat(codes(result)).containsExactly(TOO_MANY_NODES);
            assertThat(result.issues().get(0).message()).contains("501", "500");
        }

        @Test
        void tooManyEdges() {
            List<EdgeDefinition> edges = new ArrayList<>();
            for (int i = 0; i <= WorkflowDefinition.MAX_EDGES; i++) {
                edges.add(edge("e" + i, "t", "missing" + i));
            }

            ValidationResult result = VALIDATOR.validate(graph(List.of(trigger("t")), edges));

            assertThat(codes(result).get(0)).isEqualTo(TOO_MANY_EDGES);
        }

        @Test
        void tooManyOutgoingEdgesFromOneHandle() {
            List<NodeDefinition> nodes = new ArrayList<>(List.of(trigger("t")));
            List<EdgeDefinition> edges = new ArrayList<>();
            for (int i = 0; i <= GraphValidator.MAX_OUTGOING_EDGES_PER_HANDLE; i++) {
                nodes.add(new NodeDefinition("n" + i, "core.logger", 1, "N" + i, new NodePosition(0, 0),
                        validConfig("core.logger")));
                edges.add(edge("e" + i, "t", "n" + i));
            }

            ValidationResult result = VALIDATOR.validate(graph(nodes, edges));

            assertThat(only(result, TOO_MANY_OUTGOING_EDGES))
                    .extracting(ValidationIssue::nodeId, ValidationIssue::path).containsExactly("t", "nodes[0]");
        }
    }

    @Nested
    class Configuration {

        @Test
        void missingRequiredSettingPointsAtTheField() {
            ValidationResult result = validate(List.of(trigger("t"), node("l", "core.logger", Map.of())),
                    edge("e1", "t", "l"));

            ValidationIssue issue = only(result, CONFIGURATION_INVALID);
            assertThat(issue.nodeId()).isEqualTo("l");
            assertThat(issue.path()).isEqualTo("nodes[1].configuration.message");
            assertThat(issue.message()).isEqualTo("'Node l': message: is required");
        }

        @Test
        void eachInvalidFieldIsReportedWithItsPath() {
            Map<String, Object> http = Map.of("method", "GET", "url", "ftp://x", "headers",
                    List.of(Map.of("name", "Bad Name", "value", "v")), "query", List.of(), "timeoutSeconds", 0);
            ValidationResult result = validate(List.of(trigger("t"), node("h", "core.httpRequest", http)),
                    edge("e1", "t", "h"));

            assertThat(result.issues()).filteredOn(i -> i.code() == CONFIGURATION_INVALID)
                    .extracting(ValidationIssue::path)
                    .containsExactlyInAnyOrder("nodes[1].configuration.url", "nodes[1].configuration.headers[0].name",
                            "nodes[1].configuration.timeoutSeconds");
            assertThat(result.issues()).filteredOn(i -> i.path().endsWith(".url")).first()
                    .extracting(ValidationIssue::message).asString().contains("http://");
        }

        @Test
        void unsafeExpressionsAreRejected() {
            Map<String, Object> config = Map.of("message", "x {{ process.exit() }}");
            ValidationResult result = validate(List.of(trigger("t"), node("l", "core.logger", config)),
                    edge("e1", "t", "l"));

            assertThat(only(result, CONFIGURATION_INVALID).message()).contains("{{ path.to.value }}");
        }

        @Test
        void getRequestWithBodyIsRejected() {
            Map<String, Object> http = Map.of("method", "GET", "url", "https://x.io", "headers", List.of(),
                    "query", List.of(), "timeoutSeconds", 30, "body", Map.of("a", 1));
            ValidationResult result = validate(List.of(trigger("t"), node("h", "core.httpRequest", http)),
                    edge("e1", "t", "h"));

            assertThat(only(result, CONFIGURATION_INVALID).message()).contains("cannot have a body");
        }

        @Test
        void uniquenessRulesBeyondJsonSchema() {
            Map<String, Object> vars = Map.of("variables", List.of(Map.of("name", "a", "value", "1"),
                    Map.of("name", "a", "value", "2")));
            Map<String, Object> http = Map.of("method", "GET", "url", "https://x.io", "query", List.of(),
                    "timeoutSeconds", 30, "headers", List.of(Map.of("name", "Accept", "value", "1"),
                            Map.of("name", "accept", "value", "2")));
            ValidationResult result = validate(
                    List.of(trigger("t"), node("v", "core.setVariables", vars), node("h", "core.httpRequest", http)),
                    edge("e1", "t", "v"), edge("e2", "v", "h"));

            assertThat(result.issues()).filteredOn(i -> i.code() == CONFIGURATION_INVALID)
                    .extracting(ValidationIssue::nodeId, ValidationIssue::path)
                    .containsExactly(tuple("v", "nodes[1].configuration.variables[1].name"),
                            tuple("h", "nodes[2].configuration.headers[1].name"));
        }

        @Test
        void unknownSettingIsReported() {
            ValidationResult result = validate(List.of(trigger("t"),
                    node("l", "core.logger", Map.of("message", "hi", "level", "info"))), edge("e1", "t", "l"));

            assertThat(only(result, CONFIGURATION_INVALID))
                    .extracting(ValidationIssue::path, ValidationIssue::message)
                    .containsExactly("nodes[1].configuration.level", "'Node l': level: is not a supported setting");
        }
    }

    @Test
    void issuesComeInPassOrderThenDefinitionOrder() {
        // Passes: size, nodes, edges (all edge rules per edge, in edge order), counts, triggers, branches, cycles,
        // connectivity, configuration.
        ValidationResult result = validate(List.of(logger("b"), logger("a"), logger("a")),
                edge("e1", "b", "x"), edge("e1", "b", "a"));

        assertThat(codes(result)).containsExactly(DUPLICATE_NODE_ID, EDGE_TARGET_NOT_FOUND, DUPLICATE_EDGE_ID, NO_TRIGGER);
    }
}
