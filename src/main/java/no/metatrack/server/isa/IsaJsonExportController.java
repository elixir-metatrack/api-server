package no.metatrack.server.isa;

import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
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
        return Response.ok(exportService.export(projectId).toPrettyString())
                .header("Content-Disposition", "attachment; filename=\"project-" + projectId + "-isa.json\"")
                .header("Cache-Control", "private, no-store")
                .build();
    }
}
