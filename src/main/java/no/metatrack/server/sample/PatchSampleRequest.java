package no.metatrack.server.sample;

import java.time.LocalDate;

public record PatchSampleRequest(
        String name,
        String alias,
        Integer taxId,
        Integer hostTaxId,
        String mlst,
        String isolationSource,
        LocalDate collectionDate,
        String location,
        String sequencingLab,
        String institution,
        String hostHealthState) {}
