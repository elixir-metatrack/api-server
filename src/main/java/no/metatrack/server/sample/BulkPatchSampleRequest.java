package no.metatrack.server.sample;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.time.LocalDate;
import java.util.List;

public record BulkPatchSampleRequest(@Valid @NotEmpty List<SampleRequestData> sampleData) {
    public record SampleRequestData(
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
}
