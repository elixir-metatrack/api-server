package no.metatrack.server.isa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import no.metatrack.server.assay.Assay;
import no.metatrack.server.file.FileResponse;
import no.metatrack.server.project.Project;
import no.metatrack.server.sample.SampleResponse;
import no.metatrack.server.sample.metadata.SampleMetadataField;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class IsaJsonExportWriter {
    private static final Set<String> SAMPLE_INTERNAL_FIELDS = Set.of(
            "id", "name", "createdOn", "modifiedOn", "customMetadata");

    @Inject ObjectMapper mapper;

    public ObjectNode write(Project project, List<SampleResponse> samples,
                            List<SampleMetadataField> fields, List<IsaJsonExportService.AssayData> assays) {
        ObjectNode investigation = node(id("investigation", project.id));
        investigation.put("identifier", "metatrack-project-" + project.id);
        investigation.put("filename", "i_metatrack_" + project.id + ".json");
        investigation.put("title", project.name);
        put(investigation, "description", project.description);
        comment(investigation, "metatrack:exportProfile", "exploration-v1");
        comment(investigation, "metatrack:limitations",
                "Metadata export only; not validated for repository submission. "
                + "Source materials, experimental protocols, library preparation and ontology mappings "
                + "are not recorded in Metatrack. File contents and repository accessions are not generated.");

        // Sub-projects are access-controlled sample subsets, not independently recorded ISA studies.
        ObjectNode study = node(id("study", project.id));
        study.put("identifier", "metatrack-project-" + project.id);
        study.put("title", project.name);
        put(study, "description", project.description);
        investigation.putArray("studies").add(study);
        ArrayNode categories = study.putArray("characteristicCategories");
        Map<String, String> categoryIds = new LinkedHashMap<>();
        for (SampleMetadataField field : fields) {
            String categoryId = id("custom-field", field.id);
            ObjectNode category = node(categoryId);
            ObjectNode annotation = category.putObject("characteristicType").put("annotationValue", field.label);
            // ISA values do not carry our BOOLEAN/DATE types. Keep that information for a future importer.
            comment(annotation, "metatrack:fieldKey", field.key);
            comment(annotation, "metatrack:fieldType", field.type.name());
            comment(annotation, "metatrack:tooltip", field.tooltip);
            categories.add(category);
            categoryIds.put("custom:" + field.key, categoryId);
        }

        ArrayNode isaSamples = study.putObject("materials").putArray("samples");
        for (SampleResponse sample : samples) {
            ObjectNode isaSample = node(id("sample", sample.id())).put("name", sample.name());
            ArrayNode characteristics = isaSample.putArray("characteristics");
            ObjectNode data = mapper.valueToTree(sample);
            data.properties().forEach(entry -> {
                String key = entry.getKey();
                if (SAMPLE_INTERNAL_FIELDS.contains(key) || entry.getValue().isNull()) return;
                String categoryId = categoryIds.computeIfAbsent(key, unused -> {
                    String newId = id("sample-field", key);
                    ObjectNode category = node(newId);
                    category.putObject("characteristicType").put("annotationValue", snakeCase(key));
                    categories.add(category);
                    return newId;
                });
                characteristic(characteristics, categoryId, entry.getValue());
            });
            if (sample.customMetadata() != null) {
                sample.customMetadata().forEach((key, value) -> {
                    String categoryId = categoryIds.get("custom:" + key);
                    if (categoryId != null && value != null) {
                        characteristic(characteristics, categoryId, mapper.valueToTree(value));
                    }
                });
            }
            isaSamples.add(isaSample);
        }

        ArrayNode protocols = study.putArray("protocols");
        ArrayNode isaAssays = study.putArray("assays");
        for (IsaJsonExportService.AssayData data : assays) {
            isaAssays.add(assay(data, protocols));
        }
        return investigation;
    }

    private ObjectNode assay(IsaJsonExportService.AssayData data, ArrayNode protocols) {
        Assay assay = data.assay();
        ObjectNode result = node(id("assay", assay.id));
        result.put("filename", "a_" + assay.id + ".txt");
        put(result, "technologyPlatform", assay.instrumentModel);
        comment(result, "metatrack:assayName", assay.name);
        comment(result, "metatrack:studyAccession", assay.studyAccession);

        ObjectNode protocol = node(id("protocol", assay.id));
        protocol.put("name", assay.name);
        protocol.put("description", "Recorded Metatrack assay metadata. The process represents the "
                + "sample/file association, not a reconstruction of the laboratory workflow.");
        ArrayNode parameters = protocol.putArray("parameters");
        Map<String, Object> values = assayParameters(assay);
        values.forEach((key, value) -> {
            if (value == null) return;
            ObjectNode parameter = node(id("parameter", assay.id + ":" + key));
            parameter.putObject("parameterName").put("annotationValue", key);
            parameters.add(parameter);
        });
        protocols.add(protocol);

        ArrayNode samples = result.putObject("materials").putArray("samples");
        data.sampleIds().forEach(sampleId -> samples.add(node(id("sample", sampleId))));
        ArrayNode files = result.putArray("dataFiles");
        for (FileResponse file : data.files()) {
            ObjectNode exportedFile = node(id("file", file.uuid())).put("name", file.name());
            String lower = file.name().toLowerCase(Locale.ROOT);
            if (lower.endsWith(".fastq") || lower.endsWith(".fq")
                    || lower.endsWith(".fastq.gz") || lower.endsWith(".fq.gz")) {
                exportedFile.put("type", "Raw Data File");
                comment(exportedFile, "file type", "fastq");
            }
            comment(exportedFile, "file checksum", file.md5());
            comment(exportedFile, "metatrack:unencryptedMd5", file.unencryptedMd5());
            comment(exportedFile, "metatrack:readRole", file.readRole());
            comment(exportedFile, "metatrack:uploadStatus", file.status());
            // Storage keys and signed URLs are internal implementation details, not portable file locations.
            files.add(exportedFile);
        }

        ArrayNode processes = result.putArray("processSequence");
        for (UUID sampleId : data.sampleIds()) {
            ObjectNode process = node(id("process", assay.id + ":" + sampleId));
            process.put("name", assay.name);
            process.set("executesProtocol", node(id("protocol", assay.id)));
            process.putArray("inputs").add(node(id("sample", sampleId)));
            ArrayNode outputs = process.putArray("outputs");
            data.files().stream().filter(file -> sampleId.equals(file.sampleId()))
                    .forEach(file -> outputs.add(node(id("file", file.uuid()))));
            ArrayNode parameterValues = process.putArray("parameterValues");
            values.forEach((key, value) -> {
                if (value == null) return;
                ObjectNode parameterValue = mapper.createObjectNode();
                parameterValue.set("category", node(id("parameter", assay.id + ":" + key)));
                parameterValue.set("value", mapper.valueToTree(value));
                parameterValues.add(parameterValue);
            });
            processes.add(process);
        }
        return result;
    }

    private Map<String, Object> assayParameters(Assay assay) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("LIBRARY_NAME", assay.libraryName);
        values.put("LIBRARY_SOURCE", assay.librarySource);
        values.put("LIBRARY_SELECTION", assay.librarySelection);
        values.put("LIBRARY_STRATEGY", assay.libraryStrategy);
        values.put("LIBRARY_LAYOUT", assay.libraryLayout);
        values.put("NOMINAL_LENGTH", assay.insertSize);
        values.put("INSTRUMENT_MODEL", assay.instrumentModel);
        values.put("PLATFORM", assay.sequencingPlatform);
        values.put("SEQUENCING_LABORATORY", assay.sequencingLaboratory);
        return values;
    }

    private void characteristic(ArrayNode target, String categoryId, JsonNode value) {
        ObjectNode characteristic = mapper.createObjectNode();
        characteristic.set("category", node(categoryId));
        // ISA permits numbers, strings and ontology annotations, but no JSON booleans.
        characteristic.set("value", value.isBoolean() ? mapper.getNodeFactory().textNode(value.asText()) : value);
        target.add(characteristic);
    }

    private ObjectNode node(String id) {
        return mapper.createObjectNode().put("@id", id);
    }

    private static String id(String kind, Object value) {
        return "urn:metatrack:" + kind + ":" + value;
    }

    private void comment(ObjectNode target, String name, Object value) {
        if (value != null) target.withArray("comments").addObject().put("name", name).put("value", value.toString());
    }

    private void put(ObjectNode target, String name, String value) {
        if (value != null) target.put(name, value);
    }

    private static String snakeCase(String name) {
        return name.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }
}
