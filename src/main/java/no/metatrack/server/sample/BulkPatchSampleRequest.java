package no.metatrack.server.sample;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Schema(description = "Bulk partial sample update. Each entry is matched by sample name and validated independently; valid entries are applied even when other entries produce validation errors. Built-in null fields are unchanged; custom metadata is merged and null values remove existing custom values.")
public record BulkPatchSampleRequest(
        @Schema(description = "Non-empty list of sample updates")
        @Valid @NotEmpty List<SampleRequestData> sampleData) {
    public record SampleRequestData(
            @Schema(description = "Name of the existing sample within the project")
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
            String hospitalHealthInstitution,
            String projectAccession,
            String studyAccession,
            String sampleAccession,
            String secondaryAccession,
            @Schema(description = "Custom values keyed by project metadata-field key; values must match field types, and null removes the existing value")
            Map<String, Object> customMetadata) {}
}
