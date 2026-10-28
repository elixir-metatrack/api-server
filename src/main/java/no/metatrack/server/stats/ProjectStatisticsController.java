package no.metatrack.server.stats;

import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import no.metatrack.server.project.Project;
import no.metatrack.server.project.ProjectRole;
import no.metatrack.server.project.ProjectRoleCheck;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@Path("/api/projects/{projectId}/statistics")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Project statistics", description = "Project-scoped storage usage")
public class ProjectStatisticsController {
    @Inject
    StatisticsService statisticsService;

    @Inject
    ProjectRoleCheck projectRoleCheck;

    @GET
    @Path("/storage")
    @Authenticated
    @Operation(summary = "Get project storage statistics", description = "Requires VIEWER membership in the project.")
    @APIResponse(responseCode = "403", description = "Project VIEWER membership required")
    @APIResponse(responseCode = "404", description = "Project not found")
    public StorageStatistics getStorageStatistics(@PathParam("projectId") Long projectId) {
        if (!Project.projectExists(projectId)) throw new NotFoundException("Project not found");
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.VIEWER)) throw new ForbiddenException();

        return statisticsService.getProjectStorageStatistics(projectId);
    }
}