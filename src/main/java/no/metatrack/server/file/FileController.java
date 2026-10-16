package no.metatrack.server.file;

import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import no.metatrack.server.project.ProjectRole;
import no.metatrack.server.project.ProjectRoleCheck;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;

import java.util.UUID;

@Path("/api/projects/{projectId}/files")
public class FileController {

    @Inject
    ProjectRoleCheck projectRoleCheck;

    @Inject
    FileIngestService fileIngestService;


    @DELETE
    @Authenticated
    @Path("/{fileUuid}")
    @Operation(summary = "Permanently delete an uploaded file",
            description = "Requires EDITOR or higher membership in the owning project. Purges all object versions "
                    + "and delete markers before committing metadata deletion. Linked-subproject membership is insufficient.")
    @APIResponses({
            @APIResponse(responseCode = "204", description = "Storage purged and file metadata deletion committed"),
            @APIResponse(responseCode = "401", description = "Authentication required"),
            @APIResponse(responseCode = "403", description = "Insufficient project membership"),
            @APIResponse(responseCode = "404", description = "File not found in the owning project or already deleted"),
            @APIResponse(responseCode = "409", description = "File is not uploaded"),
            @APIResponse(responseCode = "502", description = "Storage cleanup failed; metadata retained for retry")
    })
    public Response deleteFile(@PathParam("projectId") Long projectId, @PathParam("fileUuid") UUID fileUuid) {
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.EDITOR)) throw new ForbiddenException();
        fileIngestService.deleteFile(projectId, fileUuid);
        return Response.noContent().build();
    }

}
