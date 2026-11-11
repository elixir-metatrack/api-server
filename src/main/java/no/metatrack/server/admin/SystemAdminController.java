package no.metatrack.server.admin;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.validation.constraints.NotNull;
import no.metatrack.server.project.SystemAdminProjectService;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;
import java.util.UUID;

@Path("/api/admin")
@RolesAllowed("system-admin")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "System administration", description = "System-admin oversight, recovery, dashboard, and audit")
public class SystemAdminController {
    @Inject
    SystemAdminService adminService;

    @Inject
    SystemAdminProjectService projectService;

    @Inject
    AdminAuditService auditService;

    @GET
    @Path("/overview")
    @Operation(summary = "Get administrative overview", description = "Returns usage totals, ownership risks, upload issues, and integration status.")
    @APIResponse(responseCode = "403", description = "Requires the system-admin role")
    public AdminOverviewResponse overview() {
        return adminService.overview();
    }

    @GET
    @Path("/projects")
    @Operation(summary = "List projects for oversight", description = "Returns a page of project metadata and flags projects whose designated owner lacks OWNER membership.")
    public AdminProjectPageResponse projects(@QueryParam("page") Integer page, @QueryParam("size") Integer size) {
        int normalizedPage = page == null ? 0 : page;
        int normalizedSize = size == null ? 50 : size;
        validatePage(normalizedPage, normalizedSize);
        List<AdminProjectResponse> projects = projectService.listProjects(normalizedPage, normalizedSize);
        return new AdminProjectPageResponse(projects, normalizedPage, normalizedSize, projectService.projectCount());
    }

    @GET
    @Path("/projects/ownership-risks")
    @Operation(summary = "List projects needing owner attention", description = "Finds projects whose designated owner does not have an OWNER membership.")
    public AdminProjectPageResponse ownershipRisks(
            @QueryParam("page") Integer page, @QueryParam("size") Integer size) {
        int normalizedPage = page == null ? 0 : page;
        int normalizedSize = size == null ? 50 : size;
        validatePage(normalizedPage, normalizedSize);
        List<AdminProjectResponse> projects = projectService.ownershipRisks(normalizedPage, normalizedSize);
        return new AdminProjectPageResponse(projects, normalizedPage, normalizedSize,
                projectService.ownershipRiskCount());
    }

    @POST
    @Path("/projects/{projectId}/members/{memberId}")
    @Operation(summary = "Add an existing Keycloak user to a project", description = "Adds a realm user immediately; use ownership transfer to grant OWNER.")
    @APIResponse(responseCode = "204", description = "Project membership created")
    @APIResponse(responseCode = "404", description = "Project or Keycloak user not found")
    public Response addMember(@PathParam("projectId") Long projectId, @PathParam("memberId") UUID memberId,
            @NotNull @Valid AdminAddProjectMemberRequest request) {
        projectService.addMember(projectId, memberId, request.role());
        return Response.noContent().build();
    }

    @POST
    @Path("/projects/{projectId}/ownership")
    @Operation(summary = "Transfer project ownership", description = "Promotes the replacement to OWNER, updates the designated owner, and demotes the previous designated owner to the requested non-owner role.")
    @APIResponse(responseCode = "204", description = "Ownership transferred")
    @APIResponse(responseCode = "404", description = "Project or Keycloak user not found")
    public Response transferOwnership(@PathParam("projectId") Long projectId,
            @NotNull @Valid TransferProjectOwnershipRequest request) {
        projectService.transferOwnership(projectId, request.newOwnerId(), request.formerOwnerRole());
        return Response.noContent().build();
    }

    @GET
    @Path("/audit")
    @Operation(summary = "List system-admin mutation audit events", description = "Returns successful and failed mutating requests made by system-admins; request bodies are never stored.")
    public AdminAuditPageResponse audit(@QueryParam("page") Integer page, @QueryParam("size") Integer size) {
        int normalizedPage = page == null ? 0 : page;
        int normalizedSize = size == null ? 50 : size;
        validatePage(normalizedPage, normalizedSize);
        return auditService.list(normalizedPage, normalizedSize);
    }

    private void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100 || page > Integer.MAX_VALUE / size) {
            throw new BadRequestException(
                    "page must be non-negative, size must be between 1 and 100, and the offset must be representable");
        }
    }
}