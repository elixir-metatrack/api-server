package no.metatrack.server.sample;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.LocalDate;
import java.util.Map;

@Schema(description = "Partial sample update. For built-in fields, only non-null values are applied; null and omitted values leave stored fields unchanged. Custom metadata is merged and a null custom value removes that value.")
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
