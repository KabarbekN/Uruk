package io.semanticmap.platform.project;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface ProjectRepository extends JpaRepository<ProjectEntity, UUID> {
    List<ProjectEntity> findAllByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

    Optional<ProjectEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);

    @Query(
            value =
                    "SELECT p.* FROM project p WHERE p.organization_id=:organizationId AND p.deleted_at IS NULL AND (:admin OR EXISTS (SELECT 1 FROM project_member m WHERE m.project_id=p.id AND m.organization_id=p.organization_id AND m.user_id=:userId)) ORDER BY p.created_at DESC",
            nativeQuery = true)
    List<ProjectEntity> visible(UUID organizationId, UUID userId, boolean admin);
}
