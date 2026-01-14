package no.metatrack.server.sample;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;

public record CreateSampleRequest(
        @NotBlank String name,
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
