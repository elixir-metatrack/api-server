package no.metatrack.server.file;

import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import no.metatrack.server.project.ProjectRole;
import no.metatrack.server.project.ProjectRoleCheck;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.ExampleObject;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.Instant;

@Path("/api")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "File access", description = "Temporary object-storage upload and download URLs")
public class PresignController {
    @Inject
    PresignUrlService presignUrlService;

    @Inject
    ProjectRoleCheck projectRoleCheck;

    @ConfigProperty(name = "metatrack.file.presign-expiry-seconds")
    int presignExpirySeconds;

    @POST
    @Authenticated
    @Path("/files/presign-upload")
    @Operation(summary = "Create a presigned upload URL", description = "Requires EDITOR membership in the project. The response contains an expiring URL and object key; this endpoint does not upload the file bytes.")
    @APIResponse(responseCode = "200", description = "Upload URL and expiration details", content = @Content(schema = @Schema(implementation = PresignResponse.class)))
    @APIResponse(responseCode = "400", description = "Request fields are invalid")
    @APIResponse(responseCode = "401", description = "Authentication required")
    @APIResponse(responseCode = "403", description = "Project EDITOR membership required")
    @APIResponse(responseCode = "404", description = "Project, assay, or sample not found in the requested relationship")
    public PresignResponse presignUpload(
            @RequestBody(description = "Project, assay, sample, and file name for the upload",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = PresignUploadRequest.class),
                            examples = @ExampleObject(value = "{\"projectId\":1,\"assayId\":\"00000000-0000-0000-0000-000000000001\",\"sampleName\":\"sample-001\",\"fileName\":\"reads_R1.fastq.gz\"}")))
            @Valid PresignUploadRequest request) throws Exception {
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
    @Operation(summary = "Create a presigned download URL", description = "Requires VIEWER membership in the project. The response contains an expiring URL and object key; file contents are not returned by this endpoint.")
    @APIResponse(responseCode = "200", description = "Download URL and expiration details", content = @Content(schema = @Schema(implementation = PresignResponse.class)))
    @APIResponse(responseCode = "400", description = "Request fields are invalid")
    @APIResponse(responseCode = "401", description = "Authentication required")
    @APIResponse(responseCode = "403", description = "Project VIEWER membership required")
    @APIResponse(responseCode = "404", description = "Project, assay, sample, or file not found in the requested relationship")
    public PresignResponse presignDownload(
            @RequestBody(description = "Project, assay, sample, and stored file name for the download",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = PresignDownloadRequest.class),
                            examples = @ExampleObject(value = "{\"projectId\":1,\"assayId\":\"00000000-0000-0000-0000-000000000001\",\"sampleName\":\"sample-001\",\"fileName\":\"reads_R1.fastq.gz\"}")))
            @Valid PresignDownloadRequest request) throws Exception {
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