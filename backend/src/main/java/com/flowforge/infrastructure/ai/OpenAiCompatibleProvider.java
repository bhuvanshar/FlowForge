package com.flowforge.infrastructure.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowforge.application.ai.ChatCall;
import com.flowforge.application.ai.ChatMessage;
import com.flowforge.application.ai.ChatProvider;
import com.flowforge.application.ai.ChatProviderException;
import com.flowforge.application.ai.ChatProviderException.Kind;
import com.flowforge.application.ai.ChatRequest;
import com.flowforge.application.ai.ChatResult;
import com.flowforge.application.ai.Usage;
import com.flowforge.config.AiProperties;
import com.flowforge.infrastructure.http.OutboundPolicyException;
import com.flowforge.infrastructure.http.OutboundRequestPolicy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
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
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;

/**
 * {@code openaiCompatible}: {@code POST <baseUrl>/chat/completions} as implemented by OpenAI and the many servers that
 * copy its API (OpenRouter, Groq, Ollama, vLLM, ...).
 *
 * <p>The request goes through the same guards as the HTTP Request node: allowlisted hosts and ports, no non-public
 * addresses (checked when connecting, so no DNS rebinding), http/https only. <b>Redirects are never followed</b> (a
 * redirect would carry the API key to another place). Responses are size-capped, the attempt is bounded by the call's
 * deadline and is aborted on cancellation.
 *
 * <p>Wire details: {@code max_tokens} is sent when the node sets a limit (the widely supported spelling; providers that
 * only accept {@code max_completion_tokens} need that limit left empty), {@code response_format: json_object} when JSON
 * mode asks for it. Usage, and {@code usage.cost} when the provider reports it, are passed on.
 */
@Component
public class OpenAiCompatibleProvider implements ChatProvider {

    public static final String ID = "openaiCompatible";
    private static final int ERROR_BODY_LIMIT = 16 * 1024;
    private static final int ERROR_MESSAGE_CHARS = 300;

    private final CloseableHttpClient client;
    private final OutboundRequestPolicy policy;
    private final AiProperties properties;
    private final ObjectMapper mapper;
    private final ScheduledExecutorService watchdog;

    public OpenAiCompatibleProvider(CloseableHttpClient workflowHttpClient, OutboundRequestPolicy policy,
                                    AiProperties properties, ObjectMapper mapper, ScheduledExecutorService httpWatchdog) {
        this.client = workflowHttpClient;
        this.policy = policy;
        this.properties = properties;
        this.mapper = mapper;
        this.watchdog = httpWatchdog;
    }

    @Override
    public String id() {
        return ID;
    }

    private record Received(int status, byte[] body, Duration retryAfter) {
    }

    @Override
    public ChatResult chat(ChatRequest request, ChatCall call) throws InterruptedException {
        URI uri = endpoint(request.baseUrl());
        byte[] body = body(request);
        Received received = send(uri, body, call);

        int status = received.status();
        if (status >= 200 && status < 300) {
            return parse(received.body());
        }
        throw failure(status, received);
    }

    // --------------------------------------------------------------------------------------------------------------

    private URI endpoint(String baseUrl) {
        URI uri;
        try {
            String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            uri = new URI(base + "/chat/completions");
            policy.checkUrl(uri);
        } catch (java.net.URISyntaxException e) {
            throw new ChatProviderException(Kind.REFUSED_BY_POLICY, "The base URL is not a valid URL.", null, null,
                    OutboundRequestPolicy.INVALID_URL);
        } catch (OutboundPolicyException e) {
            throw new ChatProviderException(Kind.REFUSED_BY_POLICY, e.getMessage(), null, null, e.code());
        }
        return uri;
    }

