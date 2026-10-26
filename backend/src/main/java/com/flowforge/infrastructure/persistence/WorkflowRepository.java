package com.flowforge.infrastructure.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkflowRepository
        extends JpaRepository<WorkflowEntity, UUID>, JpaSpecificationExecutor<WorkflowEntity> {

    interface CredentialUsage {
        UUID getId();

        String getName();

        String getStatus();
    }

    /** Workflows of an owner whose definition references the credential in any node configuration. */
    @Query(value = "select w.id as id, w.name as name, w.status as status from workflows w where w.owner_id = :owner "
            + "and jsonb_path_exists(w.definition, '$.nodes[*].configuration.credentialId ? (@ == $id)', "
            + "jsonb_build_object('id', cast(:credentialId as text))) order by w.name", nativeQuery = true)
    List<CredentialUsage> findCredentialUsage(@Param("owner") String ownerId, @Param("credentialId") String credentialId);
}
