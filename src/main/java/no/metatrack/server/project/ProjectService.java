package no.metatrack.server.project;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import no.metatrack.server.auth.keycloak.IdentityLookupService;
import no.metatrack.server.sample.Sample;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class ProjectService {
    private final IdentityLookupService identityLookupService;

    public ProjectService(IdentityLookupService identityLookupService) {
        this.identityLookupService = identityLookupService;
    }

    public List<ProjectResponse> getAllProjects() {
        return toResponses(Project.listAll());
    }

    @Transactional
    public Project createProject(String name, String description, String currentUserId) throws WebApplicationException {

        if (Project.projectExistsByName(name)) {
            throw new WebApplicationException("Project already exists", Response.Status.CONFLICT);
        }

        Project project = new Project();
        project.name = name;
        project.description = description;
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
    public void updateProject(Long projectId, String name, String description) throws NotFoundException {
        Project project = (Project) Project.findByIdOptional(projectId).orElseThrow(NotFoundException::new);

        if (name != null) {
            project.name = name;
        }
        if (description != null) {
            project.description = description;
        }
        project.modifiedOn = Instant.now();
    }

    @Transactional
    public void deleteProject(Long projectId) {
        Project project = (Project) Project.findByIdOptional(projectId).orElseThrow(NotFoundException::new);

        if (!project.subProjects.isEmpty()) {
            throw new WebApplicationException(
                    "Cannot delete a project that has sub-projects - delete them first",
                    Response.Status.CONFLICT);
        }

        project.delete();
    }

    @Transactional
    public void addMember(Long projectId, UUID memberId, ProjectRole role) {
        Project project = (Project) Project.findByIdOptional(projectId).orElseThrow(NotFoundException::new);

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
        Project project = (Project) Project.findByIdOptional(projectId).orElseThrow(NotFoundException::new);
        if (!ProjectMember.isMember(memberId, projectId)) {
            throw new WebApplicationException("Member doesn't exists", Response.Status.NOT_FOUND);
        }

        ProjectMember member =
                ProjectMember.findMemberInProjectOptional(memberId, projectId).orElseThrow(NotFoundException::new);

        if (member.role == ProjectRole.OWNER) {
            long ownerCount = ProjectMember.count("project.id = ?1 and role = ?2", projectId, ProjectRole.OWNER);
            if (ownerCount <= 1) {
                throw new WebApplicationException(
                        "Cannot remove the last owner of a project", Response.Status.BAD_REQUEST);
            }
        }

        project.projectMembers.remove(member);
    }

    @Transactional
    public void updateMemberRole(Long projectId, UUID memberId, ProjectRole role) {
        Project.findByIdOptional(projectId).orElseThrow(NotFoundException::new);
        if (!ProjectMember.isMember(memberId, projectId)) {
            throw new WebApplicationException("Member doesn't exists", Response.Status.NOT_FOUND);
        }

        ProjectMember member =
                ProjectMember.findMemberInProjectOptional(memberId, projectId).orElseThrow(NotFoundException::new);

        member.role = role;
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
        String ownerUsername = identityLookupService.username(project.owner).orElse(null);
        return response(project, ownerUsername);
    }

    List<ProjectResponse> toResponses(List<Project> projects) {
        Map<UUID, Optional<String>> usernames = identityLookupService.usernames(
                projects.stream().map(project -> project.owner).toList()
        );
        return projects.stream()
                .map(project -> response(project, usernames.get(project.owner).orElse(null)))
                .toList();
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
                project.parentProject != null ? project.parentProject.id : null
        );
    }

    @Transactional
    public Project createSubProject(
            Long parentProjectId, String name, String description, List<UUID> sampleIds, String currentUserId) {
        Project parent = (Project) Project.findByIdOptional(parentProjectId).orElseThrow(NotFoundException::new);
        if (parent.isSubProject()) {
            throw new WebApplicationException(
                    "Cannot create a sub-project of a sub-project", Response.Status.BAD_REQUEST);
        }
        if (Project.projectExistsByName(name)) {
            throw new WebApplicationException("Project already exists", Response.Status.CONFLICT);
        }

        Project subProject = new Project();
        subProject.name = name;
        subProject.description = description;
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
}
