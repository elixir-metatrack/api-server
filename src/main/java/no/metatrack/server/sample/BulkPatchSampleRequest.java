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
            String hostHealthState,
            String projectTitle,
            String description,
            String isolate,
            String collectedBy,
            Double latitude,
            Double longitude,
            String environmentalSample,
            String hostAssociated,
            String hostCommonName,
            String hostSubjectId,
            String collectorName,
            String collectingInstitution,
            String hostSex,
            String influenzaTestMethod,
            String influenzaTestResult,
            String otherPathogensTested,
            String otherPathogensTestResult,
            String hostHabitat,
            String isolationSourceHostAssociated,
            String hostBehaviour,
            String isolationSourceNonHostAssociated,
            String influenzaVirusType,
            String influenzaSubType,
            String serovar,
            String strain,
            String hostAge,
            String county,
            String commune,
            String hospitalHealthInstitution) {}
}
