package com.flowforge.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * A stored credential. The secret fields exist only as AES-GCM ciphertext; this entity never holds plaintext secrets.
 * {@link #toString()} is the default identity string, so logging an entity cannot leak anything.
 */
@Entity
@Table(name = "credentials")
public class CredentialEntity {

    @Id
    private UUID id;

    @Column(name = "owner_id", nullable = false, updatable = false, length = 64)
    private String ownerId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, updatable = false, length = 40)
    private String type;

    @Column(length = 500)
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "public_data", nullable = false, columnDefinition = "jsonb")
    private Map<String, String> publicData;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allowed_hosts", nullable = false, columnDefinition = "jsonb")
    private List<String> allowedHosts;

    @Column(name = "key_id", nullable = false, length = 64)
    private String keyId;

    @Column(name = "secret_nonce", nullable = false)
    private byte[] secretNonce;

    @Column(name = "secret_ciphertext", nullable = false)
    private byte[] secretCiphertext;

    @Column(name = "secret_version", nullable = false)
    private int secretVersion;

    @Column(name = "rotated_at")
    private Instant rotatedAt;

    @Column(name = "last_used_at", insertable = false, updatable = false)
    private Instant lastUsedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "lock_version", nullable = false)
    private Long lockVersion;

    protected CredentialEntity() {
    }

    public CredentialEntity(UUID id, String ownerId, String type) {
        this.id = Objects.requireNonNull(id);
        this.ownerId = Objects.requireNonNull(ownerId);
        this.type = Objects.requireNonNull(type);
        this.secretVersion = 1;
    }

    public void describe(String name, String description, Map<String, String> publicData, List<String> allowedHosts) {
        this.name = Objects.requireNonNull(name);
        this.description = description;
        this.publicData = Map.copyOf(publicData);
        this.allowedHosts = List.copyOf(allowedHosts);
    }

    /** Stores a new ciphertext (creation, rotation or re-encryption). */
    public void seal(String keyId, byte[] nonce, byte[] ciphertext) {
        this.keyId = Objects.requireNonNull(keyId);
        this.secretNonce = nonce.clone();
        this.secretCiphertext = ciphertext.clone();
    }

    public void rotated(Instant at) {
        this.secretVersion++;
        this.rotatedAt = at;
    }

    public UUID getId() {
        return id;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public String getName() {
        return name;
    }

    public String getType() {
        return type;
    }

    public String getDescription() {
        return description;
    }

    public Map<String, String> getPublicData() {
        return publicData;
    }

    public List<String> getAllowedHosts() {
        return allowedHosts;
    }

    public String getKeyId() {
        return keyId;
    }

    public byte[] getSecretNonce() {
        return secretNonce.clone();
    }

    public byte[] getSecretCiphertext() {
        return secretCiphertext.clone();
    }

    public int getSecretVersion() {
        return secretVersion;
    }

    public Instant getRotatedAt() {
        return rotatedAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getLockVersion() {
        return lockVersion;
    }
}
