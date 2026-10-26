package com.flowforge.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.support.PostgresIntegrationTest;
import com.flowforge.support.Workflows;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** Trigger registrations follow workflow status and definition: activation, saving, deactivation, archiving. */
class TriggerLifecycleIT extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE workflows CASCADE");
    }

    private JsonNode create(String definition) throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/workflows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Triggered\",\"definition\":" + definition + "}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode save(JsonNode workflow, String status, String definition, int expectedStatus) throws Exception {
        String body = "{\"name\":\"Triggered\",\"status\":" + (status == null ? "null" : "\"" + status + "\"")
                + ",\"definition\":" + definition + ",\"lockVersion\":" + workflow.get("lockVersion").asLong() + "}";
        return json.readTree(mvc.perform(put("/api/v1/workflows/" + workflow.get("id").asText())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString());
    }

    private JsonNode lifecycle(String id, String action, int expectedStatus) throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/workflows/" + id + "/" + action))
                .andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString());
    }

    private JsonNode triggers(String id) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/workflows/" + id + "/triggers")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private String secret(String id) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/workflows/" + id + "/triggers/webhook/secret"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString()).get("secret").asText();
    }

    @Test
    void webhookIsRegisteredOnSaveAndActiveExactlyWhileTheWorkflowIsActive() throws Exception {
        String id = create(Workflows.webhook("POST", "signature", "body")).get("id").asText();

        JsonNode draft = triggers(id);
        assertThat(draft.get("triggerType").asText()).isEqualTo("core.webhookTrigger");
        assertThat(draft.hasNonNull("schedule")).isFalse();
        JsonNode webhook = draft.get("webhook");
        String webhookId = webhook.get("webhookId").asText();
        assertThat(webhookId).matches("[A-Za-z0-9_-]{32}");
        assertThat(webhook.get("path").asText()).isEqualTo("/api/v1/hooks/" + webhookId);
        assertThat(webhook.get("active").asBoolean()).isFalse();
        assertThat(webhook.get("method").asText()).isEqualTo("POST");
        assertThat(webhook.get("authentication").asText()).isEqualTo("signature");
        assertThat(webhook.has("secret")).isFalse();
        String secret = secret(id);
        assertThat(secret).hasSize(43);
        assertThat(webhook.get("secretHint").asText()).isEqualTo("…" + secret.substring(39));

        assertThat(lifecycle(id, "activate", 200).get("status").asText()).isEqualTo("ACTIVE");
        assertThat(triggers(id).get("webhook").get("active").asBoolean()).isTrue();
        lifecycle(id, "activate", 200); // idempotent

        assertThat(lifecycle(id, "deactivate", 200).get("status").asText()).isEqualTo("INACTIVE");
        assertThat(triggers(id).get("webhook").get("active").asBoolean()).isFalse();
        lifecycle(id, "deactivate", 200);

        lifecycle(id, "activate", 200);
        JsonNode again = triggers(id).get("webhook");
        assertThat(again.get("webhookId").asText()).isEqualTo(webhookId);
        assertThat(secret(id)).isEqualTo(secret);
    }

    @Test
    void savingAnActiveWorkflowUpdatesTheRegistrationAndMustKeepTheGraphValid() throws Exception {
        JsonNode workflow = create(Workflows.webhook("POST", "signature", "body"));
        String id = workflow.get("id").asText();
        workflow = lifecycle(id, "activate", 200);
        String webhookId = triggers(id).get("webhook").get("webhookId").asText();

        workflow = save(workflow, null, Workflows.webhook("PUT", "token", "request"), 200);
        JsonNode webhook = triggers(id).get("webhook");
        assertThat(webhook.get("webhookId").asText()).isEqualTo(webhookId);
        assertThat(webhook.get("method").asText()).isEqualTo("PUT");
        assertThat(webhook.get("authentication").asText()).isEqualTo("token");
        assertThat(webhook.get("inputMode").asText()).isEqualTo("request");
        assertThat(webhook.get("active").asBoolean()).isTrue();

        // An incomplete graph cannot be saved while active (it would fire automatically) ...
        String incomplete = Workflows.definition(java.util.List.of(), java.util.List.of());
        JsonNode problem = save(workflow, null, incomplete, 422);
        assertThat(problem.get("detail").asText()).contains("deactivate it");
        assertThat(triggers(id).get("webhook").get("active").asBoolean()).isTrue();
        // ... but can be saved together with deactivation.
        save(workflow, "INACTIVE", incomplete, 200);
        assertThat(triggers(id).hasNonNull("webhook")).isFalse();
        assertThat(jdbc.queryForObject("select active from webhook_triggers where workflow_id = ?::uuid",
                Boolean.class, id)).isFalse();
    }

    @Test
    void activationRequiresAValidGraphAndAuthenticatedWebhooks() throws Exception {
        String invalid = create(Workflows.definition(java.util.List.of(), java.util.List.of())).get("id").asText();
        JsonNode problem = lifecycle(invalid, "activate", 422);
        assertThat(problem.get("type").asText()).endsWith("/workflow-invalid");

        String unauthenticated = create(Workflows.webhook("POST", "none", "body")).get("id").asText();
        problem = lifecycle(unauthenticated, "activate", 422);
        assertThat(problem.get("issues").toString()).contains("authentication")
                .contains("unauthenticated webhooks are disabled");
        assertThat(triggers(unauthenticated).get("webhook").get("active").asBoolean()).isFalse();
    }

    @Test
    void scheduleIsRegisteredOnActivationAndStopsOnDeactivation() throws Exception {
        String id = create(Workflows.schedule("0 9 * * *", "Europe/Berlin", "skip")).get("id").asText();
        JsonNode inactive = triggers(id).get("schedule");
        assertThat(inactive.get("active").asBoolean()).isFalse();
        assertThat(inactive.hasNonNull("nextFireAt")).isFalse();
        assertThat(inactive.get("cron").asText()).isEqualTo("0 9 * * *");
        assertThat(jdbc.queryForObject("select count(*) from schedule_triggers", Integer.class)).isZero();

        Instant before = Instant.now();
        lifecycle(id, "activate", 200);
        JsonNode active = triggers(id).get("schedule");
        assertThat(active.get("active").asBoolean()).isTrue();
        Instant next = Instant.parse(active.get("nextFireAt").asText());
        assertThat(next).isAfter(before).isBefore(before.plusSeconds(25 * 3600));
        ZonedDateTime local = next.atZone(ZoneId.of("Europe/Berlin"));
        assertThat(local.getHour()).isEqualTo(9);
        assertThat(local.getMinute()).isZero();

        lifecycle(id, "deactivate", 200);
        JsonNode stopped = triggers(id).get("schedule");
        assertThat(stopped.get("active").asBoolean()).isFalse();
        assertThat(stopped.hasNonNull("nextFireAt")).isFalse();
    }

    @Test
    void savingWithoutChangingTheScheduleKeepsTheNextOccurrence() throws Exception {
        JsonNode workflow = create(Workflows.schedule("0 9 * * *", "UTC", "skip"));
        String id = workflow.get("id").asText();
        workflow = lifecycle(id, "activate", 200);
        jdbc.update("update schedule_triggers set next_fire_at = '2030-01-01T09:00:00Z' where workflow_id = ?::uuid",
                java.util.UUID.fromString(id));

        workflow = save(workflow, null, Workflows.schedule("0 9 * * *", "UTC", "runOnce"), 200);
        JsonNode same = triggers(id).get("schedule");
        assertThat(same.get("nextFireAt").asText()).isEqualTo("2030-01-01T09:00:00Z");
        assertThat(same.get("missedRunPolicy").asText()).isEqualTo("runOnce");

        save(workflow, null, Workflows.schedule("30 9 * * *", "UTC", "runOnce"), 200);
        Instant recomputed = Instant.parse(triggers(id).get("schedule").get("nextFireAt").asText());
        assertThat(recomputed).isBefore(Instant.parse("2030-01-01T00:00:00Z"));
        assertThat(recomputed.atZone(ZoneId.of("UTC")).getMinute()).isEqualTo(30);
    }

    @Test
    void replacingTheTriggerOrArchivingDeactivatesRegistrations() throws Exception {
        JsonNode workflow = create(Workflows.webhook("POST", "token", "body"));
        String id = workflow.get("id").asText();
        workflow = lifecycle(id, "activate", 200);

        workflow = save(workflow, null, Workflows.schedule("0 * * * *", "UTC", "skip"), 200);
        JsonNode switched = triggers(id);
        assertThat(switched.get("triggerType").asText()).isEqualTo("core.scheduleTrigger");
        assertThat(switched.hasNonNull("webhook")).isFalse();
        assertThat(switched.get("schedule").get("active").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("select active from webhook_triggers where workflow_id = ?::uuid",
                Boolean.class, id)).isFalse();

        mvc.perform(delete("/api/v1/workflows/" + id)).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select active from schedule_triggers where workflow_id = ?::uuid",
                Boolean.class, id)).isFalse();
        lifecycle(id, "activate", 409);

        mvc.perform(delete("/api/v1/workflows/" + id).param("permanent", "true")).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select count(*) from webhook_triggers", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from schedule_triggers", Integer.class)).isZero();
    }

    @Test
    void secretsCanBeRotatedAndDuplicatesGetTheirOwnWebhook() throws Exception {
        String id = create(Workflows.webhook("POST", "signature", "body")).get("id").asText();
        String original = secret(id);
        String rotated = json.readTree(mvc.perform(post("/api/v1/workflows/" + id + "/triggers/webhook/secret/rotate"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString()).get("secret").asText();
        assertThat(rotated).isNotEqualTo(original).isEqualTo(secret(id));

        String copy = json.readTree(mvc.perform(post("/api/v1/workflows/" + id + "/duplicate"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
        assertThat(triggers(copy).get("webhook").get("webhookId").asText())
                .isNotEqualTo(triggers(id).get("webhook").get("webhookId").asText());
        assertThat(secret(copy)).isNotEqualTo(rotated);
    }

    @Test
    void manualWorkflowsHaveNoRegistrations() throws Exception {
        String id = create(Workflows.manual()).get("id").asText();
        lifecycle(id, "activate", 200);
        JsonNode view = triggers(id);
        assertThat(view.get("triggerType").asText()).isEqualTo("core.manualTrigger");
        assertThat(view.hasNonNull("webhook")).isFalse();
        assertThat(view.hasNonNull("schedule")).isFalse();
        mvc.perform(get("/api/v1/workflows/" + id + "/triggers/webhook/secret"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("has no webhook trigger")));
        mvc.perform(get("/api/v1/workflows/" + java.util.UUID.randomUUID() + "/triggers"))
                .andExpect(status().isNotFound());
    }
}
