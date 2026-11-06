package no.metatrack.server.upload;

import java.util.List;
import java.util.UUID;

public record UploadBatchRegistrationResult(UUID batchId, List<UploadManifestIssue> issues) {
    public UploadBatchRegistrationResult {
        issues = List.copyOf(issues);
    }

    public boolean accepted() {
        return batchId != null;
    }
}