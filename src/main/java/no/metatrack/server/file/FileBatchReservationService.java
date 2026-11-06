package no.metatrack.server.file;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.WebApplicationException;
import no.metatrack.server.assay.Assay;
import no.metatrack.server.sample.Sample;
import no.metatrack.server.upload.UploadBatchEntry;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class FileBatchReservationService {
    public File reserve(UploadBatchEntry entry) {
        Sample sample = entry.sample;
        Assay assay = entry.assay;
        String virtualPath = PresignUrlService.virtualPath(
                sample.project.id, assay.id, sample.name, entry.fileName);
        Optional<File> existing = File.findByVirtualPathForUpdate(virtualPath);
        if (existing.isPresent()) {
            File file = existing.get();
            Optional<String> validationError = File.validateImportPending(
                    sample.project.id,
                    assay.id,
                    sample,
                    assay,
                    entry.fileName,
                    file.md5,
                    file.unencryptedMd5,
                    entry.readRole);
            if (validationError.isPresent()) {
                throw new WebApplicationException(validationError.get(), 409);
            }
            if (file.uploadBatchEntry != null) {
                throw new WebApplicationException("File destination is already reserved by an upload batch", 409);
            }
            if (file.uploadedBy != null) {
                throw new WebApplicationException(
                        "A pending direct-upload destination cannot be reserved by an SFTP batch", 409);
            }
            if (!Objects.equals(file.md5, entry.expectedMd5)
                    || !Objects.equals(file.unencryptedMd5, entry.expectedUnencryptedMd5)) {
                throw new WebApplicationException("Pending file checksum metadata changed during registration", 409);
            }
            if (!Objects.equals(file.objectKey, entry.previousObjectKey)) {
                throw new WebApplicationException("Pending file destination changed during registration", 409);
            }
            file.uploadBatchEntry = entry;
            if (file.readRole == null) file.readRole = entry.readRole;
            entry.expectedMd5 = file.md5;
            entry.expectedUnencryptedMd5 = file.unencryptedMd5;
            entry.destinationVirtualPath = file.virtualPath;
            entry.file = file;
            return file;
        }

        File file = new File();
        file.uuid = UUID.randomUUID();
        file.fileName = entry.fileName;
        file.readRole = entry.readRole;
        file.virtualPath = virtualPath;
        file.objectKey = entry.destinationObjectKey;
        file.status = UploadStatus.PENDING;
        file.sample = sample;
        file.assay = assay;
        file.uploadBatchEntry = entry;
        file.persist();
        sample.files.add(file);
        entry.destinationVirtualPath = virtualPath;
        entry.file = file;
        return file;
    }

    public void release(UploadBatchEntry entry) {
        Optional<File> reservedFile = File.findByVirtualPathForUpdate(entry.destinationVirtualPath);
        if (reservedFile.isPresent()) {
            File file = reservedFile.get();
            if (file.uploadBatchEntry != null && file.uploadBatchEntry.id.equals(entry.id)
                    && file.status == UploadStatus.PENDING) {
                file.uploadBatchEntry = null;
            }
        }
    }
}