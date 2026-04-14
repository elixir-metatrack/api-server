package no.metatrack.server.file;

import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import no.metatrack.server.project.ProjectRole;
import no.metatrack.server.project.ProjectRoleCheck;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;

@Path("/api/files")
public class FileController {
    @Inject
    PresignUrlService presignUrlService;

    @Inject
    ProjectRoleCheck projectRoleCheck;

    @ConfigProperty(name = "metatrack.file.presign-expiry-seconds")
    int presignExpirySeconds;

    @POST
    @Authenticated
    @Path("/presign-upload")
    public PresignResponse presignUpload(@Valid PresignUploadRequest request) throws Exception {
        Long projectId = request.projectId();
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.EDITOR))
            throw new WebApplicationException(Response.Status.FORBIDDEN);

        String url = presignUrlService.presignedUploadUrl(
                request.projectId(), request.sampleName(), request.fileName(), presignExpirySeconds);

        return new PresignResponse(
                url, request.fileName(), presignExpirySeconds, Instant.now().plusSeconds(presignExpirySeconds));
    }

    @POST
    @Authenticated
    @Path("/presign-download")
    public PresignResponse presignDownload(@Valid PresignDownloadRequest request) throws Exception {
        Long projectId = request.projectId();
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.VIEWER))
            throw new WebApplicationException(Response.Status.FORBIDDEN);

        String url = presignUrlService.presignedDownloadUrl(
                request.projectId(), request.sampleName(), request.fileName(), presignExpirySeconds);

        return new PresignResponse(
                url, request.fileName(), presignExpirySeconds, Instant.now().plusSeconds(presignExpirySeconds));
    }
}
