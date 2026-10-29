package no.metatrack.server.project;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import no.metatrack.server.auth.UserService;
import no.metatrack.server.auth.keycloak.IdentityLookupService;
import no.metatrack.server.auth.keycloak.KeycloakIdentityException;
import no.metatrack.server.sample.Sample;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class ProjectService {
    private static final Logger LOG = Logger.getLogger(ProjectService.class);

    private final IdentityLookupService identityLookupService;

    @Inject
    ProjectRoleCheck projectRoleCheck;

    @Inject
    SecurityIdentity identity;

    @Inject
    UserService userService;

    public ProjectService(IdentityLookupService identityLookupService) {
        this.identityLookupService = identityLookupService;
    }

    public List<ProjectResponse> getAllProjects() {
        return toResponses(Project.listAll());
    }

    @Transactional
    public Project createProject(CreateProjectRequest request, String currentUserId) throws WebApplicationException {
        String organization = requireOrganization(request.dataOwningOrganization());
        String name = request.name();

        if (Project.projectExistsByName(name)) {
            throw new WebApplicationException("Project already exists", Response.Status.CONFLICT);
        }

        Project project = new Project();
        project.name = name;
        project.description = request.description();
        project.dataOwningOrganization = organization;
        project.detailedDescription = request.detailedDescription();
        project.comments = request.comments();
        project.owner = UUID.fromString(currentUserId);
        project.createdOn = Instant.now();
        project.modifiedOn = Instant.now();

        ProjectMember member = new ProjectMember();
        member.role = ProjectRole.OWNER;
        member.memberId = UUID.fromString(currentUserId);
        member.project = project;

        project.projectMembers.add(member);

        project.persist();
        return project;
    }

    @Transactional
    public void updateProject(Long projectId, UpdateProjectRequest request) throws NotFoundException {
        Project project = (Project) Project.findByIdOptional(projectId).orElseThrow(NotFoundException::new);

        if (request.dataOwningOrganization() != null) {
            project.dataOwningOrganization = requireOrganization(request.dataOwningOrganization());
        }
        if (request.name() != null) {
            project.name = request.name();
        }
        if (request.description() != null) {
            project.description = request.description();
        }
        if (request.detailedDescription() != null) {
            project.detailedDescription = request.detailedDescription();
        }
        if (request.comments() != null) {
            project.comments = request.comments();
        }
        project.modifiedOn = Instant.now();
    }

    @Transactional
    public void deleteProject(Long projectId) {
        Project project = lockProject(projectId);

        if (!project.subProjects.isEmpty()) {
            throw new WebApplicationException(
                    "Cannot delete a project that has sub-projects - delete them first",
                    Response.Status.CONFLICT);
        }

        project.delete();
    }

    @Transactional
    public void addMember(Long projectId, UUID memberId, ProjectRole role) {
        Project project = lockProject(projectId);
        if (role == null) throw new BadRequestException("Member role is required");
        if (role == ProjectRole.OWNER) requireOwner(projectId);

        if (ProjectMember.isMember(memberId, projectId)) {
            throw new WebApplicationException("Member already exists", Response.Status.CONFLICT);
        }

        ProjectMember member = new ProjectMember();
        member.memberId = memberId;
        member.role = role;
        member.project = project;

        project.projectMembers.add(member);
    }

    @Transactional
    public void removeMember(Long projectId, UUID memberId) {
        Project project = lockProject(projectId);
        if (!ProjectMember.isMember(memberId, projectId)) {
            throw new WebApplicationException("Member doesn't exists", Response.Status.NOT_FOUND);
        }

        ProjectMember member =
                ProjectMember.findMemberInProjectOptional(memberId, projectId).orElseThrow(NotFoundException::new);

        ProjectMember.getEntityManager().refresh(member);
        if (member.role == ProjectRole.OWNER) {
            requireOwner(projectId);
            requireAnotherOwner(projectId);
        }

        project.projectMembers.remove(member);
    }

    @Transactional
    public void updateMemberRole(Long projectId, UUID memberId, ProjectRole role) {
        lockProject(projectId);
        if (role == null) throw new BadRequestException("Member role is required");
        if (!ProjectMember.isMember(memberId, projectId)) {
            throw new WebApplicationException("Member doesn't exists", Response.Status.NOT_FOUND);
        }

        ProjectMember member =
                ProjectMember.findMemberInProjectOptional(memberId, projectId).orElseThrow(NotFoundException::new);

        ProjectMember.getEntityManager().refresh(member);
        if (member.role == ProjectRole.OWNER || role == ProjectRole.OWNER) requireOwner(projectId);
        if (member.role == ProjectRole.OWNER && role != ProjectRole.OWNER) requireAnotherOwner(projectId);
        member.role = role;
    }

    @Transactional
    public Project lockProject(Long projectId) {
        // All membership writers and invitation decisions lock the project before any child rows.
        Project project = Project.findById(projectId, LockModeType.PESSIMISTIC_WRITE);
        if (project == null) {
            throw new NotFoundException();
        }
        return project;
    }

    private void requireOwner(Long projectId) {
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.OWNER)) {
            throw new ForbiddenException("Only project owners can grant or revoke the owner role");
        }
    }

    private void requireAnotherOwner(Long projectId) {
        if (ProjectMember.count("project.id = ?1 and role = ?2", projectId, ProjectRole.OWNER) <= 1) {
            throw new BadRequestException("Cannot remove or demote the last owner of a project");
        }
    }

    public Project getProjectById(long id) {
        return Project.<Project>findByIdOptional(id).orElseThrow(NotFoundException::new);
    }

    public ProjectResponse getProjectResponseById(long id) {
        return toResponse(getProjectById(id));
    }

    public List<ProjectResponse> getAllUserProjects(UUID currentUserId) {
        return toResponses(Project.findProjectsByMember(currentUserId));
    }

    public ProjectResponse toResponse(Project project) {
        String ownerUsername = ownerUsername(project.owner).orElse(null);
        return response(project, ownerUsername);
    }

    List<ProjectResponse> toResponses(List<Project> projects) {
        Map<UUID, Optional<String>> usernames = new LinkedHashMap<>();
        for (Project project : projects) {
            usernames.computeIfAbsent(project.owner, this::ownerUsername);
        }
        return projects.stream()
                .map(project -> response(project, usernames.get(project.owner).orElse(null)))
                .toList();
    }

    private Optional<String> ownerUsername(UUID ownerId) {
        try {
            return identityLookupService.username(ownerId);
        } catch (KeycloakIdentityException exception) {
            LOG.warn("Project owner username lookup failed; returning null");
            return Optional.empty();
        }
    }

    private ProjectResponse response(Project project, String ownerUsername) {
        // Sub-projects don't own samples directly (Project.samples) - their samples
        // are the curated subset linked in via Project.linkedSamples.
        long sampleCount = project.isSubProject() ? project.linkedSamples.size() : project.samples.size();

        return new ProjectResponse(
                project.id,
                project.name,
                project.description,
                project.owner,
                ownerUsername,
                sampleCount,
                project.createdOn,
                project.modifiedOn,
                project.parentProject != null ? project.parentProject.id : null,
                project.dataOwningOrganization,
                project.detailedDescription,
                visibleComments(project)
        );
    }

    @Transactional
    public Project createSubProject(
            Long parentProjectId, String name, String description, List<UUID> sampleIds, String currentUserId) {
        return createSubProject(parentProjectId,
                new CreateSubProjectRequest(name, description, sampleIds, null, null, null), currentUserId);
    }

    @Transactional
    public Project createSubProject(Long parentProjectId, CreateSubProjectRequest request, String currentUserId) {
        Project parent = (Project) Project.findByIdOptional(parentProjectId).orElseThrow(NotFoundException::new);
        String name = request.name();
        List<UUID> sampleIds = request.sampleIds();
        String organization = requireOrganization(request.dataOwningOrganization() != null
                ? request.dataOwningOrganization() : parent.dataOwningOrganization);
        if (parent.isSubProject()) {
            throw new WebApplicationException(
                    "Cannot create a sub-project of a sub-project", Response.Status.BAD_REQUEST);
        }
        if (Project.projectExistsByName(name)) {
            throw new WebApplicationException("Project already exists", Response.Status.CONFLICT);
        }

        Project subProject = new Project();
        subProject.name = name;
        subProject.description = request.description();
        subProject.dataOwningOrganization = organization;
        subProject.detailedDescription = request.detailedDescription();
        subProject.comments = request.comments();
        subProject.owner = UUID.fromString(currentUserId);
        subProject.parentProject = parent;
        subProject.createdOn = Instant.now();
        subProject.modifiedOn = Instant.now();

        ProjectMember member = new ProjectMember();
        member.role = ProjectRole.OWNER;
        member.memberId = UUID.fromString(currentUserId);
        member.project = subProject;
        subProject.projectMembers.add(member);

        if (sampleIds != null) {
            for (UUID sampleId : sampleIds) {
                Sample sample = Sample.<Sample>findByIdOptional(sampleId)
                        .orElseThrow(() -> new WebApplicationException(
                                "Sample " + sampleId + " not found", Response.Status.BAD_REQUEST));
                if (sample.project == null || !sample.project.id.equals(parent.id)) {
                    throw new WebApplicationException(
                            "Sample " + sampleId + " does not belong to project " + parentProjectId,
                            Response.Status.BAD_REQUEST);
                }
                subProject.linkedSamples.add(sample);
            }
        }

        subProject.persist();
        return subProject;
    }

    public List<ProjectResponse> getSubProjects(Long parentProjectId) {
        return toResponses(Project.findSubProjects(parentProjectId));
    }

    private String requireOrganization(String organization) {
        if (organization == null || organization.isBlank() || organization.length() > 255) {
            throw new BadRequestException("Data-owning organization is required and must not exceed 255 characters");
        }
        return organization;
    }

    private String visibleComments(Project project) {
        if (project.comments == null || identity.isAnonymous()) {
            return null;
        }
        UUID userId = UUID.fromString(userService.requireCurrentUser().id());
        return project.projectMembers.stream().anyMatch(member -> userId.equals(member.memberId))
                ? project.comments : null;
    }
}
