package no.metatrack.server.sample;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.Response;
import no.metatrack.server.project.Project;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class SampleServices {
    private final Request request;

    @Inject
    public SampleServices(Request request) {
        this.request = request;
    }

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
            LocalDate collectionDate) {

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
            LocalDate collectionDate) {

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
        }

        if (errors.isEmpty()) {
            return null;
        }

        return errors;
    }
}
