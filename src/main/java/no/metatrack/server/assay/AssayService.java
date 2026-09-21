package no.metatrack.server.assay;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import no.metatrack.server.assay.vocabulary.AssayValidationViolation;
import no.metatrack.server.assay.vocabulary.AssayVocabularyService;
import no.metatrack.server.assay.vocabulary.AssayVocabularyValidationException;
import no.metatrack.server.project.Project;
import no.metatrack.server.sample.Sample;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class AssayService {

    @Inject
    AssayVocabularyService vocabularyService;

    public Assay getAssayById(Long projectId, UUID assayId) {
        return Assay.findByIdInProjectScope(projectId, assayId).orElseThrow(NotFoundException::new);
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
        if (targetProject.isSubProject()) {
            throw new ForbiddenException("Create experiments in the parent project");
        }
        Project owningProject = targetProject;

        validate(name, studyAccession, instrumentModel, libraryName, librarySource,
                librarySelection, libraryStrategy, libraryLayout);
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
            Long projectId,
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
        Assay assay = getAssayById(projectId, assayId);
        validate(name != null ? name : assay.name, studyAccession, instrumentModel, libraryName, librarySource,
                librarySelection, libraryStrategy, libraryLayout);
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

    private void validate(
            String name, String studyAccession, String instrumentModel, String libraryName,
            String librarySource, String librarySelection, String libraryStrategy, String libraryLayout) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("study_accession", studyAccession);
        values.put("instrument_model", instrumentModel);
        values.put("library_name", libraryName);
        values.put("library_source", librarySource);
        values.put("library_selection", librarySelection);
        values.put("library_strategy", libraryStrategy);
        values.put("library_layout", libraryLayout);
        List<AssayValidationViolation> violations = vocabularyService.validate(name, values);
        if (!violations.isEmpty()) throw new AssayVocabularyValidationException(violations);
    }

    @Transactional
    public void deleteAssay(Long projectId, UUID assayId) {
        Assay assay = getAssayById(projectId, assayId);
        if (!assay.project.id.equals(projectId)) {
            throw new ForbiddenException("Delete shared experiments from the parent project");
        }
        assay.delete();
    }

    @Transactional
    public List<String> addSamplesToAssay(Long projectId, List<String> sampleNames, UUID assayId) {
        Assay assay = getAssayById(projectId, assayId);
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
        Assay assay = getAssayById(projectId, assayId);

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

    public List<Sample> getAllSamplesInAssay(Long projectId, UUID assayId) {
        getAssayById(projectId, assayId);
        return Sample.findSamplesInAssayInProjectScope(projectId, assayId);
    }

    public List<Assay> getAllAssaysInSample(Long projectId, UUID sampleId) {
        Sample sample = Sample.findByIdInProjectScope(sampleId, projectId).orElseThrow(NotFoundException::new);
        return Sample.getAllAssaysInSample(sample.project.id, sampleId);
    }
}
