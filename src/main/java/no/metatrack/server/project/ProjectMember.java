package no.metatrack.server.project;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;

import java.util.Optional;
import java.util.UUID;

@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"member_id", "project_id"}))
public class ProjectMember extends PanacheEntity {
    @Column(nullable = false)
    UUID memberId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    ProjectRole role;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    Project project;

    public static Optional<ProjectMember> findMemberInProjectOptional(UUID memberId, Long projectId) {
        return find("memberId = ?1 and project.id = ?2", memberId, projectId).firstResultOptional();
    }

    public static boolean isMember(UUID memberId, Long projectId) {
        return count("memberId = ?1 and project.id = ?2", memberId, projectId) > 0;
    }
}
