package com.flowforge.application.credential;

import java.util.UUID;

/** Decrypts a credential for use by a node at run time, after checking ownership and permitted usage. */
public interface CredentialResolver {

    /**
     * @param ownerId  owner of the workflow being executed (not "the current user": runs may be triggered by a
     *                 webhook or schedule)
     * @param nodeType node type that wants to use the credential
     * @throws CredentialAccessException {@code CREDENTIAL_NOT_FOUND} (missing or owned by someone else),
     *                                   {@code CREDENTIAL_NOT_PERMITTED} (type not usable by this node),
     *                                   {@code CREDENTIAL_UNAVAILABLE} (key missing or ciphertext invalid)
     */
    ResolvedCredential resolve(UUID credentialId, String ownerId, String nodeType);
}
