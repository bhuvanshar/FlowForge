package com.flowforge.api;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flowforge.application.HealthService;
import com.flowforge.application.HealthService.HealthReport;
import com.flowforge.config.AppConfig;
import com.flowforge.domain.ComponentStatus;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(HealthController.class)
@Import(AppConfig.class)
class HealthControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    HealthService healthService;

    @Test
    void returns200WhenAllComponentsUp() throws Exception {
        given(healthService.report()).willReturn(
                new HealthReport(ComponentStatus.UP, Map.of("database", ComponentStatus.UP), Instant.EPOCH));

        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.database").value("UP"))
                .andExpect(jsonPath("$.version").exists());
    }

    @Test
    void returns503WhenAComponentIsDown() throws Exception {
        given(healthService.report()).willReturn(
                new HealthReport(ComponentStatus.DOWN, Map.of("database", ComponentStatus.DOWN), Instant.EPOCH));

        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.components.database").value("DOWN"));
    }
}
