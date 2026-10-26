package com.flowforge.application.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowforge.infrastructure.nodes.ManualTriggerExecutor;
import java.util.List;
import org.junit.jupiter.api.Test;

class NodeExecutorRegistryTest {

    @Test
    void findsExecutorsByTypeAndReportsMissingTypes() {
        NodeExecutorRegistry registry = new NodeExecutorRegistry(List.of(new ManualTriggerExecutor()));

        assertThat(registry.find("core.manualTrigger")).isPresent();
        assertThat(registry.find("core.logger")).isEmpty();
        assertThat(registry.supportedTypes()).containsExactly("core.manualTrigger");
        assertThat(registry.missing(List.of("core.manualTrigger", "core.logger", "core.httpRequest", "core.logger")))
                .containsExactly("core.httpRequest", "core.logger");
    }

    @Test
    void twoExecutorsForOneTypeAreAStartupError() {
        assertThatThrownBy(() -> new NodeExecutorRegistry(List.of(new ManualTriggerExecutor(), new ManualTriggerExecutor())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("core.manualTrigger");
    }
}
