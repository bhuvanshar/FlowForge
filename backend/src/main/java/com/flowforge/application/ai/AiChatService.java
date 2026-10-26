package com.flowforge.application.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.application.execution.NodeExecutionException;
import com.flowforge.config.AiProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

/**
 * One AI completion with a <b>bounded</b> failure policy, independent of the wire format.
 *
 * <ul>
 *   <li><b>Attempts</b>: {@code 1 + maxRetries} (0..3 by schema), never more. The whole node shares one time budget
 *       ({@code timeoutSeconds}); a retry that cannot finish its wait inside the budget is not started.</li>
 *   <li><b>Retried</b>: rate limits (429), server errors (5xx), connection failures, garbled or empty replies, and in
 *       JSON mode a reply that is not valid JSON or violates the schema (the model is shown what was wrong).</li>
 *   <li><b>Not retried</b>: authentication, bad requests, refusals, timeouts, oversize replies, policy refusals, and
 *       replies cut off by the token limit.</li>
 *   <li><b>Waiting</b>: exponential backoff from {@code retry-base-delay} (+ up to 25 % jitter) up to
 *       {@code retry-max-delay}; a provider's {@code Retry-After} replaces it when it is at most
 *       {@code max-retry-after}, otherwise the failure is final.</li>
 * </ul>
 * Usage (and provider-reported cost) is summed over all attempts.
 */
@Service
public class AiChatService {

    public static final String OUTPUT_INVALID = "AI_OUTPUT_INVALID";
    public static final String OUTPUT_TRUNCATED = "AI_OUTPUT_TRUNCATED";
    public static final String REFUSED = "AI_REFUSED";
    public static final String EMPTY = "AI_EMPTY_RESPONSE";
    public static final String TIMEOUT = "AI_TIMEOUT";
    public static final String UNKNOWN_PROVIDER = "AI_UNKNOWN_PROVIDER";

    private static final int REPAIR_ECHO_CHARS = 2000;
    private static final int PROBLEM_CHARS = 500;
    private static final long SLEEP_SLICE_MILLIS = 25;

    /**
     * @param json        JSON mode: the reply must be valid JSON (and satisfy {@code schema} when given)
     * @param jsonObject  also ask the provider to constrain the reply to a JSON object
     * @param schema      validated by {@link ResponseSchemaPolicy} before use; null = any JSON
     */
    public record Task(String provider, String baseUrl, String model, String system, String user, Double temperature,
                       Integer maxTokens, boolean json, boolean jsonObject, JsonNode schema, int maxRetries) {
    }

    /** @param json null unless the task asked for JSON */
    public record Result(String text, JsonNode json, String model, String finishReason, Usage usage, int attempts) {
    }

    private final ChatProviderRegistry providers;
    private final AiProperties properties;
    private final AiOutputParser parser;

    public AiChatService(ChatProviderRegistry providers, AiProperties properties, ObjectMapper mapper) {
        this.providers = providers;
        this.properties = properties;
        this.parser = new AiOutputParser(mapper);
    }

    public Result complete(Task task, Optional<ChatCall.Header> auth, Duration budget, BooleanSupplier cancelled,
                           Consumer<String> log) throws InterruptedException {
        ChatProvider provider = providers.find(task.provider()).orElseThrow(() ->
                new NodeExecutionException(UNKNOWN_PROVIDER, "Unknown AI provider '" + task.provider() + "'."));
        long deadline = System.nanoTime() + budget.toNanos();
        ChatCall call = new ChatCall(auth, deadline, cancelled);
        List<ChatMessage> messages = initialMessages(task);
        Usage usage = Usage.NONE;

        for (int attempt = 1; ; attempt++) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("cancelled");
            }
            if (System.nanoTime() >= deadline) {
                throw new NodeExecutionException(TIMEOUT, "The AI request did not finish within " + budget.toSeconds()
                        + " s.");
            }
            ChatResult reply;
            try {
                reply = provider.chat(new ChatRequest(task.baseUrl(), task.model(), messages, task.temperature(),
                        task.maxTokens(), task.json() && task.jsonObject()), call);
            } catch (ChatProviderException e) {
                Duration wait = e.kind().retryable() && attempt <= task.maxRetries() ? waitFor(e, attempt) : null;
                if (wait == null || wait.toNanos() >= deadline - System.nanoTime()) {
                    throw new NodeExecutionException(e.code(), failureText(e, attempt));
                }
                log.accept("AI request failed (" + e.getMessage() + "); retrying in " + wait.toMillis() + " ms (attempt "
                        + (attempt + 1) + " of " + (task.maxRetries() + 1) + ").");
                sleep(wait, cancelled);
                continue;
            }
            usage = usage.plus(reply.usage() == null ? Usage.NONE : reply.usage());

            if (reply.refusal() != null && !reply.refusal().isBlank()) {
                throw new NodeExecutionException(REFUSED, "The model refused the request: "
                        + truncate(reply.refusal(), PROBLEM_CHARS));
            }
            String text = reply.content() == null ? "" : reply.content();
            if (text.isBlank()) {
                if (attempt <= task.maxRetries()) {
                    log.accept("The model returned an empty reply; asking again (attempt " + (attempt + 1) + ").");
                    continue;
                }
                throw new NodeExecutionException(EMPTY, "The model returned an empty reply"
                        + (attempt > 1 ? " (after " + attempt + " attempts)." : "."));
            }
            if (!task.json()) {
                return new Result(text, null, reply.model(), reply.finishReason(), usage, attempt);
            }

            String problem = null;
            JsonNode json = null;
            AiOutputParser.Parsed parsed = parser.parse(text);
            if (!parsed.ok()) {
                problem = parsed.problem();
            } else {
                json = parsed.json();
                if (task.schema() != null) {
                    List<String> violations = parser.validate(task.schema(), json);
                    if (!violations.isEmpty()) {
                        problem = "the JSON does not match the response schema: " + String.join("; ", violations);
                    }
                }
            }
            if (problem == null) {
                return new Result(text, json, reply.model(), reply.finishReason(), usage, attempt);
            }
            if ("length".equals(reply.finishReason())) {
                throw new NodeExecutionException(OUTPUT_TRUNCATED, "The reply was cut off by the token limit and is "
                        + "not valid JSON. Increase 'maxTokens' or ask for a shorter answer.");
            }
            if (attempt > task.maxRetries()) {
                throw new NodeExecutionException(OUTPUT_INVALID, "The model's reply is unusable: " + truncate(problem,
                        PROBLEM_CHARS) + (attempt > 1 ? " (after " + attempt + " attempts)." : "."));
            }
            log.accept("The model's reply was unusable (" + truncate(problem, 200) + "); asking again (attempt "
                    + (attempt + 1) + " of " + (task.maxRetries() + 1) + ").");
            messages = repair(messages, text, problem);
        }
    }

    private List<ChatMessage> initialMessages(Task task) {
        List<ChatMessage> messages = new ArrayList<>();
        String system = task.system() == null ? "" : task.system().strip();
        if (task.json()) {
            // OpenAI's json_object mode also requires the word "JSON" somewhere in the messages.
            String instruction = "Respond with a single JSON value only: no prose, no Markdown, no code fences."
                    + (task.schema() != null ? " It must conform to this JSON Schema: " + task.schema() : "");
            system = system.isEmpty() ? instruction : system + "\n\n" + instruction;
        }
        if (!system.isEmpty()) {
            messages.add(ChatMessage.system(system));
        }
        messages.add(ChatMessage.user(task.user()));
        return messages;
    }

    /** Shows the model its own bad reply and what was wrong with it (bounded size, so cost stays bounded too). */
    private static List<ChatMessage> repair(List<ChatMessage> original, String badReply, String problem) {
        List<ChatMessage> messages = new ArrayList<>(original.subList(0, Math.min(original.size(), 2)));
        messages.add(ChatMessage.assistant(truncate(badReply, REPAIR_ECHO_CHARS)));
        messages.add(ChatMessage.user("That reply was rejected: " + truncate(problem, PROBLEM_CHARS)
                + ". Reply again with only a valid JSON value that satisfies the requirements."));
        return messages;
    }

    private Duration waitFor(ChatProviderException e, int attempt) {
        if (e.retryAfter() != null) {
            return e.retryAfter().compareTo(properties.maxRetryAfter()) <= 0 ? e.retryAfter() : null;
        }
        long base = properties.retryBaseDelay().toMillis();
        long exponential = Math.min(properties.retryMaxDelay().toMillis(), base * (1L << Math.min(attempt - 1, 10)));
        long jitter = ThreadLocalRandom.current().nextLong(exponential / 4 + 1);
        return Duration.ofMillis(Math.min(properties.retryMaxDelay().toMillis(), exponential + jitter));
    }

    private static String failureText(ChatProviderException e, int attempt) {
        return e.getMessage() + (attempt > 1 ? " (after " + attempt + " attempts)" : "");
    }

    private static void sleep(Duration wait, BooleanSupplier cancelled) throws InterruptedException {
        long end = System.nanoTime() + wait.toNanos();
        while (true) {
            if (cancelled.getAsBoolean()) {
                throw new InterruptedException("cancelled");
            }
            long remaining = end - System.nanoTime();
            if (remaining <= 0) {
                return;
            }
            TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(SLEEP_SLICE_MILLIS)));
        }
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
