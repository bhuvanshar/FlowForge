package com.flowforge.api;

import com.flowforge.api.dto.CredentialRequests;
import com.flowforge.api.dto.CredentialResponse;
import com.flowforge.application.credential.CredentialInUseException;
import com.flowforge.application.credential.CredentialService;
import com.flowforge.application.credential.CredentialTypeRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Credentials. No endpoint returns secret values: they are write-only (create, rotate) and only decrypted inside a run.
 */
@RestController
@RequestMapping(ApiPaths.BASE + "/credentials")
@Tag(name = "Credentials", description = "Encrypted credentials for external integrations (secrets are write-only)")
@ApiResponse(responseCode = "404", description = "Not found (or owned by someone else)",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
public class CredentialController {

    private final CredentialService service;
    private final CredentialTypeRegistry types;

    public CredentialController(CredentialService service, CredentialTypeRegistry types) {
        this.service = service;
        this.types = types;
    }

    public record UsageResponse(List<CredentialInUseException.Usage> workflows) {
    }

    /** @param reEncrypted credentials changed; {@code webhookSecrets}: webhook secrets encrypted or re-encrypted */
    public record ReEncryptResponse(int reEncrypted, int webhookSecrets, String activeKeyId) {
    }

    @GetMapping
    @Operation(summary = "List credentials (without secrets)", description = "Sorted by name; optional type filter.")
    @ApiResponse(responseCode = "200", description = "Credentials, sorted by name")
    public List<CredentialResponse> list(@RequestParam(required = false) String type) {
        return service.list(type).stream().map(c -> CredentialResponse.from(c, types)).toList();
    }

    @GetMapping("/status")
    @Operation(summary = "Encryption status", description = "Whether a key is configured, the active key id and how "
            + "many credentials still use an older key.")
    @ApiResponse(responseCode = "200", description = "Encryption status")
    public CredentialService.EncryptionStatus status() {
        return service.encryptionStatus();
    }

    @PostMapping
    @Operation(summary = "Create a credential", description = "The secret is encrypted with AES-256-GCM and never returned.")
    @ApiResponse(responseCode = "201", description = "Created")
    @ApiResponse(responseCode = "503", description = "No encryption key configured",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<CredentialResponse> create(@Valid @RequestBody CredentialRequests.Create request) {
        var created = service.create(request.type(), new CredentialService.Details(request.name(),
                request.description(), request.allowedHosts(), request.fields()), request.secret());
        return ResponseEntity.created(URI.create(ApiPaths.BASE + "/credentials/" + created.getId()))
                .body(CredentialResponse.from(created, types));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a credential (without secret)")
    @ApiResponse(responseCode = "200", description = "The credential")
    public CredentialResponse get(@PathVariable UUID id) {
        return CredentialResponse.from(service.get(id), types);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update name, description, allowed hosts and non-secret fields",
            description = "Requires lockVersion. The secret is changed only by rotation.")
    @ApiResponse(responseCode = "200", description = "Updated")
    public CredentialResponse update(@PathVariable UUID id, @Valid @RequestBody CredentialRequests.Update request) {
        return CredentialResponse.from(service.update(id, new CredentialService.Details(request.name(),
                request.description(), request.allowedHosts(), request.fields()), request.lockVersion()), types);
    }

    @PostMapping("/{id}/rotate")
    @Operation(summary = "Rotate the secret", description = "Replaces the secret value; secretVersion increments. "
            + "Runs started afterwards use the new value.")
    @ApiResponse(responseCode = "200", description = "Rotated")
    public CredentialResponse rotate(@PathVariable UUID id, @Valid @RequestBody CredentialRequests.Rotate request) {
        return CredentialResponse.from(service.rotate(id, request.secret(), request.lockVersion()), types);
    }

    @GetMapping("/{id}/usage")
    @Operation(summary = "Workflows that reference the credential")
    @ApiResponse(responseCode = "200", description = "Referencing workflows")
    public UsageResponse usage(@PathVariable UUID id) {
        return new UsageResponse(service.usage(id));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a credential", description = "Refused with 409 (and the workflows) while referenced.")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "409", description = "Still referenced by workflows",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/re-encrypt")
    @Operation(summary = "Re-encrypt all credentials with the active key",
            description = "Key rotation: add a new key to the keyring, make it active, call this, then remove the old key. "
                    + "Also encrypts webhook secrets still stored in plaintext.")
    @ApiResponse(responseCode = "200", description = "Re-encryption summary")
    public ReEncryptResponse reEncrypt() {
        CredentialService.ReEncryption result = service.reEncrypt();
        return new ReEncryptResponse(result.credentials(), result.webhookSecrets(),
                service.encryptionStatus().activeKeyId());
    }
}