    private byte[] body(ChatRequest request) {
        ObjectNode json = mapper.createObjectNode();
        json.put("model", request.model());
        ArrayNode messages = json.putArray("messages");
        for (ChatMessage message : request.messages()) {
            messages.addObject().put("role", message.role()).put("content", message.content());
        }
        if (request.temperature() != null) {
            json.put("temperature", request.temperature());
        }
        if (request.maxTokens() != null) {
            json.put("max_tokens", request.maxTokens());
        }
        if (request.jsonObject()) {
            json.putObject("response_format").put("type", "json_object");
        }
        json.put("stream", false);
        try {
            return mapper.writeValueAsBytes(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Chat request not serializable", e);
        }
    }

    private Received send(URI uri, byte[] body, ChatCall call) throws InterruptedException {
        long remainingNanos = call.deadlineNanos() - System.nanoTime();
        if (remainingNanos <= 0) {
            throw new ChatProviderException(Kind.TIMEOUT, "The AI request did not finish in time.");
        }
        HttpUriRequestBase http = new HttpUriRequestBase("POST", uri);
        http.addHeader("Accept", "application/json");
        call.auth().ifPresent(h -> http.addHeader(h.name(), h.value()));
        http.setEntity(new ByteArrayEntity(body, ContentType.APPLICATION_JSON));
        Timeout remaining = Timeout.of(remainingNanos, TimeUnit.NANOSECONDS);
        http.setConfig(RequestConfig.custom().setResponseTimeout(remaining).setConnectionRequestTimeout(remaining)
                .setRedirectsEnabled(false).build());

        Thread caller = Thread.currentThread();
        AtomicReference<String> abortReason = new AtomicReference<>();
        ScheduledFuture<?> guard = watchdog.scheduleAtFixedRate(() -> {
            String reason = System.nanoTime() >= call.deadlineNanos() ? "TIMEOUT"
                    : caller.isInterrupted() || call.cancelled().getAsBoolean() ? "CANCELLED" : null;
            if (reason != null && abortReason.compareAndSet(null, reason)) {
                http.cancel();
            }
        }, 25, 25, TimeUnit.MILLISECONDS);
        try {
            return client.execute(http, this::read);
        } catch (ChatProviderException e) {
            throw e;
        } catch (IOException e) {
            if ("TIMEOUT".equals(abortReason.get()) || isTimeout(e)) {
                throw new ChatProviderException(Kind.TIMEOUT, "The AI request did not finish in time.");
            }
            if (abortReason.get() != null) {
                throw new InterruptedException("Request aborted: execution cancelled");
            }
            throw mapIoError(uri, e);
        } finally {
            guard.cancel(false);
        }
    }

    private Received read(ClassicHttpResponse response) throws IOException {
        int status = response.getCode();
        boolean ok = status >= 200 && status < 300;
        int limit = ok ? properties.maxResponseBytes() : ERROR_BODY_LIMIT;
        HttpEntity entity = response.getEntity();
        byte[] body = new byte[0];
        if (entity != null) {
            if (ok && entity.getContentLength() > limit) {
                throw new ChatProviderException(Kind.TOO_LARGE, "The response is larger than " + limit + " bytes.");
            }
            try (InputStream in = entity.getContent()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    if (out.size() + n > limit) {
                        if (ok) {
                            throw new ChatProviderException(Kind.TOO_LARGE, "The response is larger than " + limit
                                    + " bytes.");
                        }
                        out.write(buffer, 0, limit - out.size()); // error bodies are only read for a short message
                        break;
                    }
                    out.write(buffer, 0, n);
                }
                body = out.toByteArray();
            }
        }
        return new Received(status, body, retryAfter(response.getFirstHeader("Retry-After")));
    }

    private static Duration retryAfter(org.apache.hc.core5.http.Header header) {
        if (header == null || header.getValue() == null) {
            return null;
        }
        try {
            long seconds = Long.parseLong(header.getValue().strip());
            return seconds < 0 ? null : Duration.ofSeconds(seconds);
        } catch (NumberFormatException e) {
            return null; // HTTP-date form: ignored, the normal backoff applies
        }
    }

    // --------------------------------------------------------------------------------------------------------------

