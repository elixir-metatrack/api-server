package no.metatrack.server.project;

import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import no.metatrack.server.auth.CurrentUser;
import no.metatrack.server.auth.UserService;

import java.util.List;
import java.util.UUID;

@Path("/api/projects")
public class ProjectController {
    @Inject
    ProjectService projectService;

    @Inject
    UserService userService;

    @Inject
    ProjectRoleCheck projectRoleCheck;

    @Inject
    JoinProjectService joinProjectService;

    @Inject
    ProjectMemberService projectMemberService;

    @GET
    @Produces("application/json")
    public List<ProjectResponse> getAllProjects() {
        return projectService.getAllProjects().stream()
                .map(ProjectResponse::fromEntity)
                .toList();
    }

    @GET
    @Path("/{projectId}")
    public ProjectResponse getProjectById(@PathParam("projectId") Long projectId) {
        Project project = projectService.getProjectById(projectId);
        return ProjectResponse.fromEntity(project);
    }

    @GET
    @Path("/me")
    public List<ProjectResponse> getProjectsForUser() {
        String currentUserIdString = userService.requireCurrentUser().id();
        UUID currentUserId = UUID.fromString(currentUserIdString);

        List<Project> projects = projectService.getAllUserProjects(currentUserId);
        return projects.stream().map(ProjectResponse::fromEntity).toList();
    }

    @POST
    @Authenticated
    @Produces("application/json")
    public ProjectResponse createNewProject(CreateProjectRequest request) {
        CurrentUser currentUser = userService.requireCurrentUser();
        String currentUserId = currentUser.id();

        Project project = projectService.createProject(request.name(), request.description(), currentUserId);
        return ProjectResponse.fromEntity(project);
    }

    @PATCH
    @Authenticated
    @Path("/{projectId}")
    @Produces("application/json")
    public Response updateProject(@PathParam("projectId") Long projectId, UpdateProjectRequest request) {
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.OWNER))
            throw new WebApplicationException(Response.Status.FORBIDDEN);

        projectService.updateProject(projectId, request.name(), request.description());

        return Response.noContent().build();
    }

    @DELETE
    @Authenticated
    @Path("/{projectId}")
    public Response deleteProject(@PathParam("projectId") Long projectId) {
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.OWNER))
            throw new WebApplicationException(Response.Status.FORBIDDEN);

        projectService.deleteProject(projectId);
        return Response.noContent().build();
    }

    @POST
    @Authenticated
    @Path("/{projectId}/member/{memberId}")
    public Response addMember(
            @PathParam("projectId") Long projectId, @PathParam("memberId") UUID memberId, AddMemberRequest request) {
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.ADMIN))
            throw new WebApplicationException(Response.Status.FORBIDDEN);

        projectService.addMember(projectId, memberId, request.role());
        return Response.noContent().build();
    }

    @DELETE
    @Authenticated
    @Path("/{projectId}/member/{memberId}")
    public Response removeMember(@PathParam("projectId") Long projectId, @PathParam("memberId") UUID memberId) {
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.ADMIN))
            throw new WebApplicationException(Response.Status.FORBIDDEN);

        projectService.removeMember(projectId, memberId);
        return Response.noContent().build();
    }

    @PUT
    @Authenticated
    @Path("/{projectId}/member/{memberId}")
    public Response modifyRole(
            @PathParam("projectId") Long projectId,
            @PathParam("memberId") UUID memberId,
            @Valid ModifyMemberRoleRequest request) {
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.ADMIN))
            throw new WebApplicationException(Response.Status.FORBIDDEN);

        projectService.updateMemberRole(projectId, memberId, request.role());
        return Response.noContent().build();
    }

    @POST
    @Authenticated
    @Path("/{projectId}/join/{role}")
    public Response joinProject(@PathParam("projectId") Long projectId, @PathParam("role") ProjectRole role) {
        CurrentUser currentUser = userService.requireCurrentUser();
        UUID currentUserId = UUID.fromString(currentUser.id());

        joinProjectService.joinProject(projectId, currentUserId, role);
        return Response.noContent().build();
    }

    @GET
    @Authenticated
    @Path("/{projectId}/joinrequests/")
    public List<JoinProjectResponse> getJoinRequests(@PathParam("projectId") Long projectId) {
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.ADMIN))
            throw new WebApplicationException(Response.Status.FORBIDDEN);

        List<JoinProject> joinRequests = joinProjectService.getJoinRequests(projectId);
        return joinRequests.stream().map(JoinProjectResponse::fromEntity).toList();
    }

    @DELETE
    @Authenticated
    @Path("/{projectId}/joingrequests/{userId}")
    public Response deleteJoinRequest(@PathParam("projectId") Long projectId, @PathParam("userId") UUID userId) {
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.ADMIN))
            throw new WebApplicationException(Response.Status.FORBIDDEN);
        joinProjectService.removeJoinRequest(projectId, userId);

        return Response.noContent().build();
    }

    @GET
    @Authenticated
    @Path("{projectId}/members")
    public List<ProjectMemberResponse> getAllProjectMembers(@PathParam("projectId") Long projectId) {
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.VIEWER))
            throw new WebApplicationException(Response.Status.FORBIDDEN);

        List<ProjectMember> members = projectMemberService.listAllProjectMembers(projectId);
        return members.stream().map(ProjectMemberResponse::fromEntity).toList();
    }
}
