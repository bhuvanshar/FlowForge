package com.flowforge.application.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.application.ai.ChatProviderException.Kind;
import com.flowforge.application.execution.NodeExecutionException;
import com.flowforge.config.AiProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** The retry policy in isolation, with a scripted provider (no HTTP). */
class AiChatServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Plays back outcomes: a ChatResult, or a ChatProviderException to throw. */
    private static final class Script implements ChatProvider {
        final List<Object> outcomes = new ArrayList<>();
        final List<ChatRequest> requests = new ArrayList<>();

        @Override
        public String id() {
            return "scripted";
        }

        @Override
        public ChatResult chat(ChatRequest request, ChatCall call) {
            requests.add(request);
            Object next = outcomes.get(Math.min(requests.size(), outcomes.size()) - 1);
            if (next instanceof ChatProviderException e) {
                throw e;
            }
            return (ChatResult) next;
        }
    }

    private static ChatResult reply(String content) {
        return new ChatResult(content, null, "stop", "m", new Usage(1L, 1L, 2L, null));
    }

    private static AiChatService service(Script script, Duration base, Duration retryAfterLimit) {
        return new AiChatService(new ChatProviderRegistry(List.of(script)),
                new AiProperties(null, null, base, Duration.ofMillis(200), retryAfterLimit), JSON);
    }

    private static AiChatService.Task task(boolean json, int retries) {
        return new AiChatService.Task("scripted", "http://x/v1", "m", "", "hi", null, null, json, json, null, retries);
    }

    @Test
    void neverMakesMoreThanOnePlusMaxRetriesAttempts() {
        Script script = new Script();
        script.outcomes.add(new ChatProviderException(Kind.SERVER_ERROR, "down", 503, null, null));
        AiChatService service = service(script, Duration.ofMillis(1), Duration.ofSeconds(1));

        for (int retries = 0; retries <= 3; retries++) {
            script.requests.clear();
            int expected = retries + 1;
            int r = retries;
            assertThatThrownBy(() -> service.complete(task(false, r), Optional.empty(), Duration.ofSeconds(10),
                    () -> false, m -> { })).isInstanceOf(NodeExecutionException.class);
            assertThat(script.requests).hasSize(expected);
        }
    }

    @Test
    void aWaitThatCannotFitIntoTheBudgetIsNotStarted() {
        Script script = new Script();
        script.outcomes.add(new ChatProviderException(Kind.RATE_LIMITED, "slow", 429, Duration.ofSeconds(5), null));
        AiChatService service = service(script, Duration.ofMillis(1), Duration.ofSeconds(30));
        long started = System.nanoTime();

        assertThatThrownBy(() -> service.complete(task(false, 3), Optional.empty(), Duration.ofSeconds(2),
                () -> false, m -> { })).isInstanceOfSatisfying(NodeExecutionException.class,
                e -> assertThat(e.code()).isEqualTo("AI_RATE_LIMITED"));

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
        assertThat(script.requests).hasSize(1);
    }

    @Test
    void nonRetryableKindsFailAtOnce() {
        for (Kind kind : new Kind[] {Kind.AUTH, Kind.BAD_REQUEST, Kind.TIMEOUT, Kind.TOO_LARGE, Kind.REFUSED_BY_POLICY}) {
            Script script = new Script();
            script.outcomes.add(new ChatProviderException(kind, "x"));
            assertThatThrownBy(() -> service(script, Duration.ofMillis(1), Duration.ofSeconds(1)).complete(task(false, 3),
                    Optional.empty(), Duration.ofSeconds(5), () -> false, m -> { }))
                    .isInstanceOfSatisfying(NodeExecutionException.class, e -> assertThat(e.code()).isEqualTo(kind.code()));
            assertThat(script.requests).as(kind.name()).hasSize(1);
        }
    }

    @Test
    void retriesLogWhatHappenedAndFinalSuccessReportsTheAttemptCount() throws Exception {
        Script script = new Script();
        script.outcomes.add(new ChatProviderException(Kind.CONNECTION, "no route", null, null, null));
        script.outcomes.add(reply("done"));
        List<String> log = new ArrayList<>();

        AiChatService.Result result = service(script, Duration.ofMillis(1), Duration.ofSeconds(1)).complete(task(false, 2),
                Optional.empty(), Duration.ofSeconds(5), () -> false, log::add);

        assertThat(result.attempts()).isEqualTo(2);
        assertThat(result.text()).isEqualTo("done");
        assertThat(log).singleElement().asString().contains("no route").contains("attempt 2 of 3");
    }

    @Test
    void cancellationDuringTheBackoffWaitStopsTheNode() {
        Script script = new Script();
        script.outcomes.add(new ChatProviderException(Kind.RATE_LIMITED, "slow", 429, Duration.ofSeconds(2), null));
        AiChatService service = service(script, Duration.ofMillis(1), Duration.ofSeconds(5));
        AtomicBoolean cancelled = new AtomicBoolean();
        new Thread(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
                // helper thread
            }
            cancelled.set(true);
        }).start();

        assertThatThrownBy(() -> service.complete(task(false, 3), Optional.empty(), Duration.ofSeconds(30),
                cancelled::get, m -> { })).isInstanceOf(InterruptedException.class);
        assertThat(script.requests).hasSize(1);
    }

    @Test
    void theRepairRequestIsBoundedInSize() throws Exception {
        Script script = new Script();
        script.outcomes.add(reply("x".repeat(50_000)));
        script.outcomes.add(reply("{\"ok\":true}"));

        service(script, Duration.ofMillis(1), Duration.ofSeconds(1)).complete(task(true, 1), Optional.empty(),
                Duration.ofSeconds(5), () -> false, m -> { });

        List<ChatMessage> second = script.requests.get(1).messages();
        assertThat(second.get(second.size() - 2).content()).hasSizeLessThan(2100);
        assertThat(second.get(second.size() - 1).content()).hasSizeLessThan(700);
    }

    @Test
    void cancellationBeforeTheFirstAttemptNeverCallsTheProvider() {
        Script script = new Script();
        script.outcomes.add(reply("done"));
        assertThatThrownBy(() -> service(script, Duration.ofMillis(1), Duration.ofSeconds(1))
                .complete(task(false, 3), Optional.empty(), Duration.ofSeconds(5), () -> true, m -> { }))
                .isInstanceOf(InterruptedException.class);
        assertThat(script.requests).isEmpty();
    }

    @Test
    void cancellationBetweenRepairAttemptsDoesNotSendAnotherRequest() {
        Script script = new Script();
        script.outcomes.add(reply("not JSON"));
        script.outcomes.add(reply("{}"));
        AtomicBoolean cancelled = new AtomicBoolean();
        assertThatThrownBy(() -> service(script, Duration.ofMillis(1), Duration.ofSeconds(1))
                .complete(task(true, 3), Optional.empty(), Duration.ofSeconds(5), cancelled::get,
                        m -> cancelled.set(true))).isInstanceOf(InterruptedException.class);
        assertThat(script.requests).hasSize(1);
    }

    @Test
    void unknownProvidersFail() {
        AiChatService service = new AiChatService(new ChatProviderRegistry(List.of()), AiProperties.defaults(), JSON);
        assertThatThrownBy(() -> service.complete(task(false, 0), Optional.empty(), Duration.ofSeconds(1), () -> false,
                m -> { })).isInstanceOfSatisfying(NodeExecutionException.class,
                e -> assertThat(e.code()).isEqualTo(AiChatService.UNKNOWN_PROVIDER));
    }
}
