package com.flowforge.domain.validation;

import java.util.Optional;

/**
 * Port used by configuration validation to check {@code credentialId} references: the credential must exist, belong to
 * the current owner and be usable by the node type. Without an implementation, references are only checked for shape.
 */
@FunctionalInterface
public interface CredentialReferences {

    /** A problem message, or empty when the reference is fine. */
    Optional<String> problem(String credentialId, String nodeType);
}
