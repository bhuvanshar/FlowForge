package com.flowforge.infrastructure.nodes;

import static com.flowforge.application.execution.TestContexts.context;
import static com.flowforge.application.execution.TestContexts.node;
import static com.flowforge.application.execution.TestContexts.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.application.ai.AiChatService;
import com.flowforge.application.ai.ChatProviderRegistry;
import com.flowforge.application.credential.CredentialAccessException;
import com.flowforge.application.credential.CredentialResolver;
import com.flowforge.application.credential.ResolvedCredential;
import com.flowforge.application.execution.CancellationToken;
import com.flowforge.application.execution.ExecutionContext;
import com.flowforge.application.execution.NodeExecutionException;
import com.flowforge.application.execution.TestContexts;
import com.flowforge.application.expression.ExpressionResolver;
import com.flowforge.config.AiProperties;
import com.flowforge.infrastructure.ai.OpenAiCompatibleProvider;
import com.flowforge.infrastructure.http.HttpClientConfig;
import com.flowforge.infrastructure.http.HttpProperties;
import com.flowforge.infrastructure.http.OutboundRequestPolicy;
import com.flowforge.support.MockAiServer;
import com.flowforge.support.MockAiServer.Reply;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** AI Model node against a scripted OpenAI-compatible server: requests, JSON output, failures, retries, secrets. */
class AiModelExecutorTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String API_KEY = "sk-test-0123456789abcdef";
    private static final UUID CRED = UUID.fromString("3f2b8c1e-5d4a-4b6f-9e7d-1a2b3c4d5e6f");
    private static final String SCHEMA = "{\"type\":\"object\",\"required\":[\"sentiment\"],\"properties\":{\"sentiment\":"
            + "{\"enum\":[\"positive\",\"negative\"]}},\"additionalProperties\":false}";

    private static MockAiServer server;
    private final List<AutoCloseable> closeables = new ArrayList<>();

    @BeforeAll
    static void start() throws Exception {
        server = new MockAiServer();
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    @BeforeEach
    void reset() {
        server.reset();
    }

    @AfterEach
    void cleanUp() throws Exception {
        for (AutoCloseable c : closeables) {
            c.close();
        }
    }

    private AiModelExecutor executor(List<String> hosts, boolean allowPrivate, AiProperties ai,
                                     CredentialResolver credentials) {
        HttpProperties http = new HttpProperties(hosts, allowPrivate, List.of(), null, 3, Duration.ofSeconds(2));
        OutboundRequestPolicy policy = new OutboundRequestPolicy(http);
        HttpClientConfig config = new HttpClientConfig();
        CloseableHttpClient client = config.workflowHttpClient(http, policy);
        ScheduledExecutorService watchdog = config.httpWatchdog();
        closeables.add(client);
        closeables.add(watchdog::shutdownNow);
        OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(client, policy, ai, JSON, watchdog);
        AiChatService chat = new AiChatService(new ChatProviderRegistry(List.of(provider)), ai, JSON);
        return new AiModelExecutor(chat, credentials, new ExpressionResolver(), ai, JSON);
    }

    private static AiProperties fastRetries() {
        return new AiProperties(null, null, Duration.ofMillis(5), Duration.ofMillis(20), Duration.ofSeconds(2));
    }

    private CredentialResolver bearer(String... hosts) {
        return (id, owner, type) -> new ResolvedCredential(CRED, "OpenAI key", "httpBearer", List.of(hosts), Map.of(),
                Map.of("token", API_KEY));
    }

    private AiModelExecutor executor() {
        return executor(List.of("localhost"), true, fastRetries(), bearer("localhost"));
    }

    private static Map<String, Object> config(Object... extra) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("provider", "openaiCompatible");
        c.put("baseUrl", server.baseUrl());
        c.put("model", "test-model");
        c.put("userPrompt", "Say hi");
        c.put("responseFormat", "text");
        c.put("timeoutSeconds", 10);
        c.put("maxRetries", 2);
        for (int i = 0; i < extra.length; i += 2) {
            if (extra[i + 1] == null) {
                c.remove((String) extra[i]);
            } else {
                c.put((String) extra[i], extra[i + 1]);
            }
        }
        return c;
    }

    private static Map<String, Object> jsonConfig(Object... extra) {
        Map<String, Object> c = config("responseFormat", "json", "jsonMode", "jsonObject");
        try {
            c.put("responseSchema", JSON.readValue(SCHEMA, Map.class));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        for (int i = 0; i < extra.length; i += 2) {
            if (extra[i + 1] == null) {
                c.remove((String) extra[i]);
            } else {
                c.put((String) extra[i], extra[i + 1]);
            }
        }
        return c;
    }

    private JsonNode run(AiModelExecutor executor, Map<String, Object> config, ExecutionContext ctx) throws Exception {
        return executor.execute(request(node("ai", "core.aiModel", config), JSON.readTree("{\"text\":\"I love it\"}"), ctx))
                .output();
    }

    private JsonNode run(Map<String, Object> config) throws Exception {
        return run(executor(), config, context(JSON.createObjectNode()));
    }

    private String failureCode(AiModelExecutor executor, Map<String, Object> config) {
        try {
            run(executor, config, context(JSON.createObjectNode()));
            return "NO_FAILURE";
        } catch (NodeExecutionException e) {
            return e.code();
        } catch (Exception e) {
            return e.getClass().getSimpleName();
        }
    }

    private String failureCode(Map<String, Object> config) {
        return failureCode(executor(), config);
    }

    private static JsonNode messages(MockAiServer.Received request) {
        return request.json().get("messages");
    }

    // ----------------------------------------------------------------------------------------------------------------
    // Requests and text replies
    // ----------------------------------------------------------------------------------------------------------------

    @Test
    void sendsAnOpenAiChatCompletionRequestWithInterpolatedPrompts() throws Exception {
        server.reply(Reply.content("Hello!"));
        ExecutionContext ctx = context(JSON.readTree("{\"user\":{\"name\":\"Ada\"}}"));
        ctx.variables().put("tone", "friendly");

        JsonNode out = run(executor(), config("credentialId", CRED.toString(), "systemPrompt", "Be {{ vars.tone }}.",
                "userPrompt", "Greet {{ trigger.body.user.name }} about: {{ input.text }}",
                "temperature", 0.2, "maxTokens", 150), ctx);

        MockAiServer.Received sent = server.lastRequest();
        assertThat(sent.method()).isEqualTo("POST");
        assertThat(sent.path()).isEqualTo("/v1/chat/completions");
        assertThat(sent.headers().get("content-type")).startsWith("application/json");
        assertThat(sent.headers().get("authorization")).isEqualTo("Bearer " + API_KEY);
        JsonNode body = sent.json();
        assertThat(body.get("model").asText()).isEqualTo("test-model");
        assertThat(body.get("temperature").asDouble()).isEqualTo(0.2);
        assertThat(body.get("max_tokens").asInt()).isEqualTo(150);
        assertThat(body.get("stream").asBoolean()).isFalse();
        assertThat(body.has("response_format")).isFalse();
        assertThat(messages(sent)).hasSize(2);
        assertThat(messages(sent).get(0).get("role").asText()).isEqualTo("system");
        assertThat(messages(sent).get(0).get("content").asText()).isEqualTo("Be friendly.");
        assertThat(messages(sent).get(1).get("content").asText()).isEqualTo("Greet Ada about: I love it");

        assertThat(out.get("text").asText()).isEqualTo("Hello!");
        assertThat(out.has("json")).isFalse();
        assertThat(out.get("model").asText()).isEqualTo("test-model-2026");
        assertThat(out.get("provider").asText()).isEqualTo("openaiCompatible");
        assertThat(out.get("finishReason").asText()).isEqualTo("stop");
        assertThat(out.get("attempts").asInt()).isEqualTo(1);
        assertThat(out.get("usage").toString()).isEqualTo("{\"prompt\":12,\"completion\":7,\"total\":19}");
    }

    @Test
    void omitsOptionalSettingsAndTheSystemMessageWhenNotConfigured() throws Exception {
        server.reply(Reply.content("ok"));
        run(config());

        JsonNode body = server.lastRequest().json();
        assertThat(body.has("temperature")).isFalse();
        assertThat(body.has("max_tokens")).isFalse();
        assertThat(messages(server.lastRequest())).hasSize(1);
        assertThat(messages(server.lastRequest()).get(0).get("role").asText()).isEqualTo("user");
    }

    @Test
    void usageAndProviderReportedCostAreReturnedAndSummedAcrossAttempts() throws Exception {
        server.reply(Reply.completion("not json", "stop", "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2,"
                        + "\"total_tokens\":12,\"cost\":0.0012}"),
                Reply.completion("{\"sentiment\":\"positive\"}", "stop", "\"usage\":{\"prompt_tokens\":30,"
                        + "\"completion_tokens\":5,\"total_tokens\":35,\"cost\":0.0030}"));

        JsonNode out = run(jsonConfig());

        assertThat(out.get("attempts").asInt()).isEqualTo(2);
        assertThat(out.get("usage").get("total").asLong()).isEqualTo(47);
        assertThat(out.get("usage").get("prompt").asLong()).isEqualTo(40);
        assertThat(out.get("usage").get("cost").decimalValue()).isEqualByComparingTo("0.0042");
    }

    @Test
    void noUsageBlockWhenTheProviderReportsNone() throws Exception {
        server.reply(Reply.completion("hi", "stop", null));
        assertThat(run(config()).has("usage")).isFalse();
    }

    @Test
    void contentGivenAsPartsIsJoined() throws Exception {
        server.reply(Reply.raw(200, "{\"choices\":[{\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"Hel\"},"
                + "{\"type\":\"text\",\"text\":\"lo\"}]},\"finish_reason\":\"stop\"}]}"));
        assertThat(run(config()).get("text").asText()).isEqualTo("Hello");
    }

    // ----------------------------------------------------------------------------------------------------------------
    // Structured JSON output
    // ----------------------------------------------------------------------------------------------------------------

    @Test
    void jsonModeAsksForAJsonObjectShowsTheSchemaAndReturnsValidatedJson() throws Exception {
        server.reply(Reply.content("{\"sentiment\":\"positive\"}"));

        JsonNode out = run(jsonConfig("systemPrompt", "You classify."));

        JsonNode body = server.lastRequest().json();
        assertThat(body.get("response_format").get("type").asText()).isEqualTo("json_object");
        String system = messages(server.lastRequest()).get(0).get("content").asText();
        assertThat(system).startsWith("You classify.").contains("JSON").contains("\"sentiment\"");
        assertThat(out.get("json")).isEqualTo(JSON.readTree("{\"sentiment\":\"positive\"}"));
        assertThat(out.get("text").asText()).isEqualTo("{\"sentiment\":\"positive\"}");
    }

    @Test
    void promptModeDoesNotSendResponseFormatForServersThatLackIt() throws Exception {
        server.reply(Reply.content("{\"sentiment\":\"negative\"}"));
        JsonNode out = run(jsonConfig("jsonMode", "prompt"));

        assertThat(server.lastRequest().json().has("response_format")).isFalse();
        assertThat(messages(server.lastRequest()).get(0).get("content").asText()).contains("JSON");
        assertThat(out.get("json").get("sentiment").asText()).isEqualTo("negative");
    }

    @Test
    void aMarkdownCodeFenceAroundTheJsonIsAccepted() throws Exception {
        server.reply(Reply.content("```json\n{\"sentiment\":\"positive\"}\n```"));
        assertThat(run(jsonConfig()).get("json").get("sentiment").asText()).isEqualTo("positive");
    }

    @Test
    void jsonWithoutASchemaAcceptsAnyValidJson() throws Exception {
        server.reply(Reply.content("[1,2,3]"));
        assertThat(run(jsonConfig("responseSchema", null)).get("json")).isEqualTo(JSON.readTree("[1,2,3]"));
    }

    @Test
    void malformedJsonIsRepairedByAskingAgainAndShowingTheProblem() throws Exception {
        server.reply(Reply.content("Sure! Here you go: {\"sentiment\": \"positive\"}"),
                Reply.content("{\"sentiment\":\"positive\"}"));

        JsonNode out = run(jsonConfig());

        assertThat(out.get("attempts").asInt()).isEqualTo(2);
        assertThat(server.requests()).hasSize(2);
        JsonNode second = messages(server.requests().get(1));
        assertThat(second).hasSize(4); // system, user, the bad reply, the correction request
        assertThat(second.get(2).get("role").asText()).isEqualTo("assistant");
        assertThat(second.get(2).get("content").asText()).startsWith("Sure!");
        assertThat(second.get(3).get("content").asText()).contains("rejected").contains("not valid JSON");
    }

    @Test
    void schemaViolationsAreRepairedToo() throws Exception {
        server.reply(Reply.content("{\"sentiment\":\"furious\"}"), Reply.content("{\"sentiment\":\"negative\"}"));

        JsonNode out = run(jsonConfig());

        assertThat(out.get("json").get("sentiment").asText()).isEqualTo("negative");
        assertThat(messages(server.requests().get(1)).get(3).get("content").asText())
                .contains("does not match the response schema");
    }

    @Test
    void unusableRepliesStopAfterTheConfiguredNumberOfAttempts() {
        server.reply(Reply.content("nope")).repeatLast();

        assertThatThrownBy(() -> run(jsonConfig("maxRetries", 2)))
                .isInstanceOfSatisfying(NodeExecutionException.class, e -> {
                    assertThat(e.code()).isEqualTo(AiChatService.OUTPUT_INVALID);
                    assertThat(e.getMessage()).contains("not valid JSON").contains("after 3 attempts");
                });
        assertThat(server.requests()).hasSize(3);
    }

    @Test
    void zeroRetriesMeansExactlyOneRequest() {
        server.reply(Reply.content("{\"sentiment\":\"meh\"}")).repeatLast();
        assertThat(failureCode(jsonConfig("maxRetries", 0))).isEqualTo(AiChatService.OUTPUT_INVALID);
        assertThat(server.requests()).hasSize(1);
    }

    @Test
    void aReplyCutOffByTheTokenLimitIsReportedAndNotRetried() {
        server.reply(Reply.content("{\"sentiment\":\"posi", "length")).repeatLast();

        assertThat(failureCode(jsonConfig())).isEqualTo(AiChatService.OUTPUT_TRUNCATED);
        assertThat(server.requests()).hasSize(1);
    }

    @Test
    void textRepliesCutOffByTheLimitStillSucceedAndSayWhy() throws Exception {
        server.reply(Reply.content("partial answer", "length"));
        assertThat(run(config("maxTokens", 5)).get("finishReason").asText()).isEqualTo("length");
    }

    // ----------------------------------------------------------------------------------------------------------------
    // Provider failures, rate limits, retries
    // ----------------------------------------------------------------------------------------------------------------

    @Test
    void rateLimitsAreRetriedHonouringRetryAfter() throws Exception {
        server.reply(Reply.error(429, "Rate limit reached").withHeader("Retry-After", "0"), Reply.content("ok"));

        JsonNode out = run(config());

        assertThat(out.get("attempts").asInt()).isEqualTo(2);
        assertThat(server.requests()).hasSize(2);
    }

    @Test
    void aRetryAfterBeyondTheBoundMakesTheRateLimitFinal() {
        server.reply(Reply.error(429, "slow down").withHeader("Retry-After", "3600")).repeatLast();

        assertThatThrownBy(() -> run(config()))
                .isInstanceOfSatisfying(NodeExecutionException.class, e -> {
                    assertThat(e.code()).isEqualTo("AI_RATE_LIMITED");
                    assertThat(e.getMessage()).contains("429").contains("slow down");
                });
        assertThat(server.requests()).hasSize(1);
    }

    @Test
    void rateLimitsThatPersistFailAfterTheBoundedAttempts() {
        server.reply(Reply.error(429, "Rate limit reached")).repeatLast();

        assertThat(failureCode(config("maxRetries", 3))).isEqualTo("AI_RATE_LIMITED");
        assertThat(server.requests()).hasSize(4);
    }

    @Test
    void serverErrorsAreRetriedThenReported() throws Exception {
        server.reply(Reply.error(503, "overloaded"), Reply.content("recovered"));
        assertThat(run(config()).get("text").asText()).isEqualTo("recovered");

        server.reset();
        server.reply(Reply.error(500, "boom")).repeatLast();
        assertThat(failureCode(config())).isEqualTo("AI_PROVIDER_ERROR");
        assertThat(server.requests()).hasSize(3);
    }

    @Test
    void authenticationFailuresAreFinalAndNeverQuoteTheProvidersText() {
        server.reply(Reply.error(401, "Incorrect API key provided: sk-test-0123****cdef. You can find your key at ..."))
                .repeatLast();

        assertThatThrownBy(() -> run(config()))
                .isInstanceOfSatisfying(NodeExecutionException.class, e -> {
                    assertThat(e.code()).isEqualTo("AI_AUTH_FAILED");
                    assertThat(e.getMessage()).contains("401").doesNotContain("sk-test").doesNotContain("Incorrect");
                });
        assertThat(server.requests()).hasSize(1);
    }

    @Test
    void badRequestsAreFinalAndShowTheProvidersReason() {
        server.reply(Reply.error(400, "Unknown model 'test-model'")).repeatLast();

        assertThatThrownBy(() -> run(config()))
                .isInstanceOfSatisfying(NodeExecutionException.class, e -> {
                    assertThat(e.code()).isEqualTo("AI_BAD_REQUEST");
                    assertThat(e.getMessage()).contains("400").contains("Unknown model");
                });
        assertThat(server.requests()).hasSize(1);
    }

    @Test
    void emptyRefusedAndGarbledRepliesAreHandled() {
        server.reply(Reply.content("")).repeatLast();
        assertThat(failureCode(config())).isEqualTo("AI_EMPTY_RESPONSE");
        assertThat(server.requests()).hasSize(3);

        server.reset();
        server.reply(Reply.raw(200, "{\"choices\":[{\"message\":{\"content\":null,\"refusal\":\"I can't help with that.\"},"
                + "\"finish_reason\":\"stop\"}]}")).repeatLast();
        assertThat(failureCode(config())).isEqualTo("AI_REFUSED");
        assertThat(server.requests()).hasSize(1);

        server.reset();
        server.reply(Reply.raw(200, "<html>gateway</html>")).repeatLast();
        assertThat(failureCode(config())).isEqualTo("AI_BAD_RESPONSE");
        assertThat(server.requests()).hasSize(3);

        server.reset();
        server.reply(Reply.raw(200, "{\"choices\":[]}")).repeatLast();
        assertThat(failureCode(config("maxRetries", 0))).isEqualTo("AI_BAD_RESPONSE");
    }

    @Test
    void theNodeTimeoutIsOneBudgetForAllAttemptsAndIsNotRetried() {
        server.reply(Reply.content("late").delayed(5_000)).repeatLast();
        long started = System.nanoTime();

        assertThat(failureCode(config("timeoutSeconds", 1))).isEqualTo("AI_TIMEOUT");

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(4));
        assertThat(server.requests()).hasSize(1);
    }

    @Test
    void oversizedResponsesAreRejected() {
        AiProperties small = new AiProperties(null, 200, Duration.ofMillis(5), Duration.ofMillis(20), Duration.ofSeconds(2));
        server.reply(Reply.content("x".repeat(1000))).repeatLast();

        assertThat(failureCode(executor(List.of("localhost"), true, small, bearer("localhost")), config()))
                .isEqualTo("AI_RESPONSE_TOO_LARGE");
        assertThat(server.requests()).hasSize(1);
    }

    @Test
    void redirectsAreNeverFollowedBecauseTheyWouldCarryTheKey() {
        server.reply(new Reply(302, Map.of("Location", "http://localhost:1/steal"), "", 0)).repeatLast();

        assertThat(failureCode(config())).isEqualTo("AI_REQUEST_REFUSED");
        assertThat(server.requests()).hasSize(1);
    }

    @Test
    void connectionFailuresAreRetriedAndReported() {
        assertThat(failureCode(config("baseUrl", "http://localhost:1/v1"))).isEqualTo("AI_CONNECTION_ERROR");
    }

    @Test
    void cancellationInterruptsAWaitingRequest() throws Exception {
        server.reply(Reply.content("late").delayed(5_000)).repeatLast();
        CancellationToken token = new CancellationToken();
        ExecutionContext ctx = context(JSON.createObjectNode(), token);
        new Thread(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
                // test thread helper
            }
            token.cancel();
        }).start();

        assertThatThrownBy(() -> run(executor(), config("timeoutSeconds", 30), ctx))
                .isInstanceOf(InterruptedException.class);
    }

    // ----------------------------------------------------------------------------------------------------------------
    // Outbound protection and credentials
    // ----------------------------------------------------------------------------------------------------------------

    @Test
    void theHttpAllowlistAndNonPublicAddressRulesApply() {
        AiModelExecutor notListed = executor(List.of("api.example.com"), true, fastRetries(), bearer("localhost"));
        assertThat(failureCode(notListed, config())).isEqualTo("HTTP_HOST_NOT_ALLOWED");

        AiModelExecutor publicOnly = executor(List.of("localhost"), false, fastRetries(), bearer("localhost"));
        assertThat(failureCode(publicOnly, config())).isEqualTo("HTTP_ADDRESS_BLOCKED");
        assertThat(server.requests()).isEmpty();
    }

    @Test
    void theCredentialIsSentAsTheAuthorizationHeaderAndRegisteredForMasking() throws Exception {
        server.reply(Reply.content("ok"));
        ExecutionContext ctx = context(JSON.createObjectNode());

        JsonNode out = run(executor(), config("credentialId", CRED.toString()), ctx);

        assertThat(server.lastRequest().headers().get("authorization")).isEqualTo("Bearer " + API_KEY);
        assertThat(out.toString()).doesNotContain(API_KEY);
        assertThat(out.get("request").get("authenticatedWith").get("name").asText()).isEqualTo("OpenAI key");
        assertThat(ctx.secrets().mask("echo " + API_KEY)).isEqualTo("echo [REDACTED]");
        assertThat(server.lastRequest().body()).doesNotContain(API_KEY);
    }

    @Test
    void apiKeyHeaderCredentialsWork() throws Exception {
        server.reply(Reply.content("ok"));
        CredentialResolver keyHeader = (id, owner, type) -> new ResolvedCredential(CRED, "Gateway", "httpHeader",
                List.of("localhost"), Map.of("headerName", "api-key"), Map.of("value", API_KEY));

        run(executor(List.of("localhost"), true, fastRetries(), keyHeader), config("credentialId", CRED.toString()),
                context(JSON.createObjectNode()));

        assertThat(server.lastRequest().headers().get("api-key")).isEqualTo(API_KEY);
        assertThat(server.lastRequest().headers()).doesNotContainKey("authorization");
    }

    @Test
    void withoutACredentialNoAuthorizationIsSent() throws Exception {
        server.reply(Reply.content("ok"));
        run(executor(), config(), context(JSON.createObjectNode()));
        // The credential setting is optional (local models): the resolver is only used when credentialId is present.
        assertThat(server.lastRequest().headers()).doesNotContainKey("authorization");
    }

    @Test
    void credentialsAreOnlySentToTheirAllowedHostsAndOwnerRulesApply() {
        AiModelExecutor wrongHost = executor(List.of("localhost"), true, fastRetries(), bearer("api.openai.com"));
        assertThat(failureCode(wrongHost, config("credentialId", CRED.toString()))).isEqualTo("CREDENTIAL_HOST_NOT_ALLOWED");

        AiModelExecutor missing = executor(List.of("localhost"), true, fastRetries(), (id, owner, type) -> {
            throw new CredentialAccessException(CredentialAccessException.NOT_FOUND, "missing");
        });
        assertThat(failureCode(missing, config("credentialId", CRED.toString()))).isEqualTo("CREDENTIAL_NOT_FOUND");

        List<String> calls = new ArrayList<>();
        AiModelExecutor recording = executor(List.of("localhost"), true, fastRetries(), (id, owner, type) -> {
            calls.add(owner + "|" + type);
            return bearer("localhost").resolve(id, owner, type);
        });
        server.reply(Reply.content("ok"));
        try {
            run(recording, config("credentialId", CRED.toString()), new ExecutionContext(UUID.randomUUID(),
                    UUID.randomUUID(), 1, JSON.createObjectNode(), java.time.Instant.now().plusSeconds(60),
                    new CancellationToken(), "owner-9"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        assertThat(calls).containsExactly("owner-9|core.aiModel");
        assertThat(server.requests()).hasSize(1);
    }

    @Test
    void aKeyEchoedInAnErrorBodyIsMaskedByTheRunsSecretMask() {
        server.reply(Reply.error(500, "upstream said key " + API_KEY + " is bad")).repeatLast();
        ExecutionContext ctx = context(JSON.createObjectNode());

        assertThatThrownBy(() -> run(executor(), config("credentialId", CRED.toString(), "maxRetries", 0), ctx))
                .isInstanceOfSatisfying(NodeExecutionException.class, e ->
                        assertThat(ctx.secrets().mask(e.getMessage())).doesNotContain(API_KEY).contains("[REDACTED]"));
    }

    // ----------------------------------------------------------------------------------------------------------------
    // Prompts and configuration
    // ----------------------------------------------------------------------------------------------------------------

    @Test
    void promptProblemsFailBeforeAnythingIsSent() {
        assertThat(failureCode(config("userPrompt", "Hi {{ nothing.here }}"))).isEqualTo("EXPRESSION_ERROR");
        assertThat(failureCode(config("userPrompt", "{{ input.missing }}"))).isEqualTo("INVALID_CONFIGURATION");
        AiProperties tiny = new AiProperties(20, null, null, null, null);
        assertThat(failureCode(executor(List.of("localhost"), true, tiny, bearer("localhost")),
                config("userPrompt", "x".repeat(50)))).isEqualTo(AiModelExecutor.PROMPT_TOO_LARGE);
        assertThat(server.requests()).isEmpty();
    }

    @Test
    void responseSchemasOutsideThePolicyAreRefusedAtRunTimeToo() throws Exception {
        Map<String, Object> bad = jsonConfig("responseSchema", JSON.readValue(
                "{\"type\":\"object\",\"properties\":{\"a\":{\"$ref\":\"http://169.254.169.254/x\"}}}", Map.class));
        assertThat(failureCode(bad)).isEqualTo("INVALID_CONFIGURATION");
        assertThat(server.requests()).isEmpty();
    }

    @Test
    void unknownProvidersAreRejected() {
        assertThat(failureCode(config("provider", "mystery"))).isEqualTo(AiChatService.UNKNOWN_PROVIDER);
    }

    @Test
    void theEngineTimeoutIsTheNodeBudgetPlusAMargin() {
        assertThat(executor().timeout(node("ai", "core.aiModel", config("timeoutSeconds", 20))))
                .contains(Duration.ofSeconds(25));
        assertThat(TestContexts.class).isNotNull();
    }
}
