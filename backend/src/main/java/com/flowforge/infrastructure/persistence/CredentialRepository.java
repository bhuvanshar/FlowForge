package com.flowforge.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CredentialRepository extends JpaRepository<CredentialEntity, UUID> {

    List<CredentialEntity> findByOwnerIdOrderByNameAsc(String ownerId);

    Optional<CredentialEntity> findByIdAndOwnerId(UUID id, String ownerId);

    boolean existsByOwnerIdAndNameIgnoreCase(String ownerId, String name);

    boolean existsByOwnerIdAndNameIgnoreCaseAndIdNot(String ownerId, String name, UUID id);

    List<CredentialEntity> findByKeyIdNot(String keyId);

    /** Usage timestamp without touching the optimistic lock (a run must never conflict with an edit). */
    @Modifying
    @Query(value = "update credentials set last_used_at = :at where id = :id", nativeQuery = true)
    int markUsed(@Param("id") UUID id, @Param("at") Instant at);
}
