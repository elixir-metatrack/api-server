package no.metatrack.server.sample;

public record CSVUploadRowError(String sampleName, String errorColumn, String errorMessage) {}
