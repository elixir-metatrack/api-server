package no.metatrack.server.isa;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import no.metatrack.server.project.ProjectRole;
import no.metatrack.server.project.ProjectRoleCheck;
import org.eclipse.microprofile.openapi.annotations.Operation;

@Path("/api/projects/{projectId}/exports/isa-json")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
public class IsaJsonExportController {
    @Inject ProjectRoleCheck projectRoleCheck;
    @Inject IsaJsonExportService exportService;

    @GET
    @Operation(summary = "Export project metadata as ISA-JSON (experimental)",
            description = "Exports the visible project scope. This is a metadata mapping, not a validated repository submission.")
    public Response export(@PathParam("projectId") Long projectId) {
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.VIEWER)) throw new ForbiddenException();
        return download(exportService.export(projectId, null), "project-" + projectId + "-isa.json");
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Export selected samples as ISA-JSON (experimental)",
            description = "Same as the project export, limited to the selected samples and their experiments and files.")
    public Response exportSelection(@PathParam("projectId") Long projectId, IsaJsonExportRequest request) {
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.VIEWER)) throw new ForbiddenException();
        if (request == null) throw new BadRequestException("Select samples to export");
        return download(exportService.export(projectId, request.validatedNames()),
                "project-" + projectId + "-selection-isa.json");
    }

    private Response download(ObjectNode document, String filename) {
        return Response.ok(document.toPrettyString())
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .header("Cache-Control", "private, no-store")
                .build();
    }
}
