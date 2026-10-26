package com.flowforge.infrastructure.nodes;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowforge.application.execution.NodeExecutionException;
import com.flowforge.application.execution.NodeExecutionRequest;
import com.flowforge.application.execution.NodeExecutionResult;
import com.flowforge.application.execution.NodeExecutor;
import com.flowforge.application.credential.CredentialAccessException;
import com.flowforge.application.credential.CredentialResolver;
import com.flowforge.application.credential.ResolvedCredential;
import com.flowforge.domain.validation.HeaderRules;
import com.flowforge.application.expression.ExpressionResolver;
import com.flowforge.application.expression.ExpressionScope;
import com.flowforge.domain.workflow.NodeDefinition;
import com.flowforge.infrastructure.http.HttpProperties;
import com.flowforge.infrastructure.http.OutboundPolicyException;
import com.flowforge.infrastructure.http.OutboundRequestPolicy;
import com.flowforge.infrastructure.http.Redaction;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.net.URIBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;

/**
 * {@code core.httpRequest}: sends one HTTP request and returns
 * {@code { status, statusText, url, headers, body, redirects, request: { method, url, headers } }}.
 *
 * <p>Security (see {@link OutboundRequestPolicy}): only allowlisted hosts and ports, http/https only, no credentials in
 * URLs; addresses are validated at connect time (no DNS rebinding); redirects are followed manually, at most
 * {@code max-redirects}, each re-checked, and credentials headers are dropped when the host changes; response bodies are
 * capped at {@code max-response-bytes}; the whole request is bounded by the node's {@code timeoutSeconds} and aborted on
 * cancellation. Sensitive header values and query parameters are redacted in the stored output and in errors.
 *
 * <p><b>Credentials</b> ({@code credentialId}, Module 12): the credential is resolved as the workflow's owner, must be
 * usable by this node type and is only attached when the target host is in its {@code allowedHosts}
 * ({@code CREDENTIAL_HOST_NOT_ALLOWED} otherwise). Its headers are dropped on any redirect to another origin, shown as
 * {@code [REDACTED]} in the request echo, and every secret value is registered with the run's {@code SecretMask}, so it
 * is removed from stored output, errors and logs even if a server echoes it back.
 *
 * <p>Responses with status 400 or above fail the node ({@code HTTP_STATUS_ERROR}).
 */
@Component
public class HttpRequestExecutor implements NodeExecutor {

    public static final String STATUS_ERROR = "HTTP_STATUS_ERROR";
    public static final String TIMEOUT = "HTTP_TIMEOUT";
    public static final String CONNECTION_ERROR = "HTTP_CONNECTION_ERROR";
    public static final String DNS_ERROR = "HTTP_DNS_ERROR";
    public static final String REDIRECT_BLOCKED = "HTTP_REDIRECT_BLOCKED";
    public static final String TOO_MANY_REDIRECTS = "TOO_MANY_REDIRECTS";
    public static final String RESPONSE_TOO_LARGE = "RESPONSE_TOO_LARGE";
    public static final String INVALID_HEADER = "INVALID_HEADER";
    public static final String REQUEST_TOO_LARGE = "REQUEST_TOO_LARGE";
    public static final String CREDENTIAL_HOST_NOT_ALLOWED = "CREDENTIAL_HOST_NOT_ALLOWED";
    public static final String CREDENTIAL_HEADER_CONFLICT = "CREDENTIAL_HEADER_CONFLICT";
    private static final String CREDENTIAL_MARK = "credential";

    private static final Set<Integer> REDIRECTS = Set.of(301, 302, 303, 307, 308);
    private static final Set<String> CREDENTIAL_HEADERS = Set.of("authorization", "proxy-authorization", "cookie");
    private static final Duration ENGINE_MARGIN = Duration.ofSeconds(5);

    private final CloseableHttpClient client;
    private final OutboundRequestPolicy policy;
    private final HttpProperties properties;
    private final ExpressionResolver resolver;
    private final ObjectMapper mapper;
    private final ScheduledExecutorService watchdog;
    private final CredentialResolver credentials;

