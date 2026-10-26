package com.flowforge.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.support.PostgresIntegrationTest;
import com.flowforge.support.Workflows;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The background poller in a real application context: an active per-second schedule runs repeatedly, each occurrence
 * once, and stops on deactivation. Own context (closed afterwards) so the poller never sees other tests' schedules.
 */
@TestPropertySource(properties = {"flowforge.triggers.scheduler-enabled=true",
        "flowforge.triggers.scheduler-poll-interval=200ms"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SchedulerPollingIT extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    private List<String> succeeded(String workflowId) {
        return jdbc.queryForList("select scheduled_for::text from workflow_executions where workflow_id = ?::uuid "
                + "and trigger_type = 'SCHEDULE' and status = 'SUCCEEDED'", String.class, workflowId);
    }

    private int runs(String workflowId) {
        return jdbc.queryForObject("select count(*) from workflow_executions where workflow_id = ?::uuid",
                Integer.class, workflowId);
    }

    @Test
    void anActiveScheduleFiresInTheBackgroundUntilDeactivated() throws Exception {
        jdbc.execute("TRUNCATE workflows CASCADE");
        String id = json.readTree(mvc.perform(post("/api/v1/workflows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Every second\",\"definition\":"
                                + Workflows.schedule("* * * * * *", "UTC", "skip") + "}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
        mvc.perform(post("/api/v1/workflows/" + id + "/activate")).andExpect(status().isOk());

        long until = System.currentTimeMillis() + 15_000;
        while (succeeded(id).size() < 3) {
            assertThat(System.currentTimeMillis()).as("scheduled runs within 15 s").isLessThan(until);
            Thread.sleep(100);
        }
        List<String> occurrences = succeeded(id);
        assertThat(occurrences).doesNotHaveDuplicates();

        mvc.perform(post("/api/v1/workflows/" + id + "/deactivate")).andExpect(status().isOk());
        Thread.sleep(500); // a poll already in progress may still start one run
        int afterDeactivation = runs(id);
        Thread.sleep(1_500);
        assertThat(runs(id)).isEqualTo(afterDeactivation);
    }
}
