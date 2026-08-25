package no.metatrack.server.assay;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import no.metatrack.server.project.Project;
import no.metatrack.server.sample.Sample;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class AssayService {

    public Assay getAssayById(UUID assayId) {
        return (Assay) Assay.findByIdOptional(assayId).orElseThrow(NotFoundException::new);
    }

    public List<Assay> getAllAssaysInProject(Long projectId) {
        return Assay.findAssaysInProject(projectId);
    }

    @Transactional
    public Assay createAssay(
            Long projectId,
            String name,
            String studyAccession,
            String instrumentModel,
            String libraryName,
            String librarySource,
            String libraryStrategy,
            String librarySelection,
            String libraryLayout,
            Integer insertSize) {
        Project targetProject = (Project) Project.findByIdOptional(projectId).orElseThrow(NotFoundException::new);
        // Same rule as samples: an assay created from within a sub-project belongs to the
        // root project. It becomes visible in the sub-project once a linked sample is attached.
        Project owningProject = targetProject.isSubProject() ? targetProject.parentProject : targetProject;

        Assay assay = new Assay();
        assay.name = name;
        assay.studyAccession = studyAccession;
        assay.instrumentModel = instrumentModel;
        assay.libraryName = libraryName;
        assay.librarySource = librarySource;
        assay.librarySelection = librarySelection;
        assay.libraryStrategy = libraryStrategy;
        assay.libraryLayout = libraryLayout;
        assay.insertSize = insertSize;
        assay.project = owningProject;
        assay.createdOn = Instant.now();
        assay.modifiedOn = Instant.now();

        owningProject.assays.add(assay);
        return assay;
    }

    @Transactional
    public void updateAssay(
            UUID assayId,
            String name,
            String studyAccession,
            String instrumentModel,
            String libraryName,
            String librarySource,
            String librarySelection,
            String libraryStrategy,
            String libraryLayout,
            Integer insertSize) {
        Assay assay = getAssayById(assayId);
        if (name != null) assay.name = name;
        if (studyAccession != null) assay.studyAccession = studyAccession;
        if (instrumentModel != null) assay.instrumentModel = instrumentModel;
        if (libraryName != null) assay.libraryName = libraryName;
        if (librarySource != null) assay.librarySource = librarySource;
        if (libraryStrategy != null) assay.libraryStrategy = libraryStrategy;
        if (librarySelection != null) assay.librarySelection = librarySelection;
        if (libraryLayout != null) assay.libraryLayout = libraryLayout;
        if (insertSize != null) assay.insertSize = insertSize;
        assay.modifiedOn = Instant.now();
    }

    @Transactional
    public void deleteAssay(UUID assayId) {
        Assay assay = getAssayById(assayId);
        assay.delete();
    }

    @Transactional
    public List<String> addSamplesToAssay(Long projectId, List<String> sampleNames, UUID assayId) {
        Assay assay = getAssayById(assayId);
        List<String> errors = new ArrayList<>();

        for (String sampleName : sampleNames) {
            Optional<Sample> sample = Sample.findBySampleNameInProject(sampleName, projectId);
            if (sample.isEmpty()) {
                errors.add("Sample with name " + sampleName + " does not exist in project " + projectId);
                continue;
            }
            assay.addSample(sample.get());
        }

        if (errors.isEmpty()) return List.of();

        return errors;
    }

    @Transactional
    public List<String> removeSamplesFromAssay(Long projectId, List<String> sampleNames, UUID assayId) {
        List<String> errors = new ArrayList<>();
        Assay assay = getAssayById(assayId);

        for (String sampleName : sampleNames) {
            Optional<Sample> sample = Sample.findBySampleNameInProject(sampleName, projectId);
            if (sample.isEmpty()) {
                errors.add("Sample with name " + sampleName + " does not exist in project " + projectId);
                continue;
            }
            assay.removeSample(sample.get());
        }

        if (errors.isEmpty()) return List.of();

        return errors;
    }

    public List<Sample> getAllSamplesInAssay(UUID assayId) {
        getAssayById(assayId);
        return Sample.findSamplesInAssay(assayId);
    }

}