    public HttpRequestExecutor(CloseableHttpClient workflowHttpClient, OutboundRequestPolicy policy,
                               HttpProperties properties, ExpressionResolver resolver, ObjectMapper mapper,
                               ScheduledExecutorService httpWatchdog) {
        this(workflowHttpClient, policy, properties, resolver, mapper, httpWatchdog, (id, owner, type) -> {
            throw new CredentialAccessException(CredentialAccessException.NOT_FOUND, "Credentials are not available.");
        });
    }

    @org.springframework.beans.factory.annotation.Autowired
    public HttpRequestExecutor(CloseableHttpClient workflowHttpClient, OutboundRequestPolicy policy,
                               HttpProperties properties, ExpressionResolver resolver, ObjectMapper mapper,
                               ScheduledExecutorService httpWatchdog, CredentialResolver credentials) {
        this.credentials = credentials;
        this.client = workflowHttpClient;
        this.policy = policy;
        this.properties = properties;
        this.resolver = resolver;
        this.mapper = mapper;
        this.watchdog = httpWatchdog;
    }

    @Override
    public String nodeType() {
        return "core.httpRequest";
    }

    /** The executor enforces {@code timeoutSeconds} itself; the engine's limit is a little longer as a backstop. */
    @Override
    public Optional<Duration> timeout(NodeDefinition node) {
        return Optional.of(requestTimeout(mapper.valueToTree(node.configuration())).plus(ENGINE_MARGIN));
    }

    /** {@code headers} entries are {name, value} or {name, value, "credential"} for headers added by a credential. */
    private record Prepared(String method, URI uri, List<String[]> headers, byte[] body, ContentType contentType,
                            ResolvedCredential credential) {
    }

    private record Received(int status, String reason, List<Header> headers, byte[] body, ContentType contentType) {
    }

    @Override
    public NodeExecutionResult execute(NodeExecutionRequest request) throws Exception {
        JsonNode config = NodeConfig.of(mapper, request.node());
        ExpressionScope scope = new ExpressionScope(request.input(), request.context());
        Prepared prepared = withCredential(prepare(config, scope), config, request);
        Duration timeout = requestTimeout(config);
        long deadline = System.nanoTime() + timeout.toNanos();

        Prepared current = prepared;
        int redirects = 0;
        while (true) {
            policyCheck(current.uri(), redirects > 0);
            if (redirects == 0 && current.credential() != null && !current.credential().permitsHost(current.uri().getHost())) {
                throw new NodeExecutionException(CREDENTIAL_HOST_NOT_ALLOWED, "Credential '"
                        + current.credential().name() + "' may not be sent to " + current.uri().getHost()
                        + " (allowed: " + String.join(", ", current.credential().allowedHosts()) + ").");
            }
            Received received = send(current, deadline, timeout, request);
            String location = header(received.headers(), "location");
            if (REDIRECTS.contains(received.status()) && location != null) {
                if (redirects == properties.maxRedirects()) {
                    throw new NodeExecutionException(TOO_MANY_REDIRECTS, "More than " + properties.maxRedirects()
                            + " redirects (last: " + Redaction.url(current.uri()) + ").");
                }
                current = redirect(current, received.status(), location);
                redirects++;
                continue;
            }
            if (received.status() >= 400) {
                throw new NodeExecutionException(STATUS_ERROR, current.method() + " " + Redaction.url(current.uri())
                        + " answered " + received.status() + (received.reason() == null ? "" : " " + received.reason())
                        + ".");
            }
            return NodeExecutionResult.success(output(prepared, current, received, redirects));
        }
    }

    // --------------------------------------------------------------------------------------------------------------
    // Request preparation
    // --------------------------------------------------------------------------------------------------------------

