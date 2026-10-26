package com.flowforge.domain.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ErrorDetailsTest {

    @Test
    void keepsValidCodeAndPlainMessage() {
        ErrorDetails error = ErrorDetails.sanitized("HTTP_TIMEOUT", "Request timed out after 30s");

        assertThat(error).isEqualTo(new ErrorDetails("HTTP_TIMEOUT", "Request timed out after 30s"));
    }

    @Test
    void replacesInvalidCodeWithUnknown() {
        assertThat(ErrorDetails.sanitized("java.lang.NullPointerException", "x").code())
                .isEqualTo(ErrorDetails.UNKNOWN_CODE);
        assertThat(ErrorDetails.sanitized(null, "x").code()).isEqualTo(ErrorDetails.UNKNOWN_CODE);
    }

    @Test
    void redactsCredentialsAndStripsControlCharacters() {
        ErrorDetails error = ErrorDetails.sanitized("AUTH_FAILED",
                "login failed password=hunter2 api_key: \"abc 123\"\u0000 header Authorization=Bearer eyJhbGci.x.y");

        assertThat(error.message())
                .doesNotContain("hunter2", "abc 123", "eyJhbGci", "\u0000")
                .contains("password=[REDACTED]", "api_key: [REDACTED]");
    }

    @Test
    void redactsStandaloneBearerTokens() {
        assertThat(ErrorDetails.sanitized("AUTH_FAILED", "sent Bearer abc.def-ghi").message())
                .isEqualTo("sent Bearer [REDACTED]");
    }

    @Test
    void truncatesLongMessages() {
        ErrorDetails error = ErrorDetails.sanitized("BIG", "x".repeat(5000));

        assertThat(error.message()).hasSize(ErrorDetails.MAX_MESSAGE_LENGTH).endsWith("…");
    }

    @Test
    void blankMessageBecomesNull() {
        assertThat(ErrorDetails.sanitized("X", "   ").message()).isNull();
    }

    @Test
    void constructorRejectsUnsanitizedInput() {
        assertThatThrownBy(() -> new ErrorDetails("lower", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ErrorDetails("X", "x".repeat(2001))).isInstanceOf(IllegalArgumentException.class);
    }
}
