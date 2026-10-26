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
import com.flowforge.support.Credentials;
import com.flowforge.support.PostgresIntegrationTest;
import com.flowforge.support.Workflows;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Credential management API: write-only secrets, encryption at rest, rotation, usage, ownership. */
class CredentialApiIT extends PostgresIntegrationTest {

    private static final String TOKEN = "tok_live_SuperSecretValue_42";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE workflows CASCADE");
        jdbc.execute("TRUNCATE credentials");
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String body) throws Exception {
        return mvc.perform(body == null ? request : request.contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private JsonNode create(String body) throws Exception {
        return json.readTree(send(post("/api/v1/credentials"), body).andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/v1/credentials/")))
                .andReturn().getResponse().getContentAsString());
    }

    private String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }

    @Test
    void secretsAreWriteOnlyAndEncryptedAtRest() throws Exception {
        JsonNode created = create(Credentials.bearer("Payments API", TOKEN, "api.payments.example", "*.payments.example"));
        String id = created.get("id").asText();

        assertThat(created.get("type").asText()).isEqualTo("httpBearer");
        assertThat(created.get("secretFields")).containsExactly(json.getNodeFactory().textNode("token"));
        assertThat(created.get("secretVersion").asInt()).isEqualTo(1);
        assertThat(created.has("secret")).isFalse();
        // No read API ever returns the secret.
        for (String url : List.of("/api/v1/credentials", "/api/v1/credentials/" + id, "/api/v1/credentials/" + id + "/usage",
                "/api/v1/credentials/status")) {
            String response = body(mvc.perform(get(url)).andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store")));
            assertThat(response).as(url).doesNotContain(TOKEN);
        }
        assertThat(created.toString()).doesNotContain(TOKEN);

        // At rest: only AES-GCM ciphertext; the plaintext is nowhere in the row.
        assertThat(jdbc.queryForObject("select count(*) from credentials where position(convert_to(?, 'UTF8') in "
                + "secret_ciphertext) > 0", Integer.class, TOKEN)).isZero();
        assertThat(jdbc.queryForObject("select row_to_json(c)::text from credentials c", String.class))
                .doesNotContain(TOKEN).doesNotContain(com.flowforge.support.TestKeys.KEY);
        assertThat(jdbc.queryForObject("select key_id from credentials", String.class)).isEqualTo("test-1");
        assertThat(jdbc.queryForObject("select octet_length(secret_nonce) from credentials", Integer.class)).isEqualTo(12);

        JsonNode status = json.readTree(body(mvc.perform(get("/api/v1/credentials/status"))));
        assertThat(status.get("available").asBoolean()).isTrue();
        assertThat(status.get("activeKeyId").asText()).isEqualTo("test-1");
    }

    @Test
    void updateChangesDetailsButNeverTheSecretAndRotationReplacesIt() throws Exception {
        JsonNode created = create(Credentials.basic("CRM", "ada", "first-password-1", "crm.example"));
        String id = created.get("id").asText();
        byte[] firstCiphertext = jdbc.queryForObject("select secret_ciphertext from credentials", byte[].class);

        JsonNode updated = json.readTree(body(send(put("/api/v1/credentials/" + id), "{\"name\":\"CRM (prod)\","
                + "\"allowedHosts\":[\"CRM.example\",\"api.crm.example\"],\"fields\":{\"username\":\"grace\"},"
                + "\"lockVersion\":" + created.get("lockVersion").asLong() + "}").andExpect(status().isOk())));
        assertThat(updated.get("name").asText()).isEqualTo("CRM (prod)");
        assertThat(updated.get("allowedHosts").toString()).isEqualTo("[\"crm.example\",\"api.crm.example\"]");
        assertThat(updated.get("fields").get("username").asText()).isEqualTo("grace");
        assertThat(jdbc.queryForObject("select secret_ciphertext from credentials", byte[].class)).isEqualTo(firstCiphertext);
        // Stale lock version, unknown properties (e.g. trying to sneak a secret into PUT): refused.
        send(put("/api/v1/credentials/" + id), "{\"name\":\"x\",\"allowedHosts\":[\"a.b\"],\"fields\":{\"username\":\"a\"},"
                + "\"lockVersion\":0}").andExpect(status().isConflict());
        send(put("/api/v1/credentials/" + id), "{\"name\":\"x\",\"allowedHosts\":[\"a.b\"],\"secret\":{\"password\":\"x\"},"
                + "\"lockVersion\":1}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Unknown property 'secret'."));

        JsonNode rotated = json.readTree(body(send(post("/api/v1/credentials/" + id + "/rotate"),
                "{\"secret\":{\"password\":\"second-password-2\"}}").andExpect(status().isOk())));
        assertThat(rotated.get("secretVersion").asInt()).isEqualTo(2);
        assertThat(rotated.get("rotatedAt").asText()).isNotBlank();
        assertThat(rotated.toString()).doesNotContain("second-password-2");
        assertThat(jdbc.queryForObject("select secret_ciphertext from credentials", byte[].class))
                .isNotEqualTo(firstCiphertext);
        send(post("/api/v1/credentials/" + id + "/rotate"), "{\"secret\":{\"password\":\"short\"}}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("secret.password"));
    }

    @Test
    void invalidInputIsRejectedWithFieldErrorsAndWithoutEchoingSecrets() throws Exception {
        String response = body(send(post("/api/v1/credentials"), "{\"type\":\"httpHeader\",\"name\":\"K\","
                + "\"allowedHosts\":[\"http://evil/\"],\"fields\":{\"headerName\":\"Content-Length\"},"
                + "\"secret\":{\"value\":\"" + TOKEN + "\",\"extra\":\"" + TOKEN + "\"}}").andExpect(status().isBadRequest()));
        assertThat(response).doesNotContain(TOKEN).contains("allowedHosts[0]").contains("fields.headerName")
                .contains("secret.extra");

        String malformed = body(send(post("/api/v1/credentials"), "{\"type\":\"httpBearer\",\"secret\":{\"token\":\""
                + TOKEN + "\"").andExpect(status().isBadRequest()));
        assertThat(malformed).doesNotContain(TOKEN);

        create(Credentials.bearer("Dup", TOKEN, "a.example"));
        send(post("/api/v1/credentials"), Credentials.bearer("dup", TOKEN, "a.example")).andExpect(status().isConflict());
        mvc.perform(get("/api/v1/credentials/not-a-uuid")).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from credentials", Integer.class)).isEqualTo(1);
    }

    @Test
    void credentialsInUseCannotBeDeleted() throws Exception {
        String id = create(Credentials.bearer("Used", TOKEN, "localhost")).get("id").asText();
        String definition = Workflows.definition(List.of(Workflows.node("t", "core.manualTrigger", "{}"),
                Workflows.node("call", "core.httpRequest", "{\"method\":\"GET\",\"url\":\"https://localhost/x\","
                        + "\"headers\":[],\"query\":[],\"timeoutSeconds\":5,\"credentialId\":\"" + id + "\"}")),
                List.of(Workflows.edge("t", "call")));
        String workflowId = json.readTree(body(send(post("/api/v1/workflows"), "{\"name\":\"Uses it\",\"definition\":"
                + definition + "}").andExpect(status().isCreated()))).get("id").asText();

        JsonNode usage = json.readTree(body(mvc.perform(get("/api/v1/credentials/" + id + "/usage"))));
        assertThat(usage.get("workflows").get(0).get("id").asText()).isEqualTo(workflowId);
        send(delete("/api/v1/credentials/" + id), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://flowforge.local/problems/credential-in-use"))
                .andExpect(jsonPath("$.workflows[0].name").value("Uses it"));

        mvc.perform(delete("/api/v1/workflows/" + workflowId)).andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/workflows/" + workflowId).param("permanent", "true"))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/credentials/" + id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/credentials/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void credentialsOfAnotherOwnerAreInvisible() throws Exception {
        String mine = create(Credentials.bearer("Mine", TOKEN, "a.example")).get("id").asText();
        String theirs = create(Credentials.bearer("Theirs", TOKEN, "a.example")).get("id").asText();
        jdbc.update("update credentials set owner_id = 'someone-else' where id = ?::uuid", theirs);

        JsonNode list = json.readTree(body(mvc.perform(get("/api/v1/credentials"))));
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("id").asText()).isEqualTo(mine);
        mvc.perform(get("/api/v1/credentials/" + theirs)).andExpect(status().isNotFound());
        send(put("/api/v1/credentials/" + theirs), "{\"name\":\"x\",\"allowedHosts\":[\"a.b\"],\"lockVersion\":0}")
                .andExpect(status().isNotFound());
        send(post("/api/v1/credentials/" + theirs + "/rotate"), "{\"secret\":{\"token\":\"tok_new_12345678\"}}")
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/credentials/" + theirs)).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select count(*) from credentials", Integer.class)).isEqualTo(2);
    }

    @Test
    void reEncryptionIsANoOpWhenEveryCredentialUsesTheActiveKey() throws Exception {
        create(Credentials.bearer("One", TOKEN, "a.example"));
        mvc.perform(post("/api/v1/credentials/re-encrypt")).andExpect(status().isOk())
                .andExpect(jsonPath("$.reEncrypted").value(0)).andExpect(jsonPath("$.activeKeyId").value("test-1"));
        assertThat(UUID.randomUUID()).isNotNull();
    }
}