    private Prepared prepare(JsonNode config, ExpressionScope scope) {
        String method = NodeConfig.requiredText(config, "method").toUpperCase(Locale.ROOT);
        String urlTemplate = NodeConfig.requiredText(config, "url");
        String url = NodeConfig.resolving("url", () -> resolver.text(urlTemplate, scope)).trim();

        URI uri;
        try {
            URIBuilder builder = new URIBuilder(url);
            int index = 0;
            for (JsonNode param : config.path("query")) {
                String name = NodeConfig.requiredText(param, "name");
                String valueTemplate = NodeConfig.requiredText(param, "value");
                String value = NodeConfig.resolving("query[" + index++ + "].value",
                        () -> resolver.text(valueTemplate, scope));
                builder.addParameter(name, value);
            }
            uri = builder.build();
        } catch (URISyntaxException e) {
            throw new NodeExecutionException(OutboundRequestPolicy.INVALID_URL, "Not a valid URL: "
                    + Redaction.url(url));
        }

        List<String[]> headers = new ArrayList<>();
        int index = 0;
        for (JsonNode header : config.path("headers")) {
            String name = NodeConfig.requiredText(header, "name");
            String valueTemplate = NodeConfig.requiredText(header, "value");
            String value = NodeConfig.resolving("headers[" + index++ + "].value",
                    () -> resolver.text(valueTemplate, scope));
            if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                throw new NodeExecutionException(INVALID_HEADER, "Header '" + name + "' contains a line break.");
            }
            if (HeaderRules.isForbidden(name)) {
                throw new NodeExecutionException(INVALID_HEADER, "Header '" + name + "' is managed by the HTTP client.");
            }
            headers.add(new String[] {name, value});
        }

