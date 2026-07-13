package no.metatrack.server.sample.metadata;

import java.time.Instant;
import java.util.UUID;

public record SampleMetadataFieldResponse(
        UUID id,
        String key,
        String label,
        SampleMetadataFieldType type,
        Instant createdOn,
        Instant modifiedOn,
        Instant archivedOn) {
    public static SampleMetadataFieldResponse fromEntity(SampleMetadataField field) {
        return new SampleMetadataFieldResponse(
                field.id, field.key, field.label, field.type, field.createdOn, field.modifiedOn, field.archivedOn);
    }
}