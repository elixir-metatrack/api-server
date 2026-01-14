package no.metatrack.server.file;

import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;

import java.time.Instant;

@Path("/api/files")
public class FileController {
    @Inject
    PresignUrlService presignUrlService;

    @POST
    @Path("/presign-upload")
    public PresignResponse presignUpload(PresignUploadRequest request) throws Exception {
        int expiry = 600;

        String url = presignUrlService.presignedUploadUrl(
                request.projectId(), request.sampleName(), request.fileName(), expiry);

        return new PresignResponse(
                url, request.fileName(), expiry, Instant.now().plusSeconds(expiry));
    }

    @POST
    @Path("/presign-download")
    public PresignResponse presignDownload(PresignDownloadRequest request) throws Exception {
        int expiry = 600;

        String url = presignUrlService.presignedDownloadUrl(
                request.projectId(), request.sampleName(), request.fileName(), expiry);

        return new PresignResponse(
                url, request.fileName(), expiry, Instant.now().plusSeconds(expiry));
    }
}
