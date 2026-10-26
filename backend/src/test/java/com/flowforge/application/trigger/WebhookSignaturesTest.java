package com.flowforge.application.trigger;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class WebhookSignaturesTest {

    @Test
    void signsTimestampDotBodyWithHmacSha256() {
        // Reference value: printf '1700000000.{"a":1}' | openssl dgst -sha256 -hmac secret
        String signature = WebhookSignatures.sign("secret", "1700000000", "{\"a\":1}".getBytes(StandardCharsets.UTF_8));
        assertThat(signature).isEqualTo("sha256=" + "49f24e537407743fa4a0242bb63b94b9a47ee99cbbe071ccd8a22550ae411686");
    }

    @Test
    void comparisonIsExact() {
        assertThat(WebhookSignatures.matches("abc", "abc")).isTrue();
        assertThat(WebhookSignatures.matches("abc", "abd")).isFalse();
        assertThat(WebhookSignatures.matches("abc", "abcd")).isFalse();
        assertThat(WebhookSignatures.matches("abc", null)).isFalse();
    }

    @Test
    void replayGuardRejectsRepeatsUntilExpiryAndRefusesWhenFull() {
        ReplayGuard guard = new ReplayGuard(2);
        Instant now = Instant.parse("2026-03-01T10:00:00Z");
        assertThat(guard.record("a", now.plusSeconds(300), now)).isEqualTo(ReplayGuard.Result.ACCEPTED);
        assertThat(guard.record("a", now.plusSeconds(300), now.plusSeconds(10))).isEqualTo(ReplayGuard.Result.REPLAYED);
        assertThat(guard.record("b", now.plusSeconds(20), now)).isEqualTo(ReplayGuard.Result.ACCEPTED);
        assertThat(guard.record("c", now.plusSeconds(300), now)).isEqualTo(ReplayGuard.Result.FULL);

        // "b" expired: purged to make room; an expired key may be recorded again.
        Instant later = now.plusSeconds(30);
        assertThat(guard.record("c", later.plusSeconds(300), later)).isEqualTo(ReplayGuard.Result.ACCEPTED);
        assertThat(guard.size()).isEqualTo(2);
    }
}
