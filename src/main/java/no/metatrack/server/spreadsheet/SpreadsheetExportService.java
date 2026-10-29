package no.metatrack.server.spreadsheet;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import no.metatrack.server.assay.Assay;
import no.metatrack.server.file.File;
import no.metatrack.server.file.FileResponse;
import no.metatrack.server.sample.Sample;
import no.metatrack.server.sample.metadata.SampleMetadataFieldService;
import no.metatrack.server.sample.metadata.SampleMetadataService;
import no.metatrack.server.sample.vocabulary.SampleVocabularyManagementService;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@ApplicationScoped
public class SpreadsheetExportService {
    @Inject SampleMetadataFieldService fields;
    @Inject SampleMetadataService metadata;
    @Inject SampleVocabularyManagementService vocabularies;
    @Inject SpreadsheetExportWriter writer;

    @Transactional
    public byte[] samples(Long projectId, List<String> names) {
        List<Sample> samples = ordered(names, Sample.list("project.id = ?1 and name in ?2", projectId, names));
        return writer.samples(samples, fields.list(projectId, false), metadata.getActiveMetadata(samples),
                vocabularies.list(projectId));
    }

    @Transactional
    public byte[] experiments(Long projectId, UUID assayId, List<String> names) {
        Assay assay = Assay.<Assay>find("id = ?1 and project.id = ?2", assayId, projectId)
                .firstResultOptional().orElseThrow(() -> new NotFoundException("Assay not found"));
        List<Sample> samples = ordered(names, Sample.list(
                "select s from Sample s join s.assays a where s.project.id = ?1 and a.id = ?2 and s.name in ?3",
                projectId, assayId, names));
        List<File> files = File.list(
                "sample.project.id = ?1 and assay.project.id = ?1 and assay.id = ?2 and sample.name in ?3 order by fileName",
                projectId, assayId, names);
        return writer.experiments(assay, samples, files.stream().map(FileResponse::fromEntity).toList());
    }

    private List<Sample> ordered(List<String> names, List<Sample> samples) {
        var byName = samples.stream().collect(Collectors.toMap(sample -> sample.name, Function.identity()));
        if (names.stream().anyMatch(name -> !byName.containsKey(name))) {
            // Do not silently export a subset or disclose samples outside the requested project/assay.
            throw new NotFoundException("One or more selected samples are no longer available in this project or assay");
        }
        return names.stream().map(byName::get).toList();
    }
}
