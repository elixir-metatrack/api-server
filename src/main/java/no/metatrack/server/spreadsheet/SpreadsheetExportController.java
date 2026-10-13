package no.metatrack.server.spreadsheet;

import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import no.metatrack.server.project.Project;
import no.metatrack.server.project.ProjectRole;
import no.metatrack.server.project.ProjectRoleCheck;

import java.util.UUID;

@Path("/api/projects/{projectId}")
@Authenticated
@Consumes(MediaType.APPLICATION_JSON)
@Produces(SpreadsheetTemplateController.XLSX_TYPE)
public class SpreadsheetExportController {
    @Inject ProjectRoleCheck roleCheck;
    @Inject SpreadsheetExportService exports;

    @POST
    @Path("/samples/export")
    public Response samples(@PathParam("projectId") Long projectId, SpreadsheetExportRequest request) {
        requireViewer(projectId);
        if (request == null) throw new BadRequestException("Select samples to export");
        return download(exports.samples(projectId, request.validatedNames()), "samples-" + projectId + ".xlsx");
    }

    @POST
    @Path("/assays/{assayId}/experiments/export")
    public Response experiments(@PathParam("projectId") Long projectId, @PathParam("assayId") UUID assayId,
            SpreadsheetExportRequest request) {
        requireViewer(projectId);
        if (request == null) throw new BadRequestException("Select samples to export");
        return download(exports.experiments(projectId, assayId, request.validatedNames()),
                "experiments-" + assayId + ".xlsx");
    }

    private void requireViewer(Long projectId) {
        if (!Project.projectExists(projectId)) throw new NotFoundException("Project not found");
        if (!roleCheck.isAtLeast(projectId, ProjectRole.VIEWER)) throw new ForbiddenException();
    }

    private Response download(byte[] content, String filename) {
        return Response.ok(content, SpreadsheetTemplateController.XLSX_TYPE)
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .header("Cache-Control", "no-store")
                .build();
    }
}
