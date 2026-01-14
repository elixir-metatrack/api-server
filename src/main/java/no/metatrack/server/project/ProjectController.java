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

    @GET
    @Produces("application/json")
    public List<ProjectResponse> getAllProjects() {
        return projectService.getAllProjects().stream()
                .map(ProjectResponse::fromEntity)
                .toList();
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
}
