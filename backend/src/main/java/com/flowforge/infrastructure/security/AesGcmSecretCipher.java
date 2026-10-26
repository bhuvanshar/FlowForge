package com.flowforge.infrastructure.security;

import com.flowforge.application.credential.CredentialCryptoException;
import com.flowforge.application.credential.SecretCipher;
import com.flowforge.config.CredentialProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM with a 96-bit random nonce per encryption and a 128-bit tag. The keyring is read once at startup from
 * {@link CredentialProperties}; a malformed keyring stops the application (fail fast), an empty one disables
 * credentials. Error messages never contain key material.
 */
@Component
public class AesGcmSecretCipher implements SecretCipher {

    private static final Logger log = LoggerFactory.getLogger(AesGcmSecretCipher.class);
    private static final String KEY_ID = "[A-Za-z0-9_.-]{1,64}";
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final Map<String, SecretKey> keys;
    private final String activeKeyId;
    private final SecureRandom random = new SecureRandom();

    public AesGcmSecretCipher(CredentialProperties properties) {
        Map<String, SecretKey> parsed = new LinkedHashMap<>();
        parse(properties.keys(), "flowforge.credentials.keys", parsed);
        if (properties.keyFile() != null && !properties.keyFile().isBlank()) {
            parse(readKeyFile(Path.of(properties.keyFile().strip())), "flowforge.credentials.key-file", parsed);
        }
        this.keys = Collections.unmodifiableMap(parsed);
        if (keys.isEmpty()) {
            this.activeKeyId = null;
            log.warn("No credential encryption key configured (FLOWFORGE_CREDENTIALS_KEYS or "
                    + "FLOWFORGE_CREDENTIALS_KEY_FILE): credentials are disabled");
            return;
        }
        String active = properties.activeKeyId() == null || properties.activeKeyId().isBlank()
                ? new ArrayList<>(keys.keySet()).getLast() : properties.activeKeyId().strip();
        if (!keys.containsKey(active)) {
            throw new IllegalStateException("flowforge.credentials.active-key-id '" + active + "' is not in the keyring");
        }
        this.activeKeyId = active;
        log.info("Credential encryption enabled with {} key(s), active key '{}'", keys.size(), active);
    }

    private static void parse(String spec, String source, Map<String, SecretKey> into) {
        if (spec == null || spec.isBlank()) {
            return;
        }
        List<String> entries = spec.lines().flatMap(line -> List.of(line.split(",")).stream()).map(String::strip)
                .filter(e -> !e.isEmpty() && !e.startsWith("#")).toList();
        int index = 0;
        for (String entry : entries) {
            index++;
            int colon = entry.indexOf(':');
            String id = colon > 0 ? entry.substring(0, colon) : "";
            if (!id.matches(KEY_ID)) {
                throw new IllegalStateException(source + ": entry " + index + " must be keyId:base64Key with a key id of "
                        + "letters, digits, '_', '.' or '-'");
            }
            byte[] key;
            try {
                key = Base64.getDecoder().decode(entry.substring(colon + 1).strip());
            } catch (IllegalArgumentException e) {
                try {
                    key = Base64.getUrlDecoder().decode(entry.substring(colon + 1).strip());
                } catch (IllegalArgumentException e2) {
                    throw new IllegalStateException(source + ": key '" + id + "' is not valid base64");
                }
            }
            if (key.length != KEY_BYTES) {
                throw new IllegalStateException(source + ": key '" + id + "' must be " + KEY_BYTES
                        + " bytes (generate one with: openssl rand -base64 32)");
            }
            SecretKey previous = into.putIfAbsent(id, new SecretKeySpec(key, "AES"));
            if (previous != null && !java.security.MessageDigest.isEqual(previous.getEncoded(), key)) {
                throw new IllegalStateException(source + ": key id '" + id + "' is defined twice with different keys");
            }
        }
    }

    private static String readKeyFile(Path file) {
        try {
            try {
                Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file);
                if (permissions.stream().anyMatch(p -> p.name().startsWith("GROUP") || p.name().startsWith("OTHERS"))) {
                    log.warn("Credential key file {} is accessible by other users; restrict it (chmod 600)", file);
                }
            } catch (UnsupportedOperationException ignored) {
                // Not a POSIX file system.
            }
            return Files.readString(file);
        } catch (IOException e) {
            throw new IllegalStateException("flowforge.credentials.key-file " + file + " cannot be read");
        }
    }

    @Override
    public boolean available() {
        return activeKeyId != null;
    }

    @Override
    public String activeKeyId() {
        return activeKeyId;
    }

    @Override
    public Set<String> keyIds() {
        return keys.keySet();
    }

    @Override
    public Sealed seal(byte[] plaintext, byte[] aad) {
        if (!available()) {
            throw new CredentialCryptoException("Credential encryption is not configured on this server.");
        }
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keys.get(activeKeyId), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad);
            return new Sealed(activeKeyId, nonce, cipher.doFinal(plaintext));
        } catch (GeneralSecurityException e) {
            throw new CredentialCryptoException("Encryption failed.");
        }
    }

    @Override
    public byte[] open(Sealed sealed, byte[] aad) {
        SecretKey key = keys.get(sealed.keyId());
        if (key == null) {
            throw new CredentialCryptoException("The encryption key '" + sealed.keyId() + "' of this credential is not "
                    + "configured on this server.");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, sealed.nonce()));
            cipher.updateAAD(aad);
            return cipher.doFinal(sealed.ciphertext());
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new CredentialCryptoException("The stored secret failed authentication (wrong key or tampered data).");
        }
    }
}
