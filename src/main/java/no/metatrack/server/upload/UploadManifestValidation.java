package no.metatrack.server.upload;

import java.util.List;

public record UploadManifestValidation(
        List<ValidatedUploadManifestEntry> entries,
        List<UploadManifestIssue> issues
) {
    public UploadManifestValidation {
        entries = List.copyOf(entries);
        issues = List.copyOf(issues);
    }

    public boolean isValid() {
        return issues.isEmpty() && !entries.isEmpty();
    }
}