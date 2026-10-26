package com.flowforge.application.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.domain.execution.ErrorDetails;
import org.junit.jupiter.api.Test;

class SecretMaskTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void masksRegisteredValuesInTextJsonFieldNamesAndUrlEncodedForm() throws Exception {
        SecretMask mask = new SecretMask();
        mask.register("p@ss word+1");
        mask.register("abc"); // too short to mask safely: ignored

        assertThat(mask.mask("user=p@ss word+1&x=p%40ss+word%2B1 abc")).isEqualTo("user=[REDACTED]&x=[REDACTED] abc");
        assertThat(mask.mask(json.readTree("{\"a\":[\"x p@ss word+1\"],\"p@ss word+1\":true,\"n\":1}")))
                .isEqualTo(json.readTree("{\"a\":[\"x [REDACTED]\"],\"[REDACTED]\":true,\"n\":1}"));
    }

    @Test
    void longerSecretsWinOverSecretsTheyContain() {
        SecretMask mask = new SecretMask();
        mask.register("token-123456");
        mask.register("token-123456-extended");
        assertThat(mask.mask("token-123456-extended")).isEqualTo("[REDACTED]");
    }

    @Test
    void masksNumericSecretsEchoedAsJsonNumbersWithoutChangingOtherNumbers() throws Exception {
        SecretMask mask = new SecretMask();
        mask.register("12345678");
        JsonNode original = json.readTree("{\"echo\":12345678,\"items\":[12345678,42]}");

        assertThat(mask.mask(original)).isEqualTo(json.readTree(
                "{\"echo\":\"[REDACTED]\",\"items\":[\"[REDACTED]\",42]}"));
        assertThat(original.path("echo").isNumber()).isTrue();
    }

    @Test
    void errorsStayValidAfterMasking() {
        SecretMask mask = new SecretMask();
        mask.register("abcdefgh");
        ErrorDetails long1 = new ErrorDetails("X", "abcdefgh ".repeat(222));
        ErrorDetails masked = mask.mask(long1);
        assertThat(masked.message()).doesNotContain("abcdefgh").hasSizeLessThanOrEqualTo(ErrorDetails.MAX_MESSAGE_LENGTH);
        assertThat(new SecretMask().mask(long1)).isSameAs(long1);
    }
}
