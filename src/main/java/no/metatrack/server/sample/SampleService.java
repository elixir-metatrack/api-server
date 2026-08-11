package no.metatrack.server.sample;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import no.metatrack.server.project.Project;
import no.metatrack.server.sample.metadata.SampleMetadataService;
import no.metatrack.server.sample.vocabulary.SampleValidationViolation;
import no.metatrack.server.sample.vocabulary.SampleVocabularyRules;
import no.metatrack.server.sample.vocabulary.SampleVocabularyService;
import no.metatrack.server.sample.vocabulary.SampleVocabularyValidationException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class SampleService {
    @Inject
    SampleMetadataService metadataService;

    @Inject
    SampleVocabularyService vocabularyService;

    public List<Sample> getAllSamples(Long projectId) {
        return Sample.getAllSamplesInProject(projectId);
    }

    public Sample getSampleById(UUID sampleId, Long projectId) {
        return Sample.<Sample>find("id = ?1 and project.id = ?2", sampleId, projectId)
                .firstResultOptional().orElseThrow(NotFoundException::new);
    }

    public Sample getSampleByName(String name, Long projectId) {
        return Sample.findBySampleNameInProject(name, projectId).orElseThrow(NotFoundException::new);
    }

    @Transactional
    public Sample createSample(
            Long projectId,
            String name,
            String alias,
            Integer taxId,
            Integer hostTaxId,
            String mlst,
            String location,
            String sequencingLab,
            String institution,
            String isolationSource,
            LocalDate collectionDate,
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
            Map<String, Object> customMetadata) {

        Project project = (Project) Project.findByIdOptional(projectId).orElseThrow(NotFoundException::new);

        if (Sample.sampleExistsByName(name, projectId))
            throw new WebApplicationException("Sample with name " + name + " already exists", Response.Status.CONFLICT);

        validate(projectId, name, builtInValues(
                alias, mlst, location, sequencingLab, institution, isolationSource, hostHealthState, projectTitle,
                description, isolate, collectedBy, environmentalSample, hostAssociated, hostCommonName, hostSubjectId,
                collectorName, collectingInstitution, hostSex, influenzaTestMethod, influenzaTestResult,
                otherPathogensTested, otherPathogensTestResult, hostHabitat, isolationSourceHostAssociated,
                hostBehaviour, isolationSourceNonHostAssociated, influenzaVirusType, influenzaSubType, serovar,
                strain, hostAge, county, commune, hospitalHealthInstitution), customMetadata);

        Sample sample = new Sample();
        sample.name = name;
        if (alias != null) sample.alias = trim(alias);
        if (taxId != null) sample.taxId = taxId;
        if (hostTaxId != null) sample.hostTaxId = hostTaxId;
        if (mlst != null) sample.mlst = trim(mlst);
        if (location != null) sample.location = trim(location);
        if (sequencingLab != null) sample.sequencingLab = trim(sequencingLab);
        if (institution != null) sample.institution = trim(institution);
        if (isolationSource != null) sample.isolationSource = trim(isolationSource);
        if (collectionDate != null) sample.collectionDate = collectionDate;
        if (hostHealthState != null) sample.hostHealthState = trim(hostHealthState);
        if (projectTitle != null) sample.projectTitle = trim(projectTitle);
        if (description != null) sample.description = trim(description);
        if (isolate != null) sample.isolate = trim(isolate);
        if (collectedBy != null) sample.collectedBy = trim(collectedBy);
        if (latitude != null) sample.latitude = latitude;
        if (longitude != null) sample.longitude = longitude;
        if (environmentalSample != null) sample.environmentalSample = trim(environmentalSample);
        if (hostAssociated != null) sample.hostAssociated = trim(hostAssociated);
        if (hostCommonName != null) sample.hostCommonName = trim(hostCommonName);
        if (hostSubjectId != null) sample.hostSubjectId = trim(hostSubjectId);
        if (collectorName != null) sample.collectorName = trim(collectorName);
        if (collectingInstitution != null) sample.collectingInstitution = trim(collectingInstitution);
        if (hostSex != null) sample.hostSex = trim(hostSex);
        if (influenzaTestMethod != null) sample.influenzaTestMethod = trim(influenzaTestMethod);
        if (influenzaTestResult != null) sample.influenzaTestResult = trim(influenzaTestResult);
        if (otherPathogensTested != null) sample.otherPathogensTested = trim(otherPathogensTested);
        if (otherPathogensTestResult != null) sample.otherPathogensTestResult = trim(otherPathogensTestResult);
        if (hostHabitat != null) sample.hostHabitat = trim(hostHabitat);
        if (isolationSourceHostAssociated != null) sample.isolationSourceHostAssociated = trim(isolationSourceHostAssociated);
        if (hostBehaviour != null) sample.hostBehaviour = trim(hostBehaviour);
        if (isolationSourceNonHostAssociated != null) sample.isolationSourceNonHostAssociated = trim(isolationSourceNonHostAssociated);
        if (influenzaVirusType != null) sample.influenzaVirusType = trim(influenzaVirusType);
        if (influenzaSubType != null) sample.influenzaSubType = trim(influenzaSubType);
        if (serovar != null) sample.serovar = trim(serovar);
        if (strain != null) sample.strain = trim(strain);
        if (hostAge != null) sample.hostAge = trim(hostAge);
        if (county != null) sample.county = trim(county);
        if (commune != null) sample.commune = trim(commune);
        if (hospitalHealthInstitution != null) sample.hospitalHealthInstitution = trim(hospitalHealthInstitution);

        sample.createdOn = Instant.now();
        sample.modifiedOn = Instant.now();
        sample.project = project;

        project.samples.add(sample);
        sample.persist();
        metadataService.apply(projectId, sample, customMetadata);
        return sample;
    }

    @Transactional
    public void updateSample(
            Long projectId,
            UUID sampleId,
            String name,
            String alias,
            Integer taxId,
            Integer hostTaxId,
            String mlst,
            String location,
            String sequencingLab,
            String institution,
            String isolationSource,
            LocalDate collectionDate,
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
            Map<String, Object> customMetadata) {

        Project project = (Project) Project.findByIdOptional(projectId).orElseThrow(NotFoundException::new);

        Sample sample = Sample.<Sample>find("id = ?1 and project.id = ?2", sampleId, projectId)
                .firstResultOptional().orElseThrow(NotFoundException::new);
        validate(projectId, sample.name, builtInValues(
                alias, mlst, location, sequencingLab, institution, isolationSource, hostHealthState, projectTitle,
                description, isolate, collectedBy, environmentalSample, hostAssociated, hostCommonName, hostSubjectId,
                collectorName, collectingInstitution, hostSex, influenzaTestMethod, influenzaTestResult,
                otherPathogensTested, otherPathogensTestResult, hostHabitat, isolationSourceHostAssociated,
                hostBehaviour, isolationSourceNonHostAssociated, influenzaVirusType, influenzaSubType, serovar,
                strain, hostAge, county, commune, hospitalHealthInstitution), customMetadata);
        if (name != null) sample.name = name;
        if (alias != null) sample.alias = trim(alias);
        if (taxId != null) sample.taxId = taxId;
        if (hostTaxId != null) sample.hostTaxId = hostTaxId;
        if (mlst != null) sample.mlst = trim(mlst);
        if (location != null) sample.location = trim(location);
        if (sequencingLab != null) sample.sequencingLab = trim(sequencingLab);
        if (institution != null) sample.institution = trim(institution);
        if (isolationSource != null) sample.isolationSource = trim(isolationSource);
        if (collectionDate != null) sample.collectionDate = collectionDate;
        if (hostHealthState != null) sample.hostHealthState = trim(hostHealthState);
        if (projectTitle != null) sample.projectTitle = trim(projectTitle);
        if (description != null) sample.description = trim(description);
        if (isolate != null) sample.isolate = trim(isolate);
        if (collectedBy != null) sample.collectedBy = trim(collectedBy);
        if (latitude != null) sample.latitude = latitude;
        if (longitude != null) sample.longitude = longitude;
        if (environmentalSample != null) sample.environmentalSample = trim(environmentalSample);
        if (hostAssociated != null) sample.hostAssociated = trim(hostAssociated);
        if (hostCommonName != null) sample.hostCommonName = trim(hostCommonName);
        if (hostSubjectId != null) sample.hostSubjectId = trim(hostSubjectId);
        if (collectorName != null) sample.collectorName = trim(collectorName);
        if (collectingInstitution != null) sample.collectingInstitution = trim(collectingInstitution);
        if (hostSex != null) sample.hostSex = trim(hostSex);
        if (influenzaTestMethod != null) sample.influenzaTestMethod = trim(influenzaTestMethod);
        if (influenzaTestResult != null) sample.influenzaTestResult = trim(influenzaTestResult);
        if (otherPathogensTested != null) sample.otherPathogensTested = trim(otherPathogensTested);
        if (otherPathogensTestResult != null) sample.otherPathogensTestResult = trim(otherPathogensTestResult);
        if (hostHabitat != null) sample.hostHabitat = trim(hostHabitat);
        if (isolationSourceHostAssociated != null) sample.isolationSourceHostAssociated = trim(isolationSourceHostAssociated);
        if (hostBehaviour != null) sample.hostBehaviour = trim(hostBehaviour);
        if (isolationSourceNonHostAssociated != null) sample.isolationSourceNonHostAssociated = trim(isolationSourceNonHostAssociated);
        if (influenzaVirusType != null) sample.influenzaVirusType = trim(influenzaVirusType);
        if (influenzaSubType != null) sample.influenzaSubType = trim(influenzaSubType);
        if (serovar != null) sample.serovar = trim(serovar);
        if (strain != null) sample.strain = trim(strain);
        if (hostAge != null) sample.hostAge = trim(hostAge);
        if (county != null) sample.county = trim(county);
        if (commune != null) sample.commune = trim(commune);
        if (hospitalHealthInstitution != null) sample.hospitalHealthInstitution = trim(hospitalHealthInstitution);

        sample.modifiedOn = Instant.now();

        sample.project = project;
        metadataService.apply(projectId, sample, customMetadata);
    }

    @Transactional
    public void deleteSample(Long projectId, UUID sampleId) {
        Sample sample = Sample.<Sample>find("id = ?1 and project.id = ?2", sampleId, projectId)
                .firstResultOptional().orElseThrow(NotFoundException::new);
        sample.delete();
    }

    @Transactional
    public List<SampleValidationViolation> bulkPatchSamples(Long projectId, BulkPatchSampleRequest request) {
        List<SampleValidationViolation> errors = new ArrayList<>();
        SampleVocabularyRules vocabularyRules = vocabularyService.loadRules(projectId);

        for (var data : request.sampleData()) {

            Optional<Sample> sampleOptional = Sample.findBySampleNameInProject(data.name(), projectId);
            if (sampleOptional.isEmpty()) {
                errors.add(new SampleValidationViolation(
                        data.name(), "name", data.name(), "Sample does not exist"));
                continue;
            }

            Sample sample = sampleOptional.get();

            List<SampleValidationViolation> violations = SampleVocabularyService.validate(
                    vocabularyRules, data.name(), builtInValues(data), data.customMetadata());
            if (!violations.isEmpty()) {
                errors.addAll(violations);
                continue;
            }

            if (data.alias() != null) sample.alias = trim(data.alias());
            if (data.taxId() != null) sample.taxId = data.taxId();
            if (data.hostTaxId() != null) sample.hostTaxId = data.hostTaxId();
            if (data.mlst() != null) sample.mlst = trim(data.mlst());
            if (data.location() != null) sample.location = trim(data.location());
            if (data.sequencingLab() != null) sample.sequencingLab = trim(data.sequencingLab());
            if (data.institution() != null) sample.institution = trim(data.institution());
            if (data.isolationSource() != null) sample.isolationSource = trim(data.isolationSource());
            if (data.collectionDate() != null) sample.collectionDate = data.collectionDate();
            if (data.hostHealthState() != null) sample.hostHealthState = trim(data.hostHealthState());
            if (data.projectTitle() != null) sample.projectTitle = trim(data.projectTitle());
            if (data.description() != null) sample.description = trim(data.description());
            if (data.isolate() != null) sample.isolate = trim(data.isolate());
            if (data.collectedBy() != null) sample.collectedBy = trim(data.collectedBy());
            if (data.latitude() != null) sample.latitude = data.latitude();
            if (data.longitude() != null) sample.longitude = data.longitude();
            if (data.environmentalSample() != null) sample.environmentalSample = trim(data.environmentalSample());
            if (data.hostAssociated() != null) sample.hostAssociated = trim(data.hostAssociated());
            if (data.hostCommonName() != null) sample.hostCommonName = trim(data.hostCommonName());
            if (data.hostSubjectId() != null) sample.hostSubjectId = trim(data.hostSubjectId());
            if (data.collectorName() != null) sample.collectorName = trim(data.collectorName());
            if (data.collectingInstitution() != null) sample.collectingInstitution = trim(data.collectingInstitution());
            if (data.hostSex() != null) sample.hostSex = trim(data.hostSex());
            if (data.influenzaTestMethod() != null) sample.influenzaTestMethod = trim(data.influenzaTestMethod());
            if (data.influenzaTestResult() != null) sample.influenzaTestResult = trim(data.influenzaTestResult());
            if (data.otherPathogensTested() != null) sample.otherPathogensTested = trim(data.otherPathogensTested());
            if (data.otherPathogensTestResult() != null) sample.otherPathogensTestResult = trim(data.otherPathogensTestResult());
            if (data.hostHabitat() != null) sample.hostHabitat = trim(data.hostHabitat());
            if (data.isolationSourceHostAssociated() != null) sample.isolationSourceHostAssociated = trim(data.isolationSourceHostAssociated());
            if (data.hostBehaviour() != null) sample.hostBehaviour = trim(data.hostBehaviour());
            if (data.isolationSourceNonHostAssociated() != null) sample.isolationSourceNonHostAssociated = trim(data.isolationSourceNonHostAssociated());
            if (data.influenzaVirusType() != null) sample.influenzaVirusType = trim(data.influenzaVirusType());
            if (data.influenzaSubType() != null) sample.influenzaSubType = trim(data.influenzaSubType());
            if (data.serovar() != null) sample.serovar = trim(data.serovar());
            if (data.strain() != null) sample.strain = trim(data.strain());
            if (data.hostAge() != null) sample.hostAge = trim(data.hostAge());
            if (data.county() != null) sample.county = trim(data.county());
            if (data.commune() != null) sample.commune = trim(data.commune());
            if (data.hospitalHealthInstitution() != null) sample.hospitalHealthInstitution = trim(data.hospitalHealthInstitution());
            metadataService.apply(projectId, sample, data.customMetadata());
            sample.modifiedOn = Instant.now();
        }

        if (errors.isEmpty()) {
            return null;
        }

        return errors;
    }

    private void validate(
            Long projectId, String sample, Map<String, String> builtInValues, Map<String, Object> customMetadata) {
        List<SampleValidationViolation> violations = vocabularyService.validate(
                projectId, sample, builtInValues, customMetadata);
        if (!violations.isEmpty()) throw new SampleVocabularyValidationException(violations);
    }

    private Map<String, String> builtInValues(BulkPatchSampleRequest.SampleRequestData data) {
        return builtInValues(
                data.alias(), data.mlst(), data.location(), data.sequencingLab(), data.institution(),
                data.isolationSource(), data.hostHealthState(), data.projectTitle(), data.description(), data.isolate(),
                data.collectedBy(), data.environmentalSample(), data.hostAssociated(), data.hostCommonName(),
                data.hostSubjectId(), data.collectorName(), data.collectingInstitution(), data.hostSex(),
                data.influenzaTestMethod(), data.influenzaTestResult(), data.otherPathogensTested(),
                data.otherPathogensTestResult(), data.hostHabitat(), data.isolationSourceHostAssociated(),
                data.hostBehaviour(), data.isolationSourceNonHostAssociated(), data.influenzaVirusType(),
                data.influenzaSubType(), data.serovar(), data.strain(), data.hostAge(), data.county(), data.commune(),
                data.hospitalHealthInstitution());
    }

    private Map<String, String> builtInValues(
            String alias, String mlst, String location, String sequencingLab, String institution,
            String isolationSource, String hostHealthState, String projectTitle, String description, String isolate,
            String collectedBy, String environmentalSample, String hostAssociated, String hostCommonName,
            String hostSubjectId, String collectorName, String collectingInstitution, String hostSex,
            String influenzaTestMethod, String influenzaTestResult, String otherPathogensTested,
            String otherPathogensTestResult, String hostHabitat, String isolationSourceHostAssociated,
            String hostBehaviour, String isolationSourceNonHostAssociated, String influenzaVirusType,
            String influenzaSubType, String serovar, String strain, String hostAge, String county, String commune,
            String hospitalHealthInstitution) {
        Map<String, String> values = new java.util.LinkedHashMap<>();
        values.put("alias", alias);
        values.put("mlst", mlst);
        values.put("location", location);
        values.put("sequencing_lab", sequencingLab);
        values.put("institution", institution);
        values.put("isolation_source", isolationSource);
        values.put("host_health_state", hostHealthState);
        values.put("project_title", projectTitle);
        values.put("description", description);
        values.put("isolate", isolate);
        values.put("collected_by", collectedBy);
        values.put("environmental_sample", environmentalSample);
        values.put("host_associated", hostAssociated);
        values.put("host_common_name", hostCommonName);
        values.put("host_subject_id", hostSubjectId);
        values.put("collector_name", collectorName);
        values.put("collecting_institution", collectingInstitution);
        values.put("host_sex", hostSex);
        values.put("influenza_test_method", influenzaTestMethod);
        values.put("influenza_test_result", influenzaTestResult);
        values.put("other_pathogens_tested", otherPathogensTested);
        values.put("other_pathogens_test_result", otherPathogensTestResult);
        values.put("host_habitat", hostHabitat);
        values.put("isolation_source_host_associated", isolationSourceHostAssociated);
        values.put("host_behaviour", hostBehaviour);
        values.put("isolation_source_non_host_associated", isolationSourceNonHostAssociated);
        values.put("influenza_virus_type", influenzaVirusType);
        values.put("influenza_sub_type", influenzaSubType);
        values.put("serovar", serovar);
        values.put("strain", strain);
        values.put("host_age", hostAge);
        values.put("county", county);
        values.put("commune", commune);
        values.put("hospital_health_institution", hospitalHealthInstitution);
        return values;
    }

    private String trim(String value) {
        return value.trim();
    }

}
