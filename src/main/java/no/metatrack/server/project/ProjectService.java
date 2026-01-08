package no.metatrack.server.project;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class ProjectService {
    public List<Project> getAllProjects() {
        return Project.listAll();
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

        project.projectMembers.remove(member);
    }
}
