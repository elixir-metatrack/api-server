package no.metatrack.server.project;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import no.metatrack.server.admin.AdminProjectResponse;
import no.metatrack.server.auth.keycloak.IdentityLookupService;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class SystemAdminProjectService {
    private final EntityManager entityManager;
    private final IdentityLookupService identityLookupService;

    @Inject
    public SystemAdminProjectService(EntityManager entityManager, IdentityLookupService identityLookupService) {
        this.entityManager = entityManager;
        this.identityLookupService = identityLookupService;
    }

    @Transactional
    public List<AdminProjectResponse> listProjects(int page, int size) {
        List<Project> projects = entityManager.createQuery("from Project order by id", Project.class)
                .setFirstResult(page * size)
                .setMaxResults(size)
                .getResultList();
        return projects.stream().map(project -> new AdminProjectResponse(
                project.id,
                project.name,
                project.owner,
                project.projectMembers.size(),
                project.projectMembers.stream().noneMatch(member ->
                        member.memberId.equals(project.owner) && member.role == ProjectRole.OWNER),
                project.createdOn,
                project.modifiedOn)).toList();
    }

    @Transactional
    public long projectCount() {
        return Project.count();
    }

    @Transactional
    public List<AdminProjectResponse> ownershipRisks(int page, int size) {
        return entityManager.createQuery("""
                        from Project p
                        where not exists (
                            select m.id from ProjectMember m
                            where m.project = p and m.memberId = p.owner and m.role = :ownerRole
                        )
                        order by p.id
                        """, Project.class)
                .setParameter("ownerRole", ProjectRole.OWNER)
                .setFirstResult(page * size)
                .setMaxResults(size)
                .getResultList().stream().map(project -> new AdminProjectResponse(
                        project.id, project.name, project.owner, project.projectMembers.size(), true,
                        project.createdOn, project.modifiedOn)).toList();
    }

    @Transactional
    public long ownershipRiskCount() {
        return entityManager.createQuery("""
                        select count(p) from Project p
                        where not exists (
                            select m.id from ProjectMember m
                            where m.project = p and m.memberId = p.owner and m.role = :ownerRole
                        )
                        """, Long.class)
                .setParameter("ownerRole", ProjectRole.OWNER)
                .getSingleResult();
    }

    @Transactional
    public void addMember(Long projectId, UUID memberId, ProjectRole role) {
        requireAssignableRole(role);
        requireExistingIdentity(memberId);
        Project project = lockProject(projectId);
        if (ProjectMember.isMember(memberId, projectId)) {
            throw new WebApplicationException("User is already a project member", 409);
        }
        ProjectMember member = new ProjectMember();
        member.project = project;
        member.memberId = memberId;
        member.role = role;
        entityManager.persist(member);
        project.modifiedOn = Instant.now();
    }

    @Transactional
    public void transferOwnership(Long projectId, UUID newOwnerId, ProjectRole formerOwnerRole) {
        if (formerOwnerRole == null || formerOwnerRole == ProjectRole.OWNER) {
            throw new BadRequestException("Former owner role must be ADMIN, EDITOR, or VIEWER");
        }
        requireExistingIdentity(newOwnerId);
        Project project = lockProject(projectId);
        if (project.owner.equals(newOwnerId)) {
            throw new WebApplicationException("User is already the project owner", 409);
        }

        UUID formerOwnerId = project.owner;
        ProjectMember newOwner = ProjectMember.findMemberInProjectOptional(newOwnerId, projectId).orElseGet(() -> {
            ProjectMember member = new ProjectMember();
            member.project = project;
            member.memberId = newOwnerId;
            return member;
        });
        newOwner.role = ProjectRole.OWNER;
        if (newOwner.id == null) {
            entityManager.persist(newOwner);
        }

        ProjectMember formerOwner = ProjectMember.findMemberInProjectOptional(formerOwnerId, projectId).orElse(null);
        if (formerOwner != null) {
            formerOwner.role = formerOwnerRole;
        }
        project.owner = newOwnerId;
        project.modifiedOn = Instant.now();
    }

    private Project lockProject(Long projectId) {
        Project project = entityManager.find(Project.class, projectId, LockModeType.PESSIMISTIC_WRITE);
        if (project == null) {
            throw new NotFoundException("Project not found");
        }
        return project;
    }

    private void requireExistingIdentity(UUID memberId) {
        if (memberId == null || identityLookupService.identity(memberId).isEmpty()) {
            throw new NotFoundException("User not found in Keycloak");
        }
    }

    private void requireAssignableRole(ProjectRole role) {
        if (role == null || role == ProjectRole.OWNER) {
            throw new BadRequestException("Use ownership transfer to grant the OWNER role");
        }
    }
}