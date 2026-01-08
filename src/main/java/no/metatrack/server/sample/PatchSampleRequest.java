package no.metatrack.server.sample;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;

public record PatchSampleRequest(
        @NotBlank String name,
        @NotBlank String alias,
        @NotBlank int taxId,
        @NotBlank int hostTaxId,
        @NotBlank String mlst,
        @NotBlank String isolationSource,
        @NotBlank LocalDate collectionDate,
        @NotBlank String location,
        @NotBlank String sequencingLab,
        @NotBlank String institution,
        @NotBlank String hostHealthState) {}