    private ChatProviderException failure(int status, Received received) {
        String detail = providerMessage(received.body());
        if (status == 401 || status == 403) {
            // Provider texts for these often quote part of the key ("Incorrect API key provided: sk-...abcd").
            return new ChatProviderException(Kind.AUTH, "The provider rejected the credentials (HTTP " + status
                    + "). Check the credential of this node.", status, null, null);
        }
        if (status == 429) {
            return new ChatProviderException(Kind.RATE_LIMITED, "The provider is rate limiting requests (HTTP 429)"
                    + suffix(detail), status, received.retryAfter(), null);
        }
        if (status >= 500) {
            return new ChatProviderException(Kind.SERVER_ERROR, "The provider failed (HTTP " + status + ")"
                    + suffix(detail), status, received.retryAfter(), null);
        }
        if (status >= 300 && status < 400) {
            return new ChatProviderException(Kind.REFUSED_BY_POLICY, "The provider answered with a redirect (HTTP "
                    + status + "), which is not followed. Use the final base URL.", status, null, null);
        }
        return new ChatProviderException(Kind.BAD_REQUEST, "The provider rejected the request (HTTP " + status + ")"
                + suffix(detail), status, null, null);
    }

    private static String suffix(String detail) {
        return detail.isEmpty() ? "." : ": " + detail;
    }

    /** {@code error.message} (OpenAI style) or {@code message}/{@code detail}, without control characters, shortened. */
    private String providerMessage(byte[] body) {
        if (body.length == 0) {
            return "";
        }
        String text = null;
        try {
            JsonNode json = mapper.readTree(body);
            JsonNode error = json.path("error");
            text = error.isTextual() ? error.textValue() : error.path("message").asText(null);
            if (text == null) {
                text = json.path("message").asText(json.path("detail").asText(null));
            }
        } catch (IOException e) {
            // Not JSON (an HTML error page, ...): no message.
        }
        if (text == null) {
            return "";
        }
        text = text.replaceAll("\\p{Cntrl}+", " ").strip();
        return text.length() > ERROR_MESSAGE_CHARS ? text.substring(0, ERROR_MESSAGE_CHARS) + "…" : text;
    }

    private ChatResult parse(byte[] body) {
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (IOException e) {
            throw new ChatProviderException(Kind.BAD_RESPONSE, "The provider's reply is not valid JSON.");
        }
        JsonNode choice = root.path("choices").path(0);
        if (!choice.isObject()) {
            throw new ChatProviderException(Kind.BAD_RESPONSE, "The provider's reply has no choices.");
        }
        JsonNode message = choice.path("message");
        String refusal = message.path("refusal").isTextual() ? message.path("refusal").textValue() : null;
        return new ChatResult(content(message.path("content")), refusal, choice.path("finish_reason").asText(null),
                root.path("model").asText(null), usage(root.path("usage")));
    }

    /** {@code content} is a string, or (some servers) a list of {@code {type: text, text}} parts. */
    private static String content(JsonNode content) {
        if (content.isTextual()) {
            return content.textValue();
        }
        if (content.isArray()) {
            StringBuilder out = new StringBuilder();
            content.forEach(part -> {
                if (part.path("text").isTextual()) {
                    out.append(part.path("text").textValue());
                }
            });
            return out.toString();
        }
        return "";
    }

    private static Usage usage(JsonNode usage) {
        if (!usage.isObject()) {
            return Usage.NONE;
        }
        return new Usage(count(usage.path("prompt_tokens")), count(usage.path("completion_tokens")),
                count(usage.path("total_tokens")), usage.path("cost").isNumber() && usage.path("cost").decimalValue()
                .signum() >= 0 ? usage.path("cost").decimalValue() : null);
    }

    private static Long count(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0 ? value.longValue() : null;
    }

    private static boolean isTimeout(IOException e) {
        return e instanceof ConnectTimeoutException || e instanceof java.net.SocketTimeoutException
                || (e instanceof InterruptedIOException && e.getMessage() != null
                && e.getMessage().toLowerCase(Locale.ROOT).contains("timeout"));
    }

    private static ChatProviderException mapIoError(URI uri, IOException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof OutboundRequestPolicy.BlockedAddressException blocked) {
                return new ChatProviderException(Kind.REFUSED_BY_POLICY, blocked.getMessage(), null, null,
                        OutboundRequestPolicy.ADDRESS_BLOCKED);
            }
        }
        if (e instanceof UnknownHostException) {
            return new ChatProviderException(Kind.CONNECTION, "Host '" + uri.getHost() + "' could not be resolved.");
        }
        return new ChatProviderException(Kind.CONNECTION, "Could not reach " + uri.getHost() + ": "
                + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
    }
}
