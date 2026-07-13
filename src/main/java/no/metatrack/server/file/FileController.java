package no.metatrack.server.file;

import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import no.metatrack.server.project.Project;
import no.metatrack.server.project.ProjectRole;
import no.metatrack.server.project.ProjectRoleCheck;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Path("/api")
public class FileController {
    @Inject
    PresignUrlService presignUrlService;

    @Inject
    FileService fileService;

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

    @GET
    @Authenticated
    @Path("/projects/{projectId}/assays/{assayId}/samples/{sampleId}/files")
    public List<FileResponse> getFilesInSampleAndAssay(
            @PathParam("projectId") Long projectId,
            @PathParam("assayId") UUID assayId,
            @PathParam("sampleId") UUID sampleId) {
        if (!Project.projectExists(projectId)) throw new NotFoundException("Project not found");
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.VIEWER)) throw new ForbiddenException();

        return fileService.getFilesInSampleAndAssay(projectId, assayId, sampleId).stream()
                .map(FileResponse::fromEntity)
                .toList();
    }
}
