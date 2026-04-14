package no.metatrack.server.project;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class JoinProjectService {
    @Inject
    ProjectService projectService;

    @Transactional
    public void joinProject(long projectId, UUID userId, ProjectRole role) {
        JoinProject joinProject = new JoinProject();
        joinProject.projectId = projectId;
        joinProject.userId = userId;
        joinProject.role = role;
        joinProject.persist();
    }

    public List<JoinProject> getJoinRequests(long projectId) {
        return JoinProject.list("projectId", projectId);
    }

    @Transactional
    public void removeJoinRequest(long projectId, UUID userId) {
        JoinProject joinProject = JoinProject.findByUserIdAndProjectId(projectId, userId);
        if (joinProject == null) {
            throw new NotFoundException("Join request not found");
        }
        joinProject.delete();
    }

    @Transactional
    public void approveJoinRequest(long projectId, UUID userId) {
        JoinProject joinRequest = JoinProject.findByUserIdAndProjectId(projectId, userId);
        if (joinRequest == null) {
            throw new NotFoundException("Join request not found");
        }
        projectService.addMember(projectId, userId, joinRequest.role);
        joinRequest.delete();
    }
}
