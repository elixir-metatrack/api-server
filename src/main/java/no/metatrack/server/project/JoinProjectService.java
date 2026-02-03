package no.metatrack.server.project;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class JoinProjectService {
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
        joinProject.delete();
    }
}
