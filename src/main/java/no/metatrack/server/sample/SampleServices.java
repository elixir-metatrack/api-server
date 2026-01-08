package no.metatrack.server.sample;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import no.metatrack.server.project.Project;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class SampleServices {
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
            int taxId,
            int hostTaxId,
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
        sample.taxId = taxId;

        if (alias != null) sample.alias = alias;
        if (hostTaxId != 0) sample.hostTaxId = hostTaxId;
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
            int taxId,
            int hostTaxId,
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
        if (sample.taxId != 0) sample.taxId = taxId;
        if (hostTaxId != 0) sample.hostTaxId = hostTaxId;
        if (mlst != null) sample.mlst = mlst;
        if (location != null) sample.location = location;
        if (sequencingLab != null) sample.sequencingLab = sequencingLab;
        if (institution != null) sample.institution = institution;
        if (isolationSource != null) sample.isolationSource = isolationSource;
        if (collectionDate != null) sample.collectionDate = collectionDate;

        sample.modifiedOn = Instant.now();

        sample.project = project;
    }

    public void deleteSample(UUID sampleId) {
        Sample sample = Sample.findSampleById(sampleId).orElseThrow(NotFoundException::new);
        sample.delete();
    }
}
