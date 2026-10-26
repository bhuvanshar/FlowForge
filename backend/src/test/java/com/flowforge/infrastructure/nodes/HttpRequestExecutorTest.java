package com.flowforge.infrastructure.nodes;

import static com.flowforge.application.execution.TestContexts.context;
import static com.flowforge.application.execution.TestContexts.node;
import static com.flowforge.application.execution.TestContexts.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.application.execution.CancellationToken;
import com.flowforge.application.execution.ExecutionContext;
import com.flowforge.application.execution.NodeExecutionException;
import com.flowforge.application.execution.TestContexts;
import com.flowforge.application.expression.ExpressionResolver;
import com.flowforge.infrastructure.http.HttpClientConfig;
import com.flowforge.infrastructure.http.HttpProperties;
import com.flowforge.infrastructure.http.OutboundRequestPolicy;
import com.flowforge.support.LocalHttpServer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** HTTP Request executor against a real local server (allow-private-addresses on, except where tested). */
class HttpRequestExecutorTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static LocalHttpServer server;
    private final List<AutoCloseable> closeables = new ArrayList<>();

    @BeforeAll
    static void start() throws Exception {
        server = new LocalHttpServer();
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    @AfterEach
    void cleanUp() throws Exception {
        for (AutoCloseable c : closeables) {
            c.close();
        }
    }

    private HttpRequestExecutor executor(List<String> hosts, boolean allowPrivate, Integer maxBytes) {
        HttpProperties properties = new HttpProperties(hosts, allowPrivate, List.of(), maxBytes, 3, Duration.ofSeconds(2));
        OutboundRequestPolicy policy = new OutboundRequestPolicy(properties);
        HttpClientConfig config = new HttpClientConfig();
        CloseableHttpClient client = config.workflowHttpClient(properties, policy);
        ScheduledExecutorService watchdog = config.httpWatchdog();
        closeables.add(client);
        closeables.add(watchdog::shutdownNow);
        return new HttpRequestExecutor(client, policy, properties, new ExpressionResolver(), JSON, watchdog);
    }

    private HttpRequestExecutor executor() {
        return executor(List.of("localhost", "127.0.0.1"), true, null);
    }

    private static Map<String, Object> config(String method, String url) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("method", method);
        c.put("url", url);
        c.put("headers", List.of());
        c.put("query", List.of());
        c.put("timeoutSeconds", 5);
        return c;
    }

    private JsonNode run(HttpRequestExecutor executor, Map<String, Object> config, ExecutionContext ctx) throws Exception {
        return executor.execute(request(node("http1", "core.httpRequest", config), JSON.readTree("{\"n\":1}"), ctx))
                .output();
    }

    private JsonNode run(Map<String, Object> config) throws Exception {
        return run(executor(), config, context(JSON.readTree("{\"email\":\"a@b.io\"}")));
    }

    private String failureCode(HttpRequestExecutor executor, Map<String, Object> config) {
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

    private String url(String path) {
        return server.url("localhost", path);
    }

    @Test
    void getReturnsAStructuredResponseWithParsedJson() throws Exception {
        JsonNode out = run(config("GET", url("/json")));

        assertThat(out.get("status").asInt()).isEqualTo(200);
        assertThat(out.get("statusText").asText()).isEqualTo("OK");
        assertThat(out.get("body")).isEqualTo(JSON.readTree("{\"id\":\"u-1\",\"ok\":true}"));
        assertThat(out.get("headers").get("content-type").asText()).isEqualTo("application/json");
        assertThat(out.get("url").asText()).isEqualTo(url("/json"));
        assertThat(out.get("redirects").asInt()).isZero();
        assertThat(out.get("request").get("method").asText()).isEqualTo("GET");
        assertThat(server.last().method()).isEqualTo("GET");
    }

    @Test
    void nonJsonBodiesAreText() throws Exception {
        assertThat(run(config("GET", url("/text"))).get("body").asText()).isEqualTo("plain text");
    }

    @Test
    void resolvesExpressionsInUrlQueryHeadersAndBody() throws Exception {
        ExecutionContext ctx = context(JSON.readTree("{\"user\":{\"email\":\"a@b.io\",\"age\":31}}"));
        ctx.variables().put("token", "s3cr3t").put("page", 2);
        TestContexts.ran(ctx, "prev", JSON.readTree("{\"id\":\"p-1\"}"));
        Map<String, Object> c = config("POST", url("/echo") + "?static=1");
        c.put("query", List.of(Map.of("name", "page", "value", "{{ vars.page }}"),
                Map.of("name", "q", "value", "a b&c")));
        c.put("headers", List.of(Map.of("name", "Authorization", "value", "Bearer {{ vars.token }}"),
                Map.of("name", "X-Prev", "value", "{{ nodes.prev.output.id }}")));
        c.put("body", Map.of("email", "{{ trigger.body.user.email }}", "age", "{{ trigger.body.user.age }}",
                "note", "age {{ trigger.body.user.age }}"));

        JsonNode out = run(executor(), c, ctx);

        JsonNode echoed = out.get("body");
        assertThat(echoed.get("method").asText()).isEqualTo("POST");
        assertThat(echoed.get("query").asText()).isEqualTo("static=1&page=2&q=a%20b%26c");
        assertThat(echoed.get("headers").get("authorization").asText()).isEqualTo("Bearer s3cr3t");
        assertThat(echoed.get("headers").get("x-prev").asText()).isEqualTo("p-1");
        assertThat(echoed.get("headers").get("content-type").asText()).startsWith("application/json");
        assertThat(JSON.readTree(echoed.get("body").asText()))
                .isEqualTo(JSON.readTree("{\"email\":\"a@b.io\",\"age\":31,\"note\":\"age 31\"}"));
        // Stored output never contains the secret.
        assertThat(out.get("request").get("headers").get("Authorization").asText()).isEqualTo("[REDACTED]");
        assertThat(out.get("request").toString()).doesNotContain("s3cr3t");
    }

    @Test
    void errorStatusesFailTheNode() {
        assertThatThrownBy(() -> run(config("GET", url("/status?code=404"))))
                .isInstanceOfSatisfying(NodeExecutionException.class, e -> {
                    assertThat(e.code()).isEqualTo(HttpRequestExecutor.STATUS_ERROR);
                    assertThat(e.getMessage()).contains("answered 404");
                });
        assertThat(failureCode(config("GET", url("/fail")))).isEqualTo(HttpRequestExecutor.STATUS_ERROR);
    }

    @Test
    void secretsInTheUrlAndCookiesAreRedactedInOutputAndErrors() throws Exception {
        JsonNode out = run(config("GET", url("/cookie") + "?token=abc&x=1"));
        assertThat(out.get("url").asText()).endsWith("/cookie?token=[REDACTED]&x=1");
        assertThat(out.get("headers").get("set-cookie").asText()).isEqualTo("[REDACTED]");

        assertThatThrownBy(() -> run(config("GET", url("/status?code=500&api_key=k1"))))
                .hasMessageContaining("api_key=[REDACTED]").hasMessageNotContaining("k1");
    }

    @Test
    void requestTimeoutIsEnforced() {
        Map<String, Object> c = config("GET", url("/delay?ms=3000"));
        c.put("timeoutSeconds", 1);
        long started = System.nanoTime();

        assertThat(failureCode(c)).isEqualTo(HttpRequestExecutor.TIMEOUT);
        assertThat((System.nanoTime() - started) / 1e9).isLessThan(2.5);
        assertThat(executor().timeout(node("h", "core.httpRequest", c))).contains(Duration.ofSeconds(6));
    }

    @Test
    void responseSizeIsLimitedWithAndWithoutContentLength() {
        HttpRequestExecutor small = executor(List.of("localhost"), true, 1000);
        assertThat(failureCode(small, config("GET", url("/big?bytes=5000")))).isEqualTo(HttpRequestExecutor.RESPONSE_TOO_LARGE);
        assertThat(failureCode(small, config("GET", url("/big?bytes=5000&chunked=1"))))
                .isEqualTo(HttpRequestExecutor.RESPONSE_TOO_LARGE);
        assertThat(failureCode(small, config("GET", url("/big?bytes=900")))).isEqualTo("NO_FAILURE");
    }

    @Test
    void followsAllowedRedirects() throws Exception {
        JsonNode out = run(config("GET", url("/redirect?to=/json")));

        assertThat(out.get("status").asInt()).isEqualTo(200);
        assertThat(out.get("redirects").asInt()).isEqualTo(1);
        assertThat(out.get("url").asText()).isEqualTo(url("/json"));
        assertThat(out.get("request").get("url").asText()).contains("/redirect");
    }

    @Test
    void redirectsAreReCheckedAgainstThePolicy() {
        assertThat(failureCode(config("GET", url("/redirect?to=http://evil.example/steal"))))
                .isEqualTo(HttpRequestExecutor.REDIRECT_BLOCKED);
        assertThat(failureCode(config("GET", url("/redirect?to=http://169.254.169.254/latest/meta-data"))))
                .isEqualTo(HttpRequestExecutor.REDIRECT_BLOCKED);
        assertThat(failureCode(config("GET", url("/redirect?to=file:///etc/passwd"))))
                .isEqualTo(HttpRequestExecutor.REDIRECT_BLOCKED);
        assertThat(failureCode(config("GET", url("/loop")))).isEqualTo(HttpRequestExecutor.TOO_MANY_REDIRECTS);
    }

    @Test
    void redirectToAnotherHostDropsCredentials() throws Exception {
        Map<String, Object> c = config("GET", url("/redirect?to=" + server.url("127.0.0.1", "/echo")));
        c.put("headers", List.of(Map.of("name", "Authorization", "value", "Bearer t"), Map.of("name", "X-Keep", "value", "1")));

        JsonNode echoed = run(c).get("body");

        assertThat(echoed.get("headers").has("authorization")).isFalse();
        assertThat(echoed.get("headers").get("x-keep").asText()).isEqualTo("1");
    }

    @Test
    void redirectMethodSemantics() throws Exception {
        Map<String, Object> seeOther = config("POST", url("/redirect?code=303&to=/echo"));
        seeOther.put("body", Map.of("a", 1));
        JsonNode afterSeeOther = run(seeOther).get("body");
        assertThat(afterSeeOther.get("method").asText()).isEqualTo("GET");
        assertThat(afterSeeOther.get("body").asText()).isEmpty();

        Map<String, Object> temporary = config("POST", url("/redirect?code=307&to=/echo"));
        temporary.put("body", Map.of("a", 1));
        JsonNode afterTemporary = run(temporary).get("body");
        assertThat(afterTemporary.get("method").asText()).isEqualTo("POST");
        assertThat(afterTemporary.get("body").asText()).isEqualTo("{\"a\":1}");
    }

    @Test
    void ssrfProtectionBlocksPrivateAddressesEvenForAllowlistedHosts() {
        int before = server.received().size();
        HttpRequestExecutor strict = executor(List.of("localhost", "127.0.0.1"), false, null);

        assertThat(failureCode(strict, config("GET", server.url("127.0.0.1", "/json"))))
                .isEqualTo(OutboundRequestPolicy.ADDRESS_BLOCKED);
        assertThat(failureCode(strict, config("GET", server.url("localhost", "/json"))))
                .isEqualTo(OutboundRequestPolicy.ADDRESS_BLOCKED);
        assertThat(server.received()).hasSize(before); // nothing reached the server
    }

    @Test
    void hostsOutsideTheAllowlistAreRefusedBeforeConnecting() {
        int before = server.received().size();
        HttpRequestExecutor onlyLocalhost = executor(List.of("localhost"), true, null);

        assertThat(failureCode(onlyLocalhost, config("GET", server.url("127.0.0.1", "/json"))))
                .isEqualTo(OutboundRequestPolicy.HOST_NOT_ALLOWED);
        assertThat(failureCode(executor(List.of(), true, null), config("GET", url("/json"))))
                .isEqualTo(OutboundRequestPolicy.HOST_NOT_ALLOWED);
        assertThat(server.received()).hasSize(before);
    }

    @Test
    void invalidUrlsAndHeaderInjectionAreRejected() throws Exception {
        assertThat(failureCode(config("GET", "ftp://localhost/x"))).isEqualTo(OutboundRequestPolicy.INVALID_URL);
        assertThat(failureCode(config("GET", "http://user:pw@localhost:" + server.port() + "/json")))
                .isEqualTo(OutboundRequestPolicy.INVALID_URL);
        assertThat(failureCode(config("GET", "not a url"))).isEqualTo(OutboundRequestPolicy.INVALID_URL);

        ExecutionContext ctx = context(JSON.createObjectNode());
        ctx.variables().put("evil", "x\r\nX-Injected: 1");
        Map<String, Object> c = config("GET", url("/echo"));
        c.put("headers", List.of(Map.of("name", "X-Test", "value", "{{ vars.evil }}")));
        assertThatThrownBy(() -> run(executor(), c, ctx))
                .isInstanceOfSatisfying(NodeExecutionException.class,
                        e -> assertThat(e.code()).isEqualTo(HttpRequestExecutor.INVALID_HEADER));
    }

    @Test
    void cancellationAbortsTheRequestPromptly() throws Exception {
        CancellationToken token = new CancellationToken();
        ExecutionContext ctx = TestContexts.context(JSON.createObjectNode(), token);
        new Thread(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
                // ignore
            }
            token.cancel();
        }).start();
        long started = System.nanoTime();

        assertThatThrownBy(() -> run(executor(), config("GET", url("/slow")), ctx))
                .isInstanceOf(InterruptedException.class);
        assertThat((System.nanoTime() - started) / 1e9).isLessThan(2.0);
    }

    // ----------------------------------------------------------------------------------------------------------------
    // Credentials (Module 12)
    // ----------------------------------------------------------------------------------------------------------------

    private static final java.util.UUID CRED = java.util.UUID.fromString("3f2b8c1e-5d4a-4b6f-9e7d-1a2b3c4d5e6f");

    private HttpRequestExecutor executorWith(com.flowforge.application.credential.ResolvedCredential credential,
                                             List<String> calls) {
        HttpProperties properties = new HttpProperties(List.of("localhost", "127.0.0.1"), true, List.of(), null, 3,
                Duration.ofSeconds(2), 64);
        OutboundRequestPolicy policy = new OutboundRequestPolicy(properties);
        HttpClientConfig config = new HttpClientConfig();
        CloseableHttpClient client = config.workflowHttpClient(properties, policy);
        ScheduledExecutorService watchdog = config.httpWatchdog();
        closeables.add(client);
        closeables.add(watchdog::shutdownNow);
        return new HttpRequestExecutor(client, policy, properties, new ExpressionResolver(), JSON, watchdog,
                (id, owner, type) -> {
                    calls.add(id + "|" + owner + "|" + type);
                    if (credential == null) {
                        throw new com.flowforge.application.credential.CredentialAccessException(
                                com.flowforge.application.credential.CredentialAccessException.NOT_FOUND, "missing");
                    }
                    return credential;
                });
    }

    private static com.flowforge.application.credential.ResolvedCredential credential(String type, Map<String, String> fields,
                                                                                    Map<String, String> secrets,
                                                                                    String... hosts) {
        return new com.flowforge.application.credential.ResolvedCredential(CRED, "Service API", type, List.of(hosts),
                fields, secrets);
    }

    private Map<String, Object> withCredential(String method, String url) {
        Map<String, Object> c = config(method, url);
        c.put("credentialId", CRED.toString());
        return c;
    }

    @Test
    void bearerCredentialIsSentAsOwnerAndRegisteredForMasking() throws Exception {
        List<String> calls = new ArrayList<>();
        ExecutionContext ctx = new ExecutionContext(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), 1,
                JSON.createObjectNode(), java.time.Instant.now().plusSeconds(60),
                new com.flowforge.application.execution.CancellationToken(), "owner-7");
        HttpRequestExecutor executor = executorWith(credential("httpBearer", Map.of(),
                Map.of("token", "tok_live_123456789"), "localhost"), calls);

        JsonNode out = run(executor, withCredential("GET", url("/echo")), ctx);

        assertThat(calls).containsExactly(CRED + "|owner-7|core.httpRequest");
        assertThat(server.last().headers().get("authorization")).isEqualTo("Bearer tok_live_123456789");
        assertThat(out.get("request").get("headers").get("Authorization").asText()).isEqualTo("[REDACTED]");
        assertThat(out.get("request").get("authenticatedWith").get("name").asText()).isEqualTo("Service API");
        // The server echoed the token in its body; the engine masks it with the registered secret before storing.
        assertThat(out.get("body").toString()).contains("tok_live_123456789");
        assertThat(ctx.secrets().mask(out).toString()).doesNotContain("tok_live_123456789");
    }

    @Test
    void basicAndHeaderCredentialsBuildTheirHeaders() throws Exception {
        run(executorWith(credential("httpBasic", Map.of("username", "ada"), Map.of("password", "pa55word!"),
                "localhost"), new ArrayList<>()), withCredential("GET", url("/echo")), context(JSON.createObjectNode()));
        assertThat(server.last().headers().get("authorization")).isEqualTo("Basic "
                + java.util.Base64.getEncoder().encodeToString("ada:pa55word!".getBytes()));

        ExecutionContext ctx = context(JSON.createObjectNode());
        JsonNode out = run(executorWith(credential("httpHeader", Map.of("headerName", "X-Service-Key"),
                Map.of("value", "svc_key_000111"), "localhost"), new ArrayList<>()), withCredential("GET", url("/echo")), ctx);
        assertThat(server.last().headers().get("x-service-key")).isEqualTo("svc_key_000111");
        assertThat(out.get("request").get("headers").get("X-Service-Key").asText()).isEqualTo("[REDACTED]");
        assertThat(ctx.secrets().mask("svc_key_000111")).isEqualTo("[REDACTED]");
    }

    @Test
    void credentialsAreOnlySentToTheirAllowedHosts() {
        int before = server.received().size();
        HttpRequestExecutor executor = executorWith(credential("httpBearer", Map.of(), Map.of("token", "tok_live_123456789"),
                "api.example.com"), new ArrayList<>());

        assertThat(failureCode(executor, withCredential("GET", url("/echo"))))
                .isEqualTo(HttpRequestExecutor.CREDENTIAL_HOST_NOT_ALLOWED);
        assertThat(server.received()).hasSize(before); // nothing was sent
    }

    @Test
    void credentialHeadersAreDroppedOnRedirectsToAnotherOrigin() throws Exception {
        HttpRequestExecutor executor = executorWith(credential("httpHeader", Map.of("headerName", "X-Service-Key"),
                Map.of("value", "svc_key_000111"), "localhost", "127.0.0.1"), new ArrayList<>());

        JsonNode echoed = run(executor, withCredential("GET", url("/redirect?to=" + server.url("127.0.0.1", "/echo"))),
                context(JSON.createObjectNode())).get("body");
        assertThat(echoed.get("headers").has("x-service-key")).isFalse();

        JsonNode sameOrigin = run(executor, withCredential("GET", url("/redirect?to=/echo")),
                context(JSON.createObjectNode())).get("body");
        assertThat(sameOrigin.get("headers").get("x-service-key").asText()).isEqualTo("svc_key_000111");
    }

    @Test
    void credentialProblemsFailTheNodeWithoutSending() {
        int before = server.received().size();
        assertThat(failureCode(executorWith(null, new ArrayList<>()), withCredential("GET", url("/echo"))))
                .isEqualTo("CREDENTIAL_NOT_FOUND");

        Map<String, Object> conflict = withCredential("GET", url("/echo"));
        conflict.put("headers", List.of(Map.of("name", "authorization", "value", "{{ input.n }}")));
        assertThat(failureCode(executorWith(credential("httpBearer", Map.of(), Map.of("token", "tok_live_123456789"),
                "localhost"), new ArrayList<>()), conflict)).isEqualTo(HttpRequestExecutor.CREDENTIAL_HEADER_CONFLICT);
        assertThat(server.received()).hasSize(before);
    }

    @Test
    void requestBodiesAndManagedHeadersAreLimited() {
        HttpRequestExecutor executor = executorWith(null, new ArrayList<>()); // max request body: 64 bytes
        Map<String, Object> big = config("POST", url("/echo"));
        big.put("body", Map.of("text", "x".repeat(100)));
        assertThat(failureCode(executor, big)).isEqualTo(HttpRequestExecutor.REQUEST_TOO_LARGE);

        Map<String, Object> host = config("GET", url("/echo"));
        host.put("headers", List.of(Map.of("name", "Host", "value", "internal.example")));
        assertThat(failureCode(executor, host)).isEqualTo(HttpRequestExecutor.INVALID_HEADER);
    }
}
