package no.metatrack.server.file;

import java.util.UUID;

public record FileResponse(
        UUID uuid,
        String name,
        String virtualPath,
        String objectKey,
        UploadStatus status,
        UUID sampleId,
        UUID assayId) {
    public static FileResponse fromEntity(File file) {
        return new FileResponse(
                file.uuid, file.fileName, file.virtualPath, file.objectKey, file.status,
                file.sample == null ? null : file.sample.id,
                file.assay == null ? null : file.assay.id);
    }
}
