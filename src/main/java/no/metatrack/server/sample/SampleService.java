package no.metatrack.server.sample;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import no.metatrack.server.project.Project;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class SampleService {

    public List<Sample> getAllSamples(Long projectId) {
        return Sample.getAllSamplesInProject(projectId);
    }

    public Sample getSampleById(UUID sampleId) {
        return Sample.findSampleById(sampleId).orElseThrow(NotFoundException::new);
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
            String hospitalHealthInstitution) {

        Project project = (Project) Project.findByIdOptional(projectId).orElseThrow(NotFoundException::new);

        if (Sample.sampleExistsByName(name, projectId))
            throw new WebApplicationException("Sample with name " + name + " already exists", Response.Status.CONFLICT);

        Sample sample = new Sample();
        sample.name = name;
        if (alias != null) sample.alias = alias;
        if (taxId != null) sample.taxId = taxId;
        if (hostTaxId != null) sample.hostTaxId = hostTaxId;
        if (mlst != null) sample.mlst = mlst;
        if (location != null) sample.location = location;
        if (sequencingLab != null) sample.sequencingLab = sequencingLab;
        if (institution != null) sample.institution = institution;
        if (isolationSource != null) sample.isolationSource = isolationSource;
        if (collectionDate != null) sample.collectionDate = collectionDate;
        if (hostHealthState != null) sample.hostHealthState = hostHealthState;
        if (projectTitle != null) sample.projectTitle = projectTitle;
        if (description != null) sample.description = description;
        if (isolate != null) sample.isolate = isolate;
        if (collectedBy != null) sample.collectedBy = collectedBy;
        if (latitude != null) sample.latitude = latitude;
        if (longitude != null) sample.longitude = longitude;
        if (environmentalSample != null) sample.environmentalSample = environmentalSample;
        if (hostAssociated != null) sample.hostAssociated = hostAssociated;
        if (hostCommonName != null) sample.hostCommonName = hostCommonName;
        if (hostSubjectId != null) sample.hostSubjectId = hostSubjectId;
        if (collectorName != null) sample.collectorName = collectorName;
        if (collectingInstitution != null) sample.collectingInstitution = collectingInstitution;
        if (hostSex != null) sample.hostSex = hostSex;
        if (influenzaTestMethod != null) sample.influenzaTestMethod = influenzaTestMethod;
        if (influenzaTestResult != null) sample.influenzaTestResult = influenzaTestResult;
        if (otherPathogensTested != null) sample.otherPathogensTested = otherPathogensTested;
        if (otherPathogensTestResult != null) sample.otherPathogensTestResult = otherPathogensTestResult;
        if (hostHabitat != null) sample.hostHabitat = hostHabitat;
        if (isolationSourceHostAssociated != null) sample.isolationSourceHostAssociated = isolationSourceHostAssociated;
        if (hostBehaviour != null) sample.hostBehaviour = hostBehaviour;
        if (isolationSourceNonHostAssociated != null) sample.isolationSourceNonHostAssociated = isolationSourceNonHostAssociated;
        if (influenzaVirusType != null) sample.influenzaVirusType = influenzaVirusType;
        if (influenzaSubType != null) sample.influenzaSubType = influenzaSubType;
        if (serovar != null) sample.serovar = serovar;
        if (strain != null) sample.strain = strain;
        if (hostAge != null) sample.hostAge = hostAge;
        if (county != null) sample.county = county;
        if (commune != null) sample.commune = commune;
        if (hospitalHealthInstitution != null) sample.hospitalHealthInstitution = hospitalHealthInstitution;

        sample.createdOn = Instant.now();
        sample.modifiedOn = Instant.now();
        sample.project = project;

        project.samples.add(sample);
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
            String hospitalHealthInstitution) {

        Project project = (Project) Project.findByIdOptional(projectId).orElseThrow(NotFoundException::new);

        Sample sample = Sample.findSampleById(sampleId).orElseThrow(NotFoundException::new);
        if (name != null) sample.name = name;
        if (alias != null) sample.alias = alias;
        if (taxId != null) sample.taxId = taxId;
        if (hostTaxId != null) sample.hostTaxId = hostTaxId;
        if (mlst != null) sample.mlst = mlst;
        if (location != null) sample.location = location;
        if (sequencingLab != null) sample.sequencingLab = sequencingLab;
        if (institution != null) sample.institution = institution;
        if (isolationSource != null) sample.isolationSource = isolationSource;
        if (collectionDate != null) sample.collectionDate = collectionDate;
        if (hostHealthState != null) sample.hostHealthState = hostHealthState;
        if (projectTitle != null) sample.projectTitle = projectTitle;
        if (description != null) sample.description = description;
        if (isolate != null) sample.isolate = isolate;
        if (collectedBy != null) sample.collectedBy = collectedBy;
        if (latitude != null) sample.latitude = latitude;
        if (longitude != null) sample.longitude = longitude;
        if (environmentalSample != null) sample.environmentalSample = environmentalSample;
        if (hostAssociated != null) sample.hostAssociated = hostAssociated;
        if (hostCommonName != null) sample.hostCommonName = hostCommonName;
        if (hostSubjectId != null) sample.hostSubjectId = hostSubjectId;
        if (collectorName != null) sample.collectorName = collectorName;
        if (collectingInstitution != null) sample.collectingInstitution = collectingInstitution;
        if (hostSex != null) sample.hostSex = hostSex;
        if (influenzaTestMethod != null) sample.influenzaTestMethod = influenzaTestMethod;
        if (influenzaTestResult != null) sample.influenzaTestResult = influenzaTestResult;
        if (otherPathogensTested != null) sample.otherPathogensTested = otherPathogensTested;
        if (otherPathogensTestResult != null) sample.otherPathogensTestResult = otherPathogensTestResult;
        if (hostHabitat != null) sample.hostHabitat = hostHabitat;
        if (isolationSourceHostAssociated != null) sample.isolationSourceHostAssociated = isolationSourceHostAssociated;
        if (hostBehaviour != null) sample.hostBehaviour = hostBehaviour;
        if (isolationSourceNonHostAssociated != null) sample.isolationSourceNonHostAssociated = isolationSourceNonHostAssociated;
        if (influenzaVirusType != null) sample.influenzaVirusType = influenzaVirusType;
        if (influenzaSubType != null) sample.influenzaSubType = influenzaSubType;
        if (serovar != null) sample.serovar = serovar;
        if (strain != null) sample.strain = strain;
        if (hostAge != null) sample.hostAge = hostAge;
        if (county != null) sample.county = county;
        if (commune != null) sample.commune = commune;
        if (hospitalHealthInstitution != null) sample.hospitalHealthInstitution = hospitalHealthInstitution;

        sample.modifiedOn = Instant.now();

        sample.project = project;
    }

    @Transactional
    public void deleteSample(UUID sampleId) {
        Sample sample = Sample.findSampleById(sampleId).orElseThrow(NotFoundException::new);
        sample.delete();
    }

    @Transactional
    public List<String> bulkPatchSamples(Long projectId, BulkPatchSampleRequest request) {
        List<String> errors = new ArrayList<>();

        for (var data : request.sampleData()) {

            Optional<Sample> sampleOptional = Sample.findBySampleNameInProject(data.name(), projectId);
            if (sampleOptional.isEmpty()) {
                errors.add("Sample with name " + data.name() + " does not exist");
                continue;
            }

            Sample sample = sampleOptional.get();

            if (data.alias() != null) sample.alias = data.alias();
            if (data.taxId() != null) sample.taxId = data.taxId();
            if (data.hostTaxId() != null) sample.hostTaxId = data.hostTaxId();
            if (data.mlst() != null) sample.mlst = data.mlst();
            if (data.location() != null) sample.location = data.location();
            if (data.sequencingLab() != null) sample.sequencingLab = data.sequencingLab();
            if (data.institution() != null) sample.institution = data.institution();
            if (data.isolationSource() != null) sample.isolationSource = data.isolationSource();
            if (data.collectionDate() != null) sample.collectionDate = data.collectionDate();
            if (data.hostHealthState() != null) sample.hostHealthState = data.hostHealthState();
            if (data.projectTitle() != null) sample.projectTitle = data.projectTitle();
            if (data.description() != null) sample.description = data.description();
            if (data.isolate() != null) sample.isolate = data.isolate();
            if (data.collectedBy() != null) sample.collectedBy = data.collectedBy();
            if (data.latitude() != null) sample.latitude = data.latitude();
            if (data.longitude() != null) sample.longitude = data.longitude();
            if (data.environmentalSample() != null) sample.environmentalSample = data.environmentalSample();
            if (data.hostAssociated() != null) sample.hostAssociated = data.hostAssociated();
            if (data.hostCommonName() != null) sample.hostCommonName = data.hostCommonName();
            if (data.hostSubjectId() != null) sample.hostSubjectId = data.hostSubjectId();
            if (data.collectorName() != null) sample.collectorName = data.collectorName();
            if (data.collectingInstitution() != null) sample.collectingInstitution = data.collectingInstitution();
            if (data.hostSex() != null) sample.hostSex = data.hostSex();
            if (data.influenzaTestMethod() != null) sample.influenzaTestMethod = data.influenzaTestMethod();
            if (data.influenzaTestResult() != null) sample.influenzaTestResult = data.influenzaTestResult();
            if (data.otherPathogensTested() != null) sample.otherPathogensTested = data.otherPathogensTested();
            if (data.otherPathogensTestResult() != null) sample.otherPathogensTestResult = data.otherPathogensTestResult();
            if (data.hostHabitat() != null) sample.hostHabitat = data.hostHabitat();
            if (data.isolationSourceHostAssociated() != null) sample.isolationSourceHostAssociated = data.isolationSourceHostAssociated();
            if (data.hostBehaviour() != null) sample.hostBehaviour = data.hostBehaviour();
            if (data.isolationSourceNonHostAssociated() != null) sample.isolationSourceNonHostAssociated = data.isolationSourceNonHostAssociated();
            if (data.influenzaVirusType() != null) sample.influenzaVirusType = data.influenzaVirusType();
            if (data.influenzaSubType() != null) sample.influenzaSubType = data.influenzaSubType();
            if (data.serovar() != null) sample.serovar = data.serovar();
            if (data.strain() != null) sample.strain = data.strain();
            if (data.hostAge() != null) sample.hostAge = data.hostAge();
            if (data.county() != null) sample.county = data.county();
            if (data.commune() != null) sample.commune = data.commune();
            if (data.hospitalHealthInstitution() != null) sample.hospitalHealthInstitution = data.hospitalHealthInstitution();
            sample.modifiedOn = Instant.now();
        }

        if (errors.isEmpty()) {
            return null;
        }

        return errors;
    }

}
