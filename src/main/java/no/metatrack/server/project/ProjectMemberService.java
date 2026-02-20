package no.metatrack.server.project;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class ProjectMemberService {
    public List<ProjectMember> listAllProjectMembers(long projectId) {
        return ProjectMember.listAllMembersInProject(projectId);
    }
}
