package no.metatrack.server.sample.metadata;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Partial metadata-field update. Non-null label, tooltip, and archived values are applied.")
public record PatchSampleMetadataFieldRequest(String label, Boolean archived, String tooltip) {}