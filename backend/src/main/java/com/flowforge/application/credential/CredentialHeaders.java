package com.flowforge.application.credential;

import com.flowforge.application.execution.SecretMask;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * The HTTP header a credential contributes, shared by every node that sends credentials over HTTP. Every secret value
 * that ends up on the wire (including the derived Basic value) is registered with the run's {@link SecretMask}, so it is
 * removed from stored data, errors and logs.
 */
public final class CredentialHeaders {

    public record Header(String name, String value) {

        @Override
        public String toString() {
            return "Header[name=" + name + ", value=<hidden>]";
        }
    }

    private CredentialHeaders() {
    }

    /** @throws CredentialAccessException {@code CREDENTIAL_NOT_PERMITTED} for a type that has no HTTP form */
    public static Header of(ResolvedCredential credential, SecretMask mask) {
        return switch (credential.type()) {
            case "httpBearer" -> {
                String token = credential.secrets().get("token");
                mask.register(token);
                yield new Header("Authorization", "Bearer " + token);
            }
            case "httpBasic" -> {
                String password = credential.secrets().get("password");
                String encoded = Base64.getEncoder().encodeToString((credential.fields().get("username") + ":"
                        + password).getBytes(StandardCharsets.UTF_8));
                mask.register(password);
                mask.register(encoded);
                yield new Header("Authorization", "Basic " + encoded);
            }
            case "httpHeader" -> {
                String value = credential.secrets().get("value");
                mask.register(value);
                yield new Header(credential.fields().get("headerName"), value);
            }
            default -> throw new CredentialAccessException(CredentialAccessException.NOT_PERMITTED, "Credential type '"
                    + credential.type() + "' cannot be sent over HTTP.");
        };
    }
}
