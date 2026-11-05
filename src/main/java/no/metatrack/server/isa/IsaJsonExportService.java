package no.metatrack.server.isa;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import no.metatrack.server.assay.Assay;
import no.metatrack.server.file.File;
import no.metatrack.server.file.FileResponse;
import no.metatrack.server.project.Project;
import no.metatrack.server.sample.Sample;
import no.metatrack.server.sample.SampleResponse;
import no.metatrack.server.sample.metadata.SampleMetadataFieldService;
import no.metatrack.server.sample.metadata.SampleMetadataService;
import no.metatrack.server.taxon.TaxonResponse;
import no.metatrack.server.taxon.TaxonomyService;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@ApplicationScoped
public class IsaJsonExportService {
    @Inject SampleMetadataService metadata;
    @Inject SampleMetadataFieldService fields;
    @Inject IsaJsonExportWriter writer;
    @Inject TaxonomyService taxonomy;

    @ConfigProperty(name = "metatrack.isa.instance-id", defaultValue = "local")
    String instanceId;

    /** {@code sampleNames == null} exports the whole visible project, otherwise only the named samples. */
    @Transactional
    public ObjectNode export(Long projectId, Set<String> sampleNames) {
        Project project = Project.<Project>findByIdOptional(projectId).orElseThrow(NotFoundException::new);
        List<Sample> samples = Sample.getAllSamplesInProject(projectId).stream()
                .filter(sample -> sampleNames == null || sampleNames.contains(sample.name))
                .sorted(Comparator.comparing(sample -> sample.id)).toList();
        if (sampleNames != null && samples.size() != sampleNames.size()) {
            throw new NotFoundException("Selected sample not found");
        }
        Set<UUID> selected = samples.stream().map(sample -> sample.id).collect(Collectors.toSet());
        var values = metadata.getActiveMetadata(samples);
        List<SampleResponse> sampleResponses = samples.stream()
                .map(sample -> SampleResponse.fromEntity(sample, values.get(sample.id))).toList();

        // An assay can be shared with a sub-project. Scope its samples and files separately.
        List<AssayData> assays = Assay.findAssaysInProject(projectId).stream()
                .sorted(Comparator.comparing(assay -> assay.id))
                .map(assay -> new AssayData(assay,
                        Sample.findSamplesInAssayInProjectScope(projectId, assay.id).stream()
                                .map(sample -> sample.id).filter(selected::contains).sorted().toList(),
                        File.findInAssayInProjectScope(projectId, assay).stream()
                                .map(FileResponse::fromEntity)
                                .filter(file -> file.sampleId() == null || selected.contains(file.sampleId()))
                                .sorted(Comparator.comparing(FileResponse::uuid)).toList()))
                // A selection keeps only the experiments of the selected samples.
                .filter(data -> sampleNames == null || !data.sampleIds().isEmpty())
                .toList();
        List<String> problems = IsaJsonExportWriter.problems(assays);
        if (!problems.isEmpty()) {
            throw new WebApplicationException(Response.status(422).type(MediaType.APPLICATION_JSON)
                    .entity(Map.of("message", "The project cannot be exported as ISA-JSON without guessing",
                            "problems", problems))
                    .build());
        }
        return writer.write(project, sampleResponses, fields.list(projectId, false), assays,
                taxonNames(sampleResponses), instanceId);
    }

    /** Scientific names from TaxHub; an unavailable service must not block the export. */
    private Map<Integer, String> taxonNames(List<SampleResponse> samples) {
        Map<Integer, String> names = new HashMap<>();
        samples.stream().flatMap(sample -> Stream.of(sample.taxId(), sample.hostTaxId()))
                .filter(Objects::nonNull).distinct().forEach(taxId -> {
                    try {
                        TaxonResponse taxon = taxonomy.getTaxon(taxId.toString());
                        if (taxon != null && taxon.name() != null) names.put(taxId, taxon.name());
                    } catch (RuntimeException e) {
                        Log.warnf("Taxon %d could not be resolved for ISA-JSON export: %s", taxId, e.getMessage());
                    }
                });
        return names;
    }

    public record AssayData(Assay assay, List<UUID> sampleIds, List<FileResponse> files) {}
}
