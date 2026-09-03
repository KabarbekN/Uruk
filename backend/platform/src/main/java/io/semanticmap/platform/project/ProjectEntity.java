package io.semanticmap.platform.project;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "project")
class ProjectEntity {
    @Id
    UUID id;

    @Column(nullable = false)
    UUID organizationId;

    @Column(nullable = false, length = 120)
    String name;

    @Column(nullable = false, length = 2000)
    String description;

    @Column(nullable = false, columnDefinition = "text")
    String repositoryPath;

    @Column(nullable = false)
    Instant createdAt;

    @Column(nullable = false)
    Instant updatedAt;

    Instant deletedAt;

    protected ProjectEntity() {}

    ProjectEntity(UUID organizationId, String name, String description, String repositoryPath) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.name = name;
        this.description = description;
        this.repositoryPath = repositoryPath;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }
}
