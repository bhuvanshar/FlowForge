package com.flowforge.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.domain.execution.ExecutionStatus;
import com.flowforge.support.Credentials;
import com.flowforge.support.PostgresIntegrationTest;
import com.flowforge.support.Workflows;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * OPTIONAL live test against a real OpenAI-compatible provider. Skipped unless {@code FLOWFORGE_LIVE_AI_API_KEY} is
 * set; it makes one tiny request (a few tokens) and costs next to nothing. Never runs in CI by default.
 *
 * <pre>
 * FLOWFORGE_LIVE_AI_API_KEY=sk-...                               (required)
 * FLOWFORGE_LIVE_AI_BASE_URL=https://api.openai.com/v1           (default)
 * FLOWFORGE_LIVE_AI_MODEL=gpt-4o-mini                            (default)
 * FLOWFORGE_LIVE_AI_HOST=api.openai.com                          (host of the base URL; default)
 * ./mvnw verify -Dit.test=AiLiveIT
 * </pre>
 * For a local model (Ollama): base URL {@code http://localhost:11434/v1}, host {@code localhost}, any non-empty key.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "FLOWFORGE_LIVE_AI_API_KEY", matches = ".+")
@TestPropertySource(properties = {"flowforge.http.allowed-hosts=localhost,127.0.0.1,${FLOWFORGE_LIVE_AI_HOST:api.openai.com}",
        "flowforge.ai.retry-base-delay=500ms", "flowforge.ai.retry-max-delay=4s"})
class AiLiveIT extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    private JsonNode call(MockHttpServletRequestBuilder request, String body, int expected) throws Exception {
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return json.readTree(mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse()
                .getContentAsString());
    }

    @Test
    void aRealModelAnswersWithValidatedJsonAndTheKeyStaysSecret() throws Exception {
        String key = System.getenv("FLOWFORGE_LIVE_AI_API_KEY");
        String baseUrl = System.getenv().getOrDefault("FLOWFORGE_LIVE_AI_BASE_URL", "https://api.openai.com/v1");
        String model = System.getenv().getOrDefault("FLOWFORGE_LIVE_AI_MODEL", "gpt-4o-mini");
        String host = URI.create(baseUrl).getHost();
        jdbc.execute("TRUNCATE workflows CASCADE");
        jdbc.execute("TRUNCATE credentials");

        String credentialId = call(post("/api/v1/credentials"), Credentials.bearer("Live AI", key, host), 201).get("id").asText();
        String definition = Workflows.definition(List.of(
                Workflows.node("t", "core.manualTrigger", "{}"),
                Workflows.node("ai", "core.aiModel", "{\"provider\":\"openaiCompatible\",\"baseUrl\":\"" + baseUrl
                        + "\",\"model\":\"" + model + "\",\"credentialId\":\"" + credentialId + "\","
                        + "\"userPrompt\":\"Reply with the JSON {\\\"answer\\\": \\\"ok\\\"} and nothing else.\","
                        + "\"temperature\":0,\"maxTokens\":50,\"responseFormat\":\"json\",\"jsonMode\":\"jsonObject\","
                        + "\"responseSchema\":{\"type\":\"object\",\"required\":[\"answer\"],\"properties\":{\"answer\":"
                        + "{\"type\":\"string\"}}},\"timeoutSeconds\":60,\"maxRetries\":2}")),
                List.of(Workflows.edge("t", "ai")));
        String workflowId = call(post("/api/v1/workflows"), "{\"name\":\"Live AI\",\"definition\":" + definition + "}", 201)
                .get("id").asText();

        String executionId = call(post("/api/v1/workflows/" + workflowId + "/execute"), null, 202).get("executionId").asText();
        JsonNode execution;
        long until = System.currentTimeMillis() + 90_000;
        do {
            Thread.sleep(250);
            execution = call(get("/api/v1/executions/" + executionId), null, 200);
        } while (!ExecutionStatus.valueOf(execution.get("status").asText()).isTerminal()
                && System.currentTimeMillis() < until);

        assertThat(execution.get("status").asText()).as(execution.toString()).isEqualTo("SUCCEEDED");
        JsonNode out = execution.get("nodes").get(1).get("output");
        assertThat(out.get("json").get("answer").asText()).isNotBlank();
        assertThat(execution.toString()).doesNotContain(key);
        System.out.println("Live AI usage: " + out.get("usage") + " model " + out.get("model"));
    }
}
