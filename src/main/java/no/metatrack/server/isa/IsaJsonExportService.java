package no.metatrack.server.isa;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import no.metatrack.server.assay.Assay;
import no.metatrack.server.file.File;
import no.metatrack.server.file.FileResponse;
import no.metatrack.server.project.Project;
import no.metatrack.server.sample.Sample;
import no.metatrack.server.sample.SampleResponse;
import no.metatrack.server.sample.metadata.SampleMetadataFieldService;
import no.metatrack.server.sample.metadata.SampleMetadataService;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class IsaJsonExportService {
    @Inject SampleMetadataService metadata;
    @Inject SampleMetadataFieldService fields;
    @Inject IsaJsonExportWriter writer;

    @Transactional
    public ObjectNode export(Long projectId) {
        Project project = Project.<Project>findByIdOptional(projectId).orElseThrow(NotFoundException::new);
        List<Sample> samples = Sample.getAllSamplesInProject(projectId).stream()
                .sorted(Comparator.comparing(sample -> sample.id)).toList();
        var values = metadata.getActiveMetadata(samples);
        List<SampleResponse> sampleResponses = samples.stream()
                .map(sample -> SampleResponse.fromEntity(sample, values.get(sample.id))).toList();

        // An assay can be shared with a sub-project. Scope its samples and files separately.
        List<AssayData> assays = Assay.findAssaysInProject(projectId).stream()
                .sorted(Comparator.comparing(assay -> assay.id))
                .map(assay -> new AssayData(assay,
                        Sample.findSamplesInAssayInProjectScope(projectId, assay.id).stream()
                                .map(sample -> sample.id).sorted().toList(),
                        File.findInAssayInProjectScope(projectId, assay).stream()
                                .map(FileResponse::fromEntity)
                                .sorted(Comparator.comparing(FileResponse::uuid)).toList()))
                .toList();
        return writer.write(project, sampleResponses, fields.list(projectId, false), assays);
    }

    public record AssayData(Assay assay, List<UUID> sampleIds, List<FileResponse> files) {}
}
