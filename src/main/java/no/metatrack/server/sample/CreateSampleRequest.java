package no.metatrack.server.sample;

import jakarta.validation.constraints.NotBlank;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.LocalDate;
import java.util.Map;

@Schema(description = "Sample fields to create. Optional fields may be omitted; custom metadata must conform to active custom fields and project vocabulary rules. Custom field values use strings for TEXT and DATE, numbers for NUMBER, and booleans for BOOLEAN.")
public record CreateSampleRequest(
        @Schema(description = "Sample name, unique within the project")
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
        @Schema(description = "Custom values keyed by project metadata-field key")
        Map<String, Object> customMetadata) {}
