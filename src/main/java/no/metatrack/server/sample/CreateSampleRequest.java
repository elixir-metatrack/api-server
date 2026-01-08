package no.metatrack.server.sample;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record CreateSampleRequest(
        @NotNull @NotBlank String name,
        @NotBlank String alias,
        @NotNull @NotBlank int taxId,
        @NotBlank int hostTaxId,
        @NotBlank String mlst,
        @NotBlank String isolationSource,
        @NotBlank LocalDate collectionDate,
        @NotBlank String location,
        @NotBlank String sequencingLab,
        @NotBlank String institution,
        @NotBlank String hostHealthState) {}
