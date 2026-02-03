package no.metatrack.server.project;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import java.util.UUID;

@Entity
public class JoinProject extends PanacheEntity {
    long projectId;

    UUID userId;

    @Enumerated(EnumType.STRING)
    ProjectRole role;

    public static JoinProject findByUserIdAndProjectId(long projectId, UUID userId) {
        return find("userId = ?1 and projectId = ?2", userId, projectId).firstResult();
    }
}
