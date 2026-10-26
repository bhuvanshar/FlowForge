package com.flowforge.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.application.execution.ExecutionStateManager;
import com.flowforge.application.trigger.WebhookSecretStore;
import com.flowforge.support.PostgresIntegrationTest;
import com.flowforge.support.TestKeys;
import com.flowforge.support.Workflows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** Webhook secrets at rest: encrypted with the credential keyring, legacy plaintext migrated, re-encrypt, tampering. */
class WebhookSecretEncryptionIT extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    WebhookSecretStore store;

    @Autowired
    ExecutionStateManager states;

    @BeforeEach
    void clean() throws InterruptedException {
        long until = System.currentTimeMillis() + 5_000;
        while (states.activeCount() > 0 && System.currentTimeMillis() < until) {
            Thread.sleep(10);
        }
        jdbc.execute("TRUNCATE workflows CASCADE");
    }

    private record Hook(String workflowId, String url) {
    }

    private JsonNode read(String url) throws Exception {
        return json.readTree(mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString());
    }

    private Hook activeTokenHook() throws Exception {
        String id = json.readTree(mvc.perform(post("/api/v1/workflows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Hook\",\"definition\":" + Workflows.webhook("POST", "token", "body") + "}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
        mvc.perform(post("/api/v1/workflows/" + id + "/activate")).andExpect(status().isOk());
        return new Hook(id, read("/api/v1/workflows/" + id + "/triggers").get("webhook").get("path").asText());
    }

    private String secret(Hook hook) throws Exception {
        return read("/api/v1/workflows/" + hook.workflowId() + "/triggers/webhook/secret").get("secret").asText();
    }

    private int deliver(Hook hook, String token) throws Exception {
        return mvc.perform(post(hook.url()).header("X-FlowForge-Token", token)).andReturn().getResponse().getStatus();
    }

    private Integer rowsContaining(String text) {
        return jdbc.queryForObject("select count(*) from webhook_triggers t where t::text like ? or "
                + "position(convert_to(?, 'UTF8') in coalesce(secret_ciphertext, ''::bytea)) > 0", Integer.class,
                "%" + text + "%", text);
    }

    /** Puts the row back into the pre-Module-12 shape: plaintext secret, no ciphertext. */
    private void makeLegacy(Hook hook, String plaintext) {
        jdbc.update("update webhook_triggers set secret = ?, secret_key_id = null, secret_nonce = null, "
                + "secret_ciphertext = null where workflow_id = ?::uuid", plaintext, hook.workflowId());
    }

    @Test
    void newSecretsAreStoredOnlyAsCiphertextAndStillAuthenticate() throws Exception {
        Hook hook = activeTokenHook();
        String secret = secret(hook);

        assertThat(jdbc.queryForObject("select secret is null and secret_key_id = ? and octet_length(secret_nonce) = 12 "
                + "from webhook_triggers", Boolean.class, TestKeys.KEY_ID)).isTrue();
        assertThat(rowsContaining(secret)).isZero();
        assertThat(read("/api/v1/workflows/" + hook.workflowId() + "/triggers").get("webhook").get("secretHint").asText())
                .isEqualTo("…" + secret.substring(secret.length() - 4));
        assertThat(deliver(hook, secret)).isEqualTo(202);
        assertThat(deliver(hook, "wrong-token-000000")).isEqualTo(401);

        String rotated = json.readTree(mvc.perform(post("/api/v1/workflows/" + hook.workflowId()
                + "/triggers/webhook/secret/rotate")).andReturn().getResponse().getContentAsString()).get("secret").asText();
        assertThat(rowsContaining(rotated)).isZero();
        assertThat(deliver(hook, secret)).isEqualTo(401);
        assertThat(deliver(hook, rotated)).isEqualTo(202);
    }

    @Test
    void legacyPlaintextSecretsKeepWorkingAndAreEncryptedOnStartup() throws Exception {
        Hook hook = activeTokenHook();
        String legacy = "legacy-plaintext-secret-0123456789";
        makeLegacy(hook, legacy);

        assertThat(read("/api/v1/credentials/status").get("webhookSecretsToEncrypt").asInt()).isEqualTo(1);
        assertThat(secret(hook)).isEqualTo(legacy);
        assertThat(deliver(hook, legacy)).isEqualTo(202);

        store.encryptLegacyAtStartup(); // what the next start of the application does

        assertThat(rowsContaining(legacy)).isZero();
        assertThat(jdbc.queryForObject("select secret is null from webhook_triggers", Boolean.class)).isTrue();
        assertThat(secret(hook)).isEqualTo(legacy); // same secret: senders need no change
        assertThat(deliver(hook, legacy)).isEqualTo(202);
        assertThat(read("/api/v1/credentials/status").get("webhookSecretsToEncrypt").asInt()).isZero();
    }

    @Test
    void reEncryptAlsoEncryptsPlaintextWebhookSecrets() throws Exception {
        Hook hook = activeTokenHook();
        makeLegacy(hook, "legacy-plaintext-secret-9876543210");

        mvc.perform(post("/api/v1/credentials/re-encrypt")).andExpect(status().isOk())
                .andExpect(jsonPath("$.webhookSecrets").value(1)).andExpect(jsonPath("$.reEncrypted").value(0));

        assertThat(rowsContaining("legacy-plaintext-secret-9876543210")).isZero();
        assertThat(deliver(hook, "legacy-plaintext-secret-9876543210")).isEqualTo(202);
        mvc.perform(post("/api/v1/credentials/re-encrypt")).andExpect(jsonPath("$.webhookSecrets").value(0));
    }

    @Test
    void tamperedCiphertextFailsClosedWithoutDetails() throws Exception {
        Hook hook = activeTokenHook();
        String secret = secret(hook);
        jdbc.update("update webhook_triggers set secret_ciphertext = "
                + "overlay(secret_ciphertext placing '\\xff'::bytea from 3 for 1)");

        String response = mvc.perform(post(hook.url()).header("X-FlowForge-Token", secret))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("https://flowforge.local/problems/webhook-unavailable"))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(secret);
        mvc.perform(get("/api/v1/workflows/" + hook.workflowId() + "/triggers/webhook/secret"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("https://flowforge.local/problems/secret-unavailable"));
        assertThat(read("/api/v1/workflows/" + hook.workflowId() + "/triggers").get("webhook").get("secretHint").asText())
                .isEqualTo("…????");
        assertThat(jdbc.queryForObject("select count(*) from workflow_executions", Integer.class)).isZero();
    }

    @Test
    void theDatabaseRefusesRowsWithBothOrNeitherSecretForm() throws Exception {
        activeTokenHook();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                        "update webhook_triggers set secret_ciphertext = null"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                        "update webhook_triggers set secret = 'plaintext-next-to-ciphertext'"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
