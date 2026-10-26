package com.flowforge.application.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class PayloadRedactorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode json(String text) throws Exception {
        return JSON.readTree(text);
    }

    @Test
    void masksCredentialLookingKeysAtAnyDepthAndInArrays() throws Exception {
        JsonNode in = json("{\"user\":\"ada\",\"password\":\"pw\",\"headers\":{\"Authorization\":\"Bearer abc.def.ghi\","
                + "\"Set-Cookie\":\"s=1\",\"X-Api-Key\":\"k\",\"Accept\":\"*/*\"},\"items\":[{\"access_token\":\"t\",\"id\":1}],"
                + "\"nothing\":null,\"clientSecret\":\"x\"}");

        JsonNode out = PayloadRedactor.redact(in);

        assertThat(out).isEqualTo(json("{\"user\":\"ada\",\"password\":\"[REDACTED]\",\"headers\":{\"Authorization\":\"[REDACTED]\","
                + "\"Set-Cookie\":\"[REDACTED]\",\"X-Api-Key\":\"[REDACTED]\",\"Accept\":\"*/*\"},"
                + "\"items\":[{\"access_token\":\"[REDACTED]\",\"id\":1}],\"nothing\":null,\"clientSecret\":\"[REDACTED]\"}"));
        assertThat(in.get("password").asText()).isEqualTo("pw"); // original untouched
    }

    @Test
    void masksAuthSchemesInsideText() throws Exception {
        assertThat(PayloadRedactor.redact(json("\"sent Authorization: Bearer abcdef123456 to host\"")).asText())
                .isEqualTo("sent Authorization: Bearer [REDACTED] to host");
        assertThat(PayloadRedactor.redact(json("\"Basic dXNlcjpwYXNz\"")).asText()).isEqualTo("Basic [REDACTED]");
        assertThat(PayloadRedactor.redact(json("\"basic idea\"")).asText()).isEqualTo("basic idea");
    }

    @Test
    void leavesOrdinaryDataAlone() throws Exception {
        JsonNode in = json("{\"n\":1.5,\"ok\":true,\"list\":[1,\"a\",null],\"nested\":{\"x\":\"y\"}}");
        assertThat(PayloadRedactor.redact(in)).isEqualTo(in);
        assertThat(PayloadRedactor.redact(null)).isNull();
    }
}