        byte[] body = null;
        ContentType contentType = ContentType.APPLICATION_JSON;
        if (config.has("body") && !method.equals("GET") && !method.equals("HEAD")) {
            JsonNode resolved = NodeConfig.resolving("body", () -> resolver.json(config.get("body"), scope));
            try {
                body = mapper.writeValueAsBytes(resolved);
            } catch (JsonProcessingException e) {
                throw new NodeExecutionException(NodeConfig.INVALID_CONFIGURATION, "The body is not serializable.");
            }
            if (body.length > properties.maxRequestBytes()) {
                throw new NodeExecutionException(REQUEST_TOO_LARGE, "The request body is larger than "
                        + properties.maxRequestBytes() + " bytes.");
            }
            String declared = headers.stream().filter(h -> h[0].equalsIgnoreCase("content-type")).map(h -> h[1])
                    .findFirst().orElse(null);
            if (declared != null) {
                contentType = ContentType.parseLenient(declared);
            }
        }
        return new Prepared(method, uri, headers, body, contentType, null);
    }

    /** Adds the credential's header, after checking ownership/usage (resolver) and header conflicts. */
    private Prepared withCredential(Prepared prepared, JsonNode config, NodeExecutionRequest request) {
        String id = config.path("credentialId").asText("");
        if (id.isEmpty()) {
            return prepared;
        }
        ResolvedCredential credential;
        try {
            credential = credentials.resolve(UUID.fromString(id), request.context().ownerId(), nodeType());
        } catch (CredentialAccessException e) {
            throw new NodeExecutionException(e.code(), e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new NodeExecutionException(CredentialAccessException.NOT_FOUND, "Invalid credential id.");
        }
        com.flowforge.application.credential.CredentialHeaders.Header applied;
        try {
            applied = com.flowforge.application.credential.CredentialHeaders.of(credential,
                    request.context().secrets());
        } catch (CredentialAccessException e) {
            throw new NodeExecutionException(e.code(), e.getMessage());
        }
        String[] header = {applied.name(), applied.value(), CREDENTIAL_MARK};
        if (prepared.headers().stream().anyMatch(h -> h[0].equalsIgnoreCase(header[0]))) {
            throw new NodeExecutionException(CREDENTIAL_HEADER_CONFLICT, "Header '" + header[0] + "' is set both by "
                    + "the node and by credential '" + credential.name() + "'. Remove it from the node's headers.");
        }
        List<String[]> headers = new ArrayList<>(prepared.headers());
        headers.add(header);
        return new Prepared(prepared.method(), prepared.uri(), List.copyOf(headers), prepared.body(),
                prepared.contentType(), credential);
    }

    private static boolean isCredentialHeader(String[] header) {
        return header.length > 2 && CREDENTIAL_MARK.equals(header[2]);
    }

    private static Duration requestTimeout(JsonNode config) {
        int seconds = config.path("timeoutSeconds").asInt(30);
        return Duration.ofSeconds(Math.max(1, Math.min(seconds, 300)));
    }

    private void policyCheck(URI uri, boolean redirect) {
        try {
            policy.checkUrl(uri);
        } catch (OutboundPolicyException e) {
            throw new NodeExecutionException(redirect ? REDIRECT_BLOCKED : e.code(),
                    (redirect ? "Redirect to " + Redaction.url(uri) + " refused: " : "") + e.getMessage());
        }
    }

    private Prepared redirect(Prepared from, int status, String location) {
        URI target;
        try {
            target = from.uri().resolve(new URI(location.trim()));
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw new NodeExecutionException(REDIRECT_BLOCKED, "Invalid redirect location.");
        }
        boolean keepMethod = status == 307 || status == 308;
        boolean toGet = !keepMethod && !(from.method().equals("HEAD"));
        String method = keepMethod ? from.method() : toGet ? "GET" : from.method();
        boolean sameHost = from.uri().getHost().equalsIgnoreCase(target.getHost())
                && from.uri().getPort() == target.getPort() && from.uri().getScheme().equalsIgnoreCase(target.getScheme());
        List<String[]> headers = from.headers().stream()
                .filter(h -> sameHost || !(CREDENTIAL_HEADERS.contains(h[0].toLowerCase(Locale.ROOT))
                        || isCredentialHeader(h)))
                .filter(h -> keepMethod || !h[0].equalsIgnoreCase("content-type"))
                .toList();
        return new Prepared(method, target, headers, keepMethod ? from.body() : null, from.contentType(),
                sameHost ? from.credential() : null);
    }

    // --------------------------------------------------------------------------------------------------------------
    // Sending
    // --------------------------------------------------------------------------------------------------------------

    private Received send(Prepared prepared, long deadline, Duration timeout, NodeExecutionRequest request)
            throws InterruptedException {
        long remainingNanos = deadline - System.nanoTime();
        if (remainingNanos <= 0) {
            throw new NodeExecutionException(TIMEOUT, "The request did not finish within " + timeout.toSeconds()
                    + " s.");
        }
        HttpUriRequestBase http = new HttpUriRequestBase(prepared.method(), prepared.uri());
        prepared.headers().forEach(h -> http.addHeader(h[0], h[1]));
        if (prepared.body() != null) {
            http.setEntity(new ByteArrayEntity(prepared.body(), prepared.contentType()));
        }
        Timeout remaining = Timeout.of(remainingNanos, TimeUnit.NANOSECONDS);
        http.setConfig(RequestConfig.custom()
                .setResponseTimeout(remaining)
                .setConnectionRequestTimeout(remaining)
                .setRedirectsEnabled(false)
                .build());

        // Blocking socket I/O ignores interrupts: abort the request on deadline, interrupt or cancellation.
        Thread caller = Thread.currentThread();
        AtomicReference<String> abortReason = new AtomicReference<>();
        ScheduledFuture<?> guard = watchdog.scheduleAtFixedRate(() -> {
            String reason = System.nanoTime() >= deadline ? TIMEOUT
                    : caller.isInterrupted() || request.context().isCancelled() ? "CANCELLED" : null;
            if (reason != null && abortReason.compareAndSet(null, reason)) {
                http.cancel();
            }
        }, 25, 25, TimeUnit.MILLISECONDS);
        try {
            return client.execute(http, this::read);
        } catch (NodeExecutionException e) {
            throw e;
        } catch (IOException e) {
            if (TIMEOUT.equals(abortReason.get()) || isTimeout(e)) {
                throw new NodeExecutionException(TIMEOUT, prepared.method() + " " + Redaction.url(prepared.uri())
                        + " did not finish within " + timeout.toSeconds() + " s.");
            }
            if (abortReason.get() != null) {
                throw new InterruptedException("Request aborted: execution cancelled");
            }
            throw mapIoError(prepared, e);
        } finally {
            guard.cancel(false);
        }
    }

    private Received read(ClassicHttpResponse response) throws IOException {
        HttpEntity entity = response.getEntity();
        byte[] body = new byte[0];
        ContentType contentType = null;
        if (entity != null) {
            contentType = ContentType.parseLenient(entity.getContentType());
            int limit = properties.maxResponseBytes();
            if (entity.getContentLength() > limit) {
                throw new NodeExecutionException(RESPONSE_TOO_LARGE, "The response is larger than " + limit + " bytes.");
            }
            try (InputStream in = entity.getContent()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    if (out.size() + n > limit) {
                        throw new NodeExecutionException(RESPONSE_TOO_LARGE, "The response is larger than " + limit
                                + " bytes.");
                    }
                    out.write(buffer, 0, n);
                }
                body = out.toByteArray();
            }
        }
        return new Received(response.getCode(), response.getReasonPhrase(), List.of(response.getHeaders()), body,
                contentType);
    }

    private static boolean isTimeout(IOException e) {
        return e instanceof ConnectTimeoutException || e instanceof java.net.SocketTimeoutException
                || (e instanceof InterruptedIOException && e.getMessage() != null
                && e.getMessage().toLowerCase(Locale.ROOT).contains("timeout"));
    }

    private static NodeExecutionException mapIoError(Prepared prepared, IOException e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof OutboundRequestPolicy.BlockedAddressException blocked) {
                return new NodeExecutionException(OutboundRequestPolicy.ADDRESS_BLOCKED, blocked.getMessage());
            }
            cause = cause.getCause();
        }
        if (e instanceof UnknownHostException) {
            return new NodeExecutionException(DNS_ERROR, "Host '" + prepared.uri().getHost() + "' could not be resolved.");
        }
        return new NodeExecutionException(CONNECTION_ERROR, prepared.method() + " " + Redaction.url(prepared.uri())
                + " failed: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
    }

    // --------------------------------------------------------------------------------------------------------------
    // Output
    // --------------------------------------------------------------------------------------------------------------

    private JsonNode output(Prepared original, Prepared last, Received received, int redirects) {
        ObjectNode out = mapper.createObjectNode();
        out.put("status", received.status());
        if (received.reason() != null) {
            out.put("statusText", received.reason());
        }
        out.put("url", Redaction.url(last.uri()));
        out.put("redirects", redirects);

        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (Header header : received.headers()) {
            grouped.computeIfAbsent(header.getName().toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                    .add(header.getValue());
        }
        ObjectNode headers = out.putObject("headers");
        grouped.forEach((name, values) -> headers.put(name, Redaction.header(name, String.join(", ", values))));

        out.set("body", body(received));

        ObjectNode echo = out.putObject("request");
        echo.put("method", original.method());
        echo.put("url", Redaction.url(original.uri()));
        ObjectNode sent = echo.putObject("headers");
        original.headers().forEach(h -> sent.put(h[0], isCredentialHeader(h) ? Redaction.REDACTED
                : Redaction.header(h[0], h[1])));
        if (original.credential() != null) {
            ObjectNode used = echo.putObject("authenticatedWith"); // not "credential": key-name redaction would hide it
            used.put("id", original.credential().id().toString());
            used.put("name", original.credential().name());
            used.put("type", original.credential().type());
        }
        return out;
    }

    private JsonNode body(Received received) {
        if (received.body().length == 0) {
            return mapper.nullNode();
        }
        Charset charset = received.contentType() != null && received.contentType().getCharset() != null
                ? received.contentType().getCharset() : StandardCharsets.UTF_8;
        String text = new String(received.body(), charset);
        String mime = received.contentType() == null ? "" : received.contentType().getMimeType().toLowerCase(Locale.ROOT);
        if (mime.contains("json")) {
            try {
                return mapper.readTree(text);
            } catch (JsonProcessingException e) {
                return mapper.getNodeFactory().textNode(text);
            }
        }
        return mapper.getNodeFactory().textNode(text);
    }

    private static String header(List<Header> headers, String name) {
        return headers.stream().filter(h -> h.getName().equalsIgnoreCase(name)).map(Header::getValue).findFirst()
                .orElse(null);
    }
}
