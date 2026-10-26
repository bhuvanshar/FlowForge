package com.flowforge.api.dto;

import com.flowforge.application.credential.CredentialTypeRegistry;
import com.flowforge.infrastructure.persistence.CredentialEntity;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A credential without its secret. {@code secretFields} names the stored secret fields (values are never returned);
 * {@code secretVersion} increments on every rotation.
 */
public record CredentialResponse(UUID id, String name, String type, String description, List<String> allowedHosts,
                                 Map<String, String> fields, List<String> secretFields, int secretVersion,
                                 Instant createdAt, Instant updatedAt, Instant rotatedAt, Instant lastUsedAt,
                                 long lockVersion) {

    public static CredentialResponse from(CredentialEntity e, CredentialTypeRegistry types) {
        List<String> secretFields = types.find(e.getType())
                .map(t -> t.secretFields().stream().map(CredentialTypeRegistry.Field::name).toList()).orElse(List.of());
        return new CredentialResponse(e.getId(), e.getName(), e.getType(), e.getDescription(), e.getAllowedHosts(),
                e.getPublicData(), secretFields, e.getSecretVersion(), e.getCreatedAt(), e.getUpdatedAt(),
                e.getRotatedAt(), e.getLastUsedAt(), e.getLockVersion());
    }
}
