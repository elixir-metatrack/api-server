package no.metatrack.server.sample;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record SampleResponse(
        UUID id,
        String name,
        String alias,
        int taxId,
        int hostTaxId,
        String mlst,
        String isolationSource,
        LocalDate collectionDate,
        String location,
        String sequencingLab,
        String institution,
        String hostHealthState,
        Instant createdOn,
        Instant modifiedOn) {
    public static SampleResponse fromEntity(Sample sample) {
        return new SampleResponse(
                sample.id,
                sample.name,
                sample.alias,
                sample.taxId,
                sample.hostTaxId,
                sample.mlst,
                sample.isolationSource,
                sample.collectionDate,
                sample.location,
                sample.sequencingLab,
                sample.institution,
                sample.hostHealthState,
                sample.createdOn,
                sample.modifiedOn);
    }
}
