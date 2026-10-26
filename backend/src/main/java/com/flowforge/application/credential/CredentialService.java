package com.flowforge.application.credential;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.application.ConflictException;
import com.flowforge.application.FieldValidationException;
import com.flowforge.application.FieldValidationException.Violation;
import com.flowforge.application.ResourceNotFoundException;
import com.flowforge.application.credential.CredentialTypeRegistry.CredentialType;
import com.flowforge.application.security.OwnerContext;
import com.flowforge.domain.validation.CredentialReferences;
import com.flowforge.infrastructure.persistence.CredentialEntity;
import com.flowforge.infrastructure.persistence.CredentialRepository;
import com.flowforge.infrastructure.persistence.WorkflowRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Credential management and use.
 *
 * <p><b>Secrets.</b> Secret fields are validated, serialized to JSON and sealed with AES-256-GCM ({@link SecretCipher}).
 * The associated data binds each ciphertext to its credential id, owner and type, so a ciphertext copied to another row
 * does not decrypt. No method of this service returns plaintext except {@link #resolve} (for node executors, at run time).
 *
 * <p><b>Ownership.</b> Every read and write is scoped to {@link OwnerContext#currentOwnerId()}; a credential of another
 * owner behaves exactly like a missing one (404). Runs resolve credentials as the owner of the workflow.
 *
 * <p><b>Permitted usage.</b> A credential is usable only by the node types of its type ({@code usableBy}) and is only
 * sent to its {@code allowedHosts} (checked by the HTTP Request node for every request and redirect).
 */
@Service
@Transactional(readOnly = true)
public class CredentialService implements CredentialResolver, CredentialReferences {

    public static final int MAX_NAME_LENGTH = 100;
    public static final int MAX_DESCRIPTION_LENGTH = 500;
    public static final int MAX_ALLOWED_HOSTS = 20;
    private static final String LABEL = "(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)";
    private static final Pattern HOST = Pattern.compile("^(?:\\*\\.)?(?:" + LABEL + "\\.)*" + LABEL + "$"
            + "|^\\[[0-9a-f:.]+]$");
    private static final TypeReference<Map<String, String>> SECRET_MAP = new TypeReference<>() {
    };

    private final CredentialRepository repository;
    private final WorkflowRepository workflows;
    private final CredentialTypeRegistry types;
    private final SecretCipher cipher;
    private final OwnerContext owners;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final com.flowforge.application.trigger.WebhookSecretStore webhookSecrets;

    public CredentialService(CredentialRepository repository, WorkflowRepository workflows,
                             CredentialTypeRegistry types, SecretCipher cipher, OwnerContext owners,
                             ObjectMapper mapper, Clock clock,
                             com.flowforge.application.trigger.WebhookSecretStore webhookSecrets) {
        this.webhookSecrets = webhookSecrets;
        this.repository = repository;
        this.workflows = workflows;
        this.types = types;
        this.cipher = cipher;
        this.owners = owners;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** Non-secret parts of a credential write. */
    public record Details(String name, String description, List<String> allowedHosts, Map<String, String> fields) {
    }

    /**
     * @param webhookSecretsToEncrypt webhook secrets in legacy plaintext or under an older key
     */
    public record EncryptionStatus(boolean available, String activeKeyId, long credentialsOnOldKeys,
                                   long webhookSecretsToEncrypt) {
    }

    /** What one re-encryption pass changed. */
    public record ReEncryption(int credentials, int webhookSecrets) {
    }

    public List<CredentialEntity> list(String type) {
        return repository.findByOwnerIdOrderByNameAsc(owners.currentOwnerId()).stream()
                .filter(c -> type == null || type.equals(c.getType())).toList();
    }

    public CredentialEntity get(UUID id) {
        return owned(id);
    }

    @Transactional
    public CredentialEntity create(String type, Details details, Map<String, String> secret) {
        requireEncryption();
        CredentialType credentialType = types.find(type).orElseThrow(() -> new FieldValidationException(
                List.of(new Violation("type", "must be one of " + types.all().stream().map(CredentialType::type)
                        .toList()))));
        String owner = owners.currentOwnerId();
        List<Violation> violations = new ArrayList<>(checkDetails(details));
        violations.addAll(types.validatePublic(credentialType, details.fields()));
        violations.addAll(types.validateSecret(credentialType, secret));
        throwIfAny(violations);
        String name = details.name().strip();
        if (repository.existsByOwnerIdAndNameIgnoreCase(owner, name)) {
            throw new ConflictException("A credential named '" + name + "' already exists.");
        }
        CredentialEntity entity = new CredentialEntity(UUID.randomUUID(), owner, credentialType.type());
        entity.describe(name, normalizeDescription(details.description()), fields(details),
                normalizeHosts(details.allowedHosts()));
        seal(entity, secret);
        return repository.saveAndFlush(entity);
    }

    /** Changes everything except the secret (see {@link #rotate}). */
    @Transactional
    public CredentialEntity update(UUID id, Details details, long expectedLockVersion) {
        CredentialEntity entity = owned(id);
        checkLock(entity, expectedLockVersion);
        CredentialType credentialType = typeOf(entity);
        List<Violation> violations = new ArrayList<>(checkDetails(details));
        violations.addAll(types.validatePublic(credentialType, details.fields()));
        throwIfAny(violations);
        String name = details.name().strip();
        if (repository.existsByOwnerIdAndNameIgnoreCaseAndIdNot(entity.getOwnerId(), name, id)) {
            throw new ConflictException("A credential named '" + name + "' already exists.");
        }
        entity.describe(name, normalizeDescription(details.description()), fields(details),
                normalizeHosts(details.allowedHosts()));
        return repository.saveAndFlush(entity);
    }

    /** Replaces the secret (new value, new nonce, active key); the old value is gone. */
    @Transactional
    public CredentialEntity rotate(UUID id, Map<String, String> secret, Long expectedLockVersion) {
        requireEncryption();
        CredentialEntity entity = owned(id);
        if (expectedLockVersion != null) {
            checkLock(entity, expectedLockVersion);
        }
        throwIfAny(types.validateSecret(typeOf(entity), secret));
        seal(entity, secret);
        entity.rotated(clock.instant());
        return repository.saveAndFlush(entity);
    }

    @Transactional
    public void delete(UUID id) {
        CredentialEntity entity = owned(id);
        List<CredentialInUseException.Usage> usage = usage(id);
        if (!usage.isEmpty()) {
            throw new CredentialInUseException(usage);
        }
        repository.delete(entity);
        repository.flush();
    }

    /** Workflows (of the current owner) whose definition references the credential. */
    public List<CredentialInUseException.Usage> usage(UUID id) {
        owned(id);
        return workflows.findCredentialUsage(owners.currentOwnerId(), id.toString()).stream()
                .map(u -> new CredentialInUseException.Usage(u.getId().toString(), u.getName(), u.getStatus()))
                .toList();
    }

    public EncryptionStatus encryptionStatus() {
        if (!cipher.available()) {
            return new EncryptionStatus(false, null, 0, webhookSecrets.pendingCount());
        }
        return new EncryptionStatus(true, cipher.activeKeyId(), repository.findByKeyIdNot(cipher.activeKeyId()).size(),
                webhookSecrets.pendingCount());
    }

    /**
     * Key rotation: re-encrypts every credential and webhook secret that is not on the active key, and encrypts legacy
     * plaintext webhook secrets. Old keys can be removed from the keyring afterwards. All or nothing (one transaction).
     */
    @Transactional
    public ReEncryption reEncrypt() {
        int credentials = reEncryptCredentials();
        return new ReEncryption(credentials, webhookSecrets.reEncrypt());
    }

    private int reEncryptCredentials() {
        requireEncryption();
        List<CredentialEntity> stale = repository.findByKeyIdNot(cipher.activeKeyId());
        for (CredentialEntity entity : stale) {
            byte[] plaintext = cipher.open(sealed(entity), aad(entity));
            try {
                SecretCipher.Sealed sealed = cipher.seal(plaintext, aad(entity));
                entity.seal(sealed.keyId(), sealed.nonce(), sealed.ciphertext());
            } finally {
                Arrays.fill(plaintext, (byte) 0);
            }
        }
        repository.flush();
        return stale.size();
    }

    // ----------------------------------------------------------------------------------------------------------------
    // Run-time use
    // ----------------------------------------------------------------------------------------------------------------

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ResolvedCredential resolve(UUID credentialId, String ownerId, String nodeType) {
        CredentialEntity entity = repository.findByIdAndOwnerId(credentialId, ownerId).orElseThrow(() ->
                new CredentialAccessException(CredentialAccessException.NOT_FOUND, "Credential " + credentialId
                        + " does not exist or belongs to someone else."));
        CredentialType type = types.find(entity.getType()).orElseThrow(() -> new CredentialAccessException(
                CredentialAccessException.NOT_PERMITTED, "Unknown credential type."));
        if (!type.usableBy(nodeType)) {
            throw new CredentialAccessException(CredentialAccessException.NOT_PERMITTED, "Credential '"
                    + entity.getName() + "' (" + type.label() + ") cannot be used by " + nodeType + ".");
        }
        Map<String, String> secrets;
        try {
            byte[] plaintext = cipher.open(sealed(entity), aad(entity));
            try {
                secrets = Map.copyOf(mapper.readValue(plaintext, SECRET_MAP));
            } finally {
                Arrays.fill(plaintext, (byte) 0);
            }
        } catch (CredentialCryptoException | IOException e) {
            throw new CredentialAccessException(CredentialAccessException.UNAVAILABLE, "Credential '"
                    + entity.getName() + "' cannot be decrypted: " + (e instanceof CredentialCryptoException
                    ? e.getMessage() : "invalid secret data."));
        }
        repository.markUsed(entity.getId(), clock.instant());
        return new ResolvedCredential(entity.getId(), entity.getName(), entity.getType(), entity.getAllowedHosts(),
                entity.getPublicData(), secrets);
    }

    /** Validation of {@code credentialId} references in node configurations (current owner). */
    @Override
    public Optional<String> problem(String credentialId, String nodeType) {
        UUID id;
        try {
            id = UUID.fromString(credentialId);
        } catch (IllegalArgumentException e) {
            return Optional.of("is not a credential id");
        }
        Optional<CredentialEntity> entity = repository.findByIdAndOwnerId(id, owners.currentOwnerId());
        if (entity.isEmpty()) {
            return Optional.of("refers to a credential that does not exist");
        }
        CredentialType type = typeOf(entity.get());
        if (!type.usableBy(nodeType)) {
            return Optional.of("refers to a " + type.label() + " credential, which this node cannot use");
        }
        return Optional.empty();
    }

    // ----------------------------------------------------------------------------------------------------------------

    private void seal(CredentialEntity entity, Map<String, String> secret) {
        byte[] plaintext;
        try {
            plaintext = mapper.writeValueAsBytes(Map.copyOf(secret));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Secret not serializable");
        }
        try {
            SecretCipher.Sealed sealed = cipher.seal(plaintext, aad(entity));
            entity.seal(sealed.keyId(), sealed.nonce(), sealed.ciphertext());
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    private static SecretCipher.Sealed sealed(CredentialEntity entity) {
        return new SecretCipher.Sealed(entity.getKeyId(), entity.getSecretNonce(), entity.getSecretCiphertext());
    }

    /** Binds a ciphertext to its row: id, owner and type are authenticated. */
    static byte[] aad(CredentialEntity entity) {
        return ("flowforge:credential:v1|" + entity.getId() + "|" + entity.getOwnerId() + "|" + entity.getType())
                .getBytes(StandardCharsets.UTF_8);
    }

    private void requireEncryption() {
        if (!cipher.available()) {
            throw new CredentialsUnavailableException();
        }
    }

    private CredentialEntity owned(UUID id) {
        return repository.findByIdAndOwnerId(id, owners.currentOwnerId())
                .orElseThrow(() -> new ResourceNotFoundException("Credential " + id + " not found."));
    }

    private CredentialType typeOf(CredentialEntity entity) {
        return types.find(entity.getType()).orElseThrow(() -> new IllegalStateException("Unknown credential type"));
    }

    private static void checkLock(CredentialEntity entity, long expected) {
        if (entity.getLockVersion() != expected) {
            throw new ConflictException("Credential " + entity.getId() + " was modified by someone else (expected "
                    + "lockVersion " + expected + ", current " + entity.getLockVersion() + "). Reload and retry.");
        }
    }

    private static List<Violation> checkDetails(Details details) {
        List<Violation> out = new ArrayList<>();
        String name = details.name() == null ? "" : details.name().strip();
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
            out.add(new Violation("name", "must be 1 to " + MAX_NAME_LENGTH + " characters"));
        } else if (name.chars().anyMatch(Character::isISOControl)) {
            out.add(new Violation("name", "must not contain control characters"));
        }
        if (details.description() != null && details.description().length() > MAX_DESCRIPTION_LENGTH) {
            out.add(new Violation("description", "must be at most " + MAX_DESCRIPTION_LENGTH + " characters"));
        }
        List<String> hosts = details.allowedHosts() == null ? List.of() : details.allowedHosts();
        if (hosts.isEmpty() || hosts.size() > MAX_ALLOWED_HOSTS) {
            out.add(new Violation("allowedHosts", "needs 1 to " + MAX_ALLOWED_HOSTS + " hosts the credential may be "
                    + "sent to"));
        }
        for (int i = 0; i < hosts.size(); i++) {
            String host = hosts.get(i) == null ? "" : hosts.get(i).strip().toLowerCase(Locale.ROOT);
            if (host.length() > 253 || !HOST.matcher(host).matches()) {
                out.add(new Violation("allowedHosts[" + i + "]", "must be a host name such as api.example.com or "
                        + "*.example.com"));
            }
        }
        return out;
    }

    private static Map<String, String> fields(Details details) {
        return details.fields() == null ? Map.of() : Map.copyOf(details.fields());
    }

    private static List<String> normalizeHosts(List<String> hosts) {
        return List.copyOf(new LinkedHashSet<>(hosts.stream().map(h -> h.strip().toLowerCase(Locale.ROOT)).toList()));
    }

    private static String normalizeDescription(String description) {
        return description == null || description.isBlank() ? null : description.strip();
    }

    private static void throwIfAny(List<Violation> violations) {
        if (!violations.isEmpty()) {
            throw new FieldValidationException(violations);
        }
    }
}
