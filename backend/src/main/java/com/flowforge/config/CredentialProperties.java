package com.flowforge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Credential encryption keys ({@code flowforge.credentials.*}). Keys are never stored in the database or the repository.
 *
 * @param keys        {@code keyId:base64Key[,keyId:base64Key...]}, each key 32 random bytes (AES-256), usually from
 *                    {@code FLOWFORGE_CREDENTIALS_KEYS}
 * @param keyFile     optional file with one {@code keyId:base64Key} per line ({@code #} comments allowed), merged with
 *                    {@code keys}; should be readable by the server user only
 * @param activeKeyId key for new encryptions; default: the last key listed. Older keys stay usable for decryption until
 *                    every credential was re-encrypted ({@code POST /api/v1/credentials/re-encrypt})
 */
@ConfigurationProperties(prefix = "flowforge.credentials")
public record CredentialProperties(String keys, String keyFile, String activeKeyId) {
}
