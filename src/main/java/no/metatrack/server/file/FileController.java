package no.metatrack.server.file;

import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import no.metatrack.server.project.ProjectRole;
import no.metatrack.server.project.ProjectRoleCheck;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;

@Path("/api")
public class FileController {
    @Inject
    PresignUrlService presignUrlService;

    @Inject
    ProjectRoleCheck projectRoleCheck;

    @ConfigProperty(name = "metatrack.file.presign-expiry-seconds")
    int presignExpirySeconds;

    @POST
    @Authenticated
    @Path("/files/presign-upload")
    public PresignResponse presignUpload(@Valid PresignUploadRequest request) throws Exception {
        Long projectId = request.projectId();
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.EDITOR))
            throw new WebApplicationException(Response.Status.FORBIDDEN);

        PresignedUrl presignedUrl = presignUrlService.presignedUploadUrl(
                request.projectId(), request.assayId(), request.sampleName(), request.fileName(), presignExpirySeconds);

        return new PresignResponse(
                presignedUrl.url(), presignedUrl.objectKey(), presignExpirySeconds,
                Instant.now().plusSeconds(presignExpirySeconds));
    }

    @POST
    @Authenticated
    @Path("/files/presign-download")
    public PresignResponse presignDownload(@Valid PresignDownloadRequest request) throws Exception {
        Long projectId = request.projectId();
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.VIEWER))
            throw new WebApplicationException(Response.Status.FORBIDDEN);

        PresignedUrl presignedUrl = presignUrlService.presignedDownloadUrl(
                request.projectId(), request.assayId(), request.sampleName(), request.fileName(), presignExpirySeconds);

        return new PresignResponse(
                presignedUrl.url(), presignedUrl.objectKey(), presignExpirySeconds,
                Instant.now().plusSeconds(presignExpirySeconds));
    }

}
