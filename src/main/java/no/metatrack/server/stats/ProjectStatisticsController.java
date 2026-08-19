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

@Path("/api/projects/{projectId}/statistics")
public class ProjectStatisticsController {
    @Inject
    StatisticsService statisticsService;

    @Inject
    ProjectRoleCheck projectRoleCheck;

    @GET
    @Path("/storage")
    @Authenticated
    public StorageStatistics getStorageStatistics(@PathParam("projectId") Long projectId) {
        if (!Project.projectExists(projectId)) throw new NotFoundException("Project not found");
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.VIEWER)) throw new ForbiddenException();

        return statisticsService.getProjectStorageStatistics(projectId);
    }
}