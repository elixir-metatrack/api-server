package no.metatrack.server.upload;

public record UploadManifestIssue(int row, String column, String message) {
}