package no.metatrack.server.upload;

import no.metatrack.server.assay.Assay;
import no.metatrack.server.file.ReadRole;
import no.metatrack.server.sample.Sample;

public record ValidatedUploadManifestEntry(
        int row,
        String relativePath,
        Sample sample,
        Assay assay,
        String fileName,
        String destinationVirtualPath,
        String destinationObjectKey,
        String previousObjectKey,
        long expectedSizeBytes,
        String expectedSha256,
        String expectedMd5,
        String expectedUnencryptedMd5,
        ReadRole readRole
) {
}