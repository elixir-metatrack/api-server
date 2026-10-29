package no.metatrack.server.spreadsheet;

import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import no.metatrack.server.project.Project;
import no.metatrack.server.project.ProjectRole;
import no.metatrack.server.project.ProjectRoleCheck;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.UUID;

@Path("/api/projects/{projectId}")
@Authenticated
@SecurityRequirement(name = "bearerAuth")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(SpreadsheetTemplateController.XLSX_TYPE)
@Tag(name = "Spreadsheet exports", description = "Project sample and experiment spreadsheet downloads")
public class SpreadsheetExportController {
    @Inject ProjectRoleCheck roleCheck;
    @Inject SpreadsheetExportService exports;

    @POST
    @Path("/samples/export")
    @Operation(summary = "Export project samples", description = "Requires VIEWER membership. Select sample names in the JSON request; returns an XLSX attachment with no-store caching.")
    @APIResponse(responseCode = "200", description = "XLSX sample export", content = @Content(mediaType = SpreadsheetTemplateController.XLSX_TYPE, schema = @Schema(type = SchemaType.STRING, format = "binary")))
    @APIResponse(responseCode = "400", description = "No sample names selected or request exceeds export limits")
    @APIResponse(responseCode = "403", description = "Project VIEWER membership required")
    @APIResponse(responseCode = "404", description = "Project or selected sample not found")
    public Response samples(@PathParam("projectId") Long projectId, SpreadsheetExportRequest request) {
        requireViewer(projectId);
        if (request == null) throw new BadRequestException("Select samples to export");
        return download(exports.samples(projectId, request.validatedNames()), "samples-" + projectId + ".xlsx");
    }

    @POST
    @Path("/assays/{assayId}/experiments/export")
    @Operation(summary = "Export assay experiments", description = "Requires VIEWER membership. Select sample names in the JSON request; returns an XLSX attachment with no-store caching.")
    @APIResponse(responseCode = "200", description = "XLSX experiment export", content = @Content(mediaType = SpreadsheetTemplateController.XLSX_TYPE, schema = @Schema(type = SchemaType.STRING, format = "binary")))
    @APIResponse(responseCode = "400", description = "No sample names selected or request exceeds export limits")
    @APIResponse(responseCode = "403", description = "Project VIEWER membership required")
    @APIResponse(responseCode = "404", description = "Project, assay, or selected sample not found")
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
