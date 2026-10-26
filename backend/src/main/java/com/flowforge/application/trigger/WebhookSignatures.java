package com.flowforge.application.trigger;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Webhook request signing: {@code X-FlowForge-Signature: sha256=<hex(HMAC-SHA256(secret, timestamp + "." + body))>}
 * where {@code timestamp} is the value of {@code X-FlowForge-Timestamp} (Unix seconds) and {@code body} the raw bytes.
 */
public final class WebhookSignatures {

    public static final String PREFIX = "sha256=";

    private WebhookSignatures() {
    }

    public static String sign(String secret, String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(timestamp.getBytes(StandardCharsets.US_ASCII));
            mac.update((byte) '.');
            mac.update(body);
            return PREFIX + HexFormat.of().formatHex(mac.doFinal());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    /** Constant-time comparison of two secrets or signatures (compares digests, so lengths do not leak either). */
    public static boolean matches(String expected, String actual) {
        if (actual == null) {
            return false;
        }
        return MessageDigest.isEqual(sha256(expected), sha256(actual));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
