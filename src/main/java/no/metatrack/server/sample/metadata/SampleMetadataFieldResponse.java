package no.metatrack.server.sample.metadata;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Project metadata-field definition. A non-null archivedOn value means the field is archived.")
public record SampleMetadataFieldResponse(
        UUID id,
        String key,
        String label,
        String tooltip,
        SampleMetadataFieldType type,
        Instant createdOn,
        Instant modifiedOn,
        Instant archivedOn) {
    public static SampleMetadataFieldResponse fromEntity(SampleMetadataField field) {
        return new SampleMetadataFieldResponse(
                field.id, field.key, field.label, field.tooltip, field.type, field.createdOn, field.modifiedOn, field.archivedOn);
    }
}