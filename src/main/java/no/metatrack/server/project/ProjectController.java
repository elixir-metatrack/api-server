package no.metatrack.server.project;

import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import no.metatrack.server.auth.CurrentUser;
import no.metatrack.server.auth.UserService;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.ExampleObject;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;
import java.util.UUID;

@Path("/api/projects")
@Tag(name = "Projects", description = "Projects, memberships, join requests, and sub-projects")
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
    @Operation(summary = "List projects", description = "Public project catalog. Project comments are omitted for anonymous callers.")
    public List<ProjectResponse> getAllProjects() {
        return projectService.getAllProjects();
    }

    @GET
    @Path("/{projectId}")
    @Operation(summary = "Get a project", description = "Public project details. Project comments are omitted for anonymous callers.")
    @APIResponse(responseCode = "404", description = "Project not found")
    public ProjectResponse getProjectById(@PathParam("projectId") Long projectId) {
        return projectService.getProjectResponseById(projectId);
    }

    @GET
    @Path("/me")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "List your projects", description = "Returns projects where the authenticated caller is a member.")
    public List<ProjectResponse> getProjectsForUser() {
        String currentUserIdString = userService.requireCurrentUser().id();
        UUID currentUserId = UUID.fromString(currentUserIdString);

        return projectService.getAllUserProjects(currentUserId);
    }

    @POST
    @Authenticated
    @Produces("application/json")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Create a project", description = "The authenticated caller becomes the project owner. Project names are unique; data-owning organization is required.")
    @APIResponse(responseCode = "409", description = "A project with this name already exists")
    public ProjectResponse createNewProject(
            @RequestBody(description = "Project details", content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = CreateProjectRequest.class),
                    examples = @ExampleObject(value = "{\"name\":\"avian-surveillance-2026\",\"dataOwningOrganization\":\"Example Institute\"}")))
            @Valid CreateProjectRequest request) {
        CurrentUser currentUser = userService.requireCurrentUser();
        String currentUserId = currentUser.id();

        Project project = projectService.createProject(request, currentUserId);
        return projectService.toResponse(project);
    }

    @PATCH
    @Authenticated
    @Path("/{projectId}")
    @Produces("application/json")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Update a project", description = "Requires OWNER membership. Only supplied non-null fields are updated; returns no response body.")
    @APIResponse(responseCode = "204", description = "Project updated")
    @APIResponse(responseCode = "403", description = "Project OWNER membership required")
    @APIResponse(responseCode = "404", description = "Project not found")
    public Response updateProject(
            @PathParam("projectId") Long projectId,
            @RequestBody(description = "Partial project update; non-null values replace stored values",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = UpdateProjectRequest.class),
                            examples = @ExampleObject(value = "{\"description\":\"Updated project description\"}")))
            @Valid UpdateProjectRequest request) {
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.OWNER))
            throw new WebApplicationException(Response.Status.FORBIDDEN);

        projectService.updateProject(projectId, request);

        return Response.noContent().build();
    }

    @DELETE
    @Authenticated
    @Path("/{projectId}")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Delete a project", description = "Requires OWNER membership. A project with sub-projects cannot be deleted; returns no response body.")
    @APIResponse(responseCode = "204", description = "Project deleted")
    @APIResponse(responseCode = "403", description = "Project OWNER membership required")
    @APIResponse(responseCode = "404", description = "Project not found")
    @APIResponse(responseCode = "409", description = "Project has sub-projects")
    public Response deleteProject(@PathParam("projectId") Long projectId) {
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.OWNER))
            throw new WebApplicationException(Response.Status.FORBIDDEN);

        projectService.deleteProject(projectId);
        return Response.noContent().build();
    }

    @POST
    @Authenticated
    @Path("/{projectId}/member/{memberId}")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Add a project member", description = "Requires ADMIN membership; OWNER-level grants are restricted to owners. Returns no response body.")
    @APIResponse(responseCode = "204", description = "Member added")
    @APIResponse(responseCode = "403", description = "Insufficient project role")
    public Response addMember(
            @PathParam("projectId") Long projectId, @PathParam("memberId") UUID memberId,
            @RequestBody(description = "Role to assign to the member", content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = AddMemberRequest.class),
                    examples = @ExampleObject(value = "{\"role\":\"VIEWER\"}")))
            @Valid AddMemberRequest request) {
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.ADMIN))
            throw new WebApplicationException(Response.Status.FORBIDDEN);

        projectService.addMember(projectId, memberId, request.role());
        return Response.noContent().build();
    }

    @DELETE
    @Authenticated
    @Path("/{projectId}/member/{memberId}")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Remove a project member", description = "Requires ADMIN membership; removing or demoting the final owner is rejected. Returns no response body.")
    @APIResponse(responseCode = "204", description = "Member removed")
    @APIResponse(responseCode = "403", description = "Insufficient project role")
    public Response removeMember(@PathParam("projectId") Long projectId, @PathParam("memberId") UUID memberId) {
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.ADMIN))
            throw new WebApplicationException(Response.Status.FORBIDDEN);

        projectService.removeMember(projectId, memberId);
        return Response.noContent().build();
    }

    @PUT
    @Authenticated
    @Path("/{projectId}/member/{memberId}")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Change a member role", description = "Requires ADMIN membership; changing an OWNER role requires OWNER membership. Returns no response body.")
    @APIResponse(responseCode = "204", description = "Member role updated")
    @APIResponse(responseCode = "403", description = "Insufficient project role")
    public Response modifyRole(
            @PathParam("projectId") Long projectId,
            @PathParam("memberId") UUID memberId,
            @RequestBody(description = "Replacement project role", content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = ModifyMemberRoleRequest.class),
                    examples = @ExampleObject(value = "{\"role\":\"EDITOR\"}")))
            @Valid ModifyMemberRoleRequest request) {
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.ADMIN))
            throw new WebApplicationException(Response.Status.FORBIDDEN);

        projectService.updateMemberRole(projectId, memberId, request.role());
        return Response.noContent().build();
    }

    @POST
    @Authenticated
    @Path("/{projectId}/join/{role}")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Request to join a project", description = "Creates a join request for the authenticated caller at the selected role; project administrators approve or remove join requests.")
    @APIResponse(responseCode = "204", description = "Join request recorded; no response body")
    public Response joinProject(
            @PathParam("projectId") Long projectId,
            @Parameter(description = "Requested membership role; values are OWNER, ADMIN, EDITOR, or VIEWER")
            @PathParam("role") ProjectRole role) {
        CurrentUser currentUser = userService.requireCurrentUser();
        UUID currentUserId = UUID.fromString(currentUser.id());

        joinProjectService.joinProject(projectId, currentUserId, role);
        return Response.noContent().build();
    }

    @GET
    @Authenticated
    @Path("/{projectId}/joinrequests/")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "List project join requests", description = "Requires ADMIN membership.")
    @APIResponse(responseCode = "403", description = "Project ADMIN membership required")
    public List<JoinProjectResponse> getJoinRequests(@PathParam("projectId") Long projectId) {
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.ADMIN))
            throw new WebApplicationException(Response.Status.FORBIDDEN);

        return joinProjectService.getJoinRequests(projectId);
    }

    @DELETE
    @Authenticated
    @Path("/{projectId}/joingrequests/{userId}")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Remove a project join request", description = "Requires ADMIN membership. The route retains its existing `joingrequests` spelling.")
    @APIResponse(responseCode = "204", description = "Join request removed; no response body")
    @APIResponse(responseCode = "403", description = "Project ADMIN membership required")
    public Response deleteJoinRequest(@PathParam("projectId") Long projectId, @PathParam("userId") UUID userId) {
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.ADMIN))
            throw new WebApplicationException(Response.Status.FORBIDDEN);
        joinProjectService.removeJoinRequest(projectId, userId);

        return Response.noContent().build();
    }

    @POST
    @Authenticated
    @Path("/{projectId}/joinrequests/{userId}/approve")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Approve a project join request", description = "Requires ADMIN membership and creates or updates the requested project membership.")
    @APIResponse(responseCode = "204", description = "Join request approved; no response body")
    @APIResponse(responseCode = "403", description = "Project ADMIN membership required")
    public Response approveJoinRequest(@PathParam("projectId") Long projectId, @PathParam("userId") UUID userId) {
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.ADMIN))
            throw new WebApplicationException(Response.Status.FORBIDDEN);

        joinProjectService.approveJoinRequest(projectId, userId);
        return Response.noContent().build();
    }

    @GET
    @Authenticated
    @Path("{projectId}/members")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "List project members", description = "Requires VIEWER membership.")
    @APIResponse(responseCode = "403", description = "Project VIEWER membership required")
    public List<ProjectMemberResponse> getAllProjectMembers(@PathParam("projectId") Long projectId) {
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.VIEWER))
            throw new WebApplicationException(Response.Status.FORBIDDEN);

        return projectMemberService.listAllProjectMembers(projectId);
    }

    @POST
    @Authenticated
    @Path("/{projectId}/subprojects")
    @Produces("application/json")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Create a sub-project", description = "Requires ADMIN membership in the parent project. Optionally links samples from that parent; sub-projects cannot themselves have sub-projects.")
    @APIResponse(responseCode = "409", description = "A project with this name already exists")
    public ProjectResponse createSubProject(
            @PathParam("projectId") Long projectId,
            @RequestBody(description = "Sub-project details", content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = CreateSubProjectRequest.class),
                    examples = @ExampleObject(value = "{\"name\":\"field-site\",\"description\":\"Field collection subset\",\"sampleIds\":[]}")))
            @Valid CreateSubProjectRequest request) {
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.ADMIN))
            throw new WebApplicationException(Response.Status.FORBIDDEN);

        CurrentUser currentUser = userService.requireCurrentUser();
        Project subProject = projectService.createSubProject(
                projectId, request, currentUser.id());
        return projectService.toResponse(subProject);
    }

    @GET
    @Authenticated
    @Path("/{projectId}/subprojects")
    @Produces("application/json")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "List sub-projects", description = "Requires VIEWER membership in the parent project.")
    @APIResponse(responseCode = "403", description = "Project VIEWER membership required")
    public List<ProjectResponse> getSubProjects(@PathParam("projectId") Long projectId) {
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.VIEWER))
            throw new WebApplicationException(Response.Status.FORBIDDEN);

        return projectService.getSubProjects(projectId);
    }
}
