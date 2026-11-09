package no.metatrack.server.isa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import no.metatrack.server.assay.Assay;
import no.metatrack.server.file.FileResponse;
import no.metatrack.server.file.ReadRole;
import no.metatrack.server.project.Project;
import no.metatrack.server.sample.SampleResponse;
import no.metatrack.server.sample.metadata.SampleMetadataField;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Writes the ISA-JSON profile described in docs/isa-json/README.md. */
@ApplicationScoped
public class IsaJsonExportWriter {
    public static final String PROFILE = "isa-v2";
    static final String NCBI_TAXON_IRI = "http://purl.obolibrary.org/obo/NCBITaxon_";
    static final List<String> LIBRARY_PARAMETERS = List.of("LIBRARY_NAME", "LIBRARY_CONSTRUCTION_PROTOCOL",
            "DESIGN_DESCRIPTION", "LIBRARY_SOURCE", "LIBRARY_SELECTION", "LIBRARY_STRATEGY", "LIBRARY_LAYOUT",
            "NOMINAL_LENGTH");
    static final List<String> SEQUENCING_PARAMETERS = List.of("PLATFORM", "INSTRUMENT_MODEL");

    @Inject ObjectMapper mapper;

    /**
     * @param taxonNames scientific names by NCBI tax ID; missing IDs are exported with the numeric ID as label
     * @param instanceId persistent identifier of this Metatrack installation, part of every {@code @id}
     */
    public ObjectNode write(Project project, List<SampleResponse> samples, List<SampleMetadataField> fields,
                            List<IsaJsonExportService.AssayData> assays, Map<Integer, String> taxonNames,
                            String instanceId) {
        Ids ids = new Ids(instanceId);
        String identifier = "metatrack:" + instanceId + ":project:" + project.id;
        ObjectNode investigation = node(ids.of("investigation", project.id));
        investigation.put("identifier", identifier);
        investigation.put("filename", "i_metatrack_" + project.id + ".json");
        investigation.put("title", project.name);
        put(investigation, "description", project.description);
        comment(investigation, "metatrack:exportProfile", PROFILE);
        investigation.putArray("ontologySourceReferences").addObject()
                .put("name", "NCBITaxon")
                .put("file", "http://purl.obolibrary.org/obo/ncbitaxon.owl")
                .put("description", "NCBI organismal classification");

        // Sub-projects are access-controlled sample subsets, not independently recorded ISA studies.
        ObjectNode study = node(ids.of("study", project.id));
        study.put("identifier", identifier);
        study.put("filename", "s_metatrack_" + project.id + ".txt");
        study.put("title", project.name);
        put(study, "description", project.description);
        investigation.putArray("studies").add(study);

        Categories categories = new Categories(ids, study.putArray("characteristicCategories"));
        Map<String, String> customCategories = new LinkedHashMap<>();
        for (SampleMetadataField field : fields) {
            ObjectNode category = categories.add(ids.of("custom-field", field.id), field.label);
            ObjectNode annotation = (ObjectNode) category.get("characteristicType");
            // ISA values do not carry our field types. Keep them so a Metatrack import restores the field.
            comment(annotation, "metatrack:fieldKey", field.key);
            comment(annotation, "metatrack:fieldType", field.type.name());
            comment(annotation, "metatrack:tooltip", field.tooltip);
            customCategories.put(field.key, ids.of("custom-field", field.id));
        }

        ArrayNode protocols = study.putArray("protocols");
        ObjectNode collection = protocols.addObject().put("@id", ids.of("protocol", "sample-collection"))
                .put("name", "sample collection");
        collection.set("protocolType", term("sample collection"));

        ObjectNode materials = study.putObject("materials");
        ArrayNode sources = materials.putArray("sources");
        ArrayNode isaSamples = materials.putArray("samples");
        ArrayNode studyProcesses = study.putArray("processSequence");
        for (SampleResponse sample : samples) {
            ArrayNode organism = mapper.createArrayNode();
            if (sample.taxId() != null) {
                organism.add(characteristic(categories.builtIn(IsaSampleFields.ORGANISM),
                        taxonTerm(sample.taxId(), taxonNames)));
                organism.add(characteristic(categories.builtIn("tax_id"), term(sample.taxId().toString())));
            }
            ObjectNode source = sources.addObject().put("@id", ids.of("source", sample.id())).put("name", sample.name());
            source.set("characteristics", organism.deepCopy());

            ObjectNode isaSample = isaSamples.addObject().put("@id", ids.of("sample", sample.id()))
                    .put("name", sample.name());
            isaSample.putArray("derivesFrom").add(ref(ids.of("source", sample.id())));
            ArrayNode characteristics = isaSample.putArray("characteristics");
            characteristics.addAll(organism);
            if (sample.hostTaxId() != null) {
                characteristics.add(characteristic(categories.builtIn(IsaSampleFields.HOST_SCIENTIFIC_NAME),
                        taxonTerm(sample.hostTaxId(), taxonNames)));
            }
            for (IsaSampleFields.Field field : IsaSampleFields.FIELDS) {
                Object value = field.getter().apply(sample);
                // tax_id is already written next to organism above.
                if (!field.exported() || field.property().equals("taxId")
                        || value == null || value.toString().isBlank()) continue;
                characteristics.add(characteristic(categories.builtIn(field.isaName()), term(value.toString())));
            }
            if (sample.customMetadata() != null) {
                sample.customMetadata().forEach((key, value) -> {
                    String categoryId = customCategories.get(key);
                    if (categoryId != null && value != null) {
                        characteristics.add(characteristic(categoryId, term(text(value))));
                    }
                });
            }

            ObjectNode process = studyProcesses.addObject().put("@id", ids.of("process", "sample-collection:" + sample.id()));
            process.set("executesProtocol", ref(ids.of("protocol", "sample-collection")));
            process.putArray("inputs").add(ref(ids.of("source", sample.id())));
            process.putArray("outputs").add(ref(ids.of("sample", sample.id())));
        }

        ArrayNode isaAssays = study.putArray("assays");
        Map<UUID, String> sampleNames = samples.stream()
                .collect(Collectors.toMap(SampleResponse::id, SampleResponse::name, (a, b) -> a));
        for (IsaJsonExportService.AssayData data : assays) {
            isaAssays.add(assay(ids, data, protocols, sampleNames));
        }
        return investigation;
    }

    /** Export rule E8: cases the v2 profile refuses to guess. Empty if the assays can be exported. */
    public static List<String> problems(List<IsaJsonExportService.AssayData> assays) {
        List<String> problems = new ArrayList<>();
        for (IsaJsonExportService.AssayData data : assays) {
            Assay assay = data.assay();
            Set<UUID> members = Set.copyOf(data.sampleIds());
            Map<UUID, List<FileResponse>> filesBySample = new LinkedHashMap<>();
            for (FileResponse file : data.files()) {
                if (file.sampleId() == null || !members.contains(file.sampleId())) {
                    problems.add("Experiment '" + assay.name + "': file '" + file.name()
                            + "' belongs to a sample that is not part of the experiment");
                    continue;
                }
                filesBySample.computeIfAbsent(file.sampleId(), unused -> new ArrayList<>()).add(file);
            }
            filesBySample.forEach((sampleId, files) -> {
                List<ReadRole> roles = files.stream().map(FileResponse::readRole).toList();
                boolean single = roles.equals(List.of(ReadRole.SINGLE)) || (roles.size() == 1 && roles.get(0) == null);
                boolean paired = roles.size() == 2 && roles.contains(ReadRole.FORWARD) && roles.contains(ReadRole.REVERSE);
                String names = files.stream().map(FileResponse::name).sorted().collect(Collectors.joining(", "));
                if (!single && !paired) {
                    problems.add("Experiment '" + assay.name + "': files " + names
                            + " of one sample are not a single run (one SINGLE file or one FORWARD/REVERSE pair)");
                } else if ("PAIRED".equalsIgnoreCase(assay.libraryLayout) && !paired) {
                    problems.add("Experiment '" + assay.name + "': layout PAIRED but only " + names);
                } else if ("SINGLE".equalsIgnoreCase(assay.libraryLayout) && paired) {
                    problems.add("Experiment '" + assay.name + "': layout SINGLE but a read pair " + names);
                }
            });
        }
        return problems;
    }

    private ObjectNode assay(Ids ids, IsaJsonExportService.AssayData data, ArrayNode protocols,
                             Map<UUID, String> sampleNames) {
        Assay assay = data.assay();
        String libraryProtocol = ids.of("protocol", "library-construction:" + assay.id);
        String sequencingProtocol = ids.of("protocol", "nucleic-acid-sequencing:" + assay.id);
        ObjectNode library = protocols.addObject().put("@id", libraryProtocol)
                .put("name", assay.libraryName != null ? assay.libraryName : "library construction");
        library.set("protocolType", term("library construction"));
        ArrayNode libraryParameters = library.putArray("parameters");
        LIBRARY_PARAMETERS.forEach(name -> libraryParameters.add(parameter(ids, assay.id, name)));
        ObjectNode sequencing = protocols.addObject().put("@id", sequencingProtocol).put("name", "nucleic acid sequencing");
        sequencing.set("protocolType", term("nucleic acid sequencing"));
        ArrayNode sequencingParameters = sequencing.putArray("parameters");
        SEQUENCING_PARAMETERS.forEach(name -> sequencingParameters.add(parameter(ids, assay.id, name)));

        String strategy = assay.libraryStrategy == null ? "" : assay.libraryStrategy.trim().toUpperCase(Locale.ROOT);
        ObjectNode result = node(ids.of("assay", assay.id));
        result.put("filename", "a_" + assay.id + ".txt");
        String measurement = switch (strategy) {
            case "WGS", "WGA", "WCS" -> "genome sequencing";
            case "AMPLICON" -> "amplicon sequencing";
            case "RNA-SEQ" -> "transcription profiling";
            default -> null;
        };
        if (measurement != null) result.set("measurementType", term(measurement));
        result.set("technologyType", term("nucleotide sequencing"));
        String platform = Stream.of(assay.sequencingPlatform, assay.instrumentModel)
                .filter(Objects::nonNull).collect(Collectors.joining(" "));
        if (!platform.isBlank()) result.put("technologyPlatform", platform);
        comment(result, "metatrack:assayName", assay.name);
        comment(result, "metatrack:studyAccession", assay.studyAccession);
        comment(result, "metatrack:experimentAccession", assay.experimentAccession);
        comment(result, "metatrack:runAccession", assay.runAccession);
        comment(result, "STUDY_TYPE", switch (strategy) {
            case "WGS", "WGA", "WCS" -> "Whole Genome Sequencing";
            case "RNA-SEQ" -> "Transcriptome Analysis";
            default -> "Other";
        });

        ObjectNode materials = result.putObject("materials");
        ArrayNode samples = materials.putArray("samples");
        data.sampleIds().forEach(sampleId -> samples.add(ref(ids.of("sample", sampleId))));
        ArrayNode libraries = materials.putArray("otherMaterials");
        ArrayNode files = result.putArray("dataFiles");
        ArrayNode processes = result.putArray("processSequence");
        Map<UUID, List<FileResponse>> filesBySample = data.files().stream()
                .collect(Collectors.groupingBy(FileResponse::sampleId, LinkedHashMap::new, Collectors.toList()));
        for (UUID sampleId : data.sampleIds()) {
            List<FileResponse> sampleFiles = filesBySample.getOrDefault(sampleId, List.of());
            if (sampleFiles.isEmpty()) continue;
            String sampleName = sampleNames.getOrDefault(sampleId, sampleId.toString());
            String libraryId = ids.of("library", assay.id + ":" + sampleId);
            String libraryProcess = ids.of("process", "library-construction:" + assay.id + ":" + sampleId);
            String sequencingProcess = ids.of("process", "sequencing:" + assay.id + ":" + sampleId);

            ObjectNode isaLibrary = libraries.addObject().put("@id", libraryId)
                    .put("name", sampleName + " / " + assay.name).put("type", "Extract Name");
            isaLibrary.putArray("derivesFrom").add(ref(ids.of("sample", sampleId)));

            ObjectNode construction = processes.addObject().put("@id", libraryProcess);
            construction.set("executesProtocol", ref(libraryProtocol));
            construction.putArray("inputs").add(ref(ids.of("sample", sampleId)));
            construction.putArray("outputs").add(ref(libraryId));
            construction.set("nextProcess", ref(sequencingProcess));
            ArrayNode libraryValues = construction.putArray("parameterValues");
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("LIBRARY_NAME", sampleName);
            values.put("LIBRARY_CONSTRUCTION_PROTOCOL", assay.libraryName);
            values.put("DESIGN_DESCRIPTION", designDescription(assay));
            values.put("LIBRARY_SOURCE", assay.librarySource);
            values.put("LIBRARY_SELECTION", assay.librarySelection);
            values.put("LIBRARY_STRATEGY", assay.libraryStrategy);
            values.put("LIBRARY_LAYOUT", assay.libraryLayout);
            values.put("NOMINAL_LENGTH", assay.insertSize);
            values.forEach((name, value) -> parameterValue(libraryValues, ids, assay.id, name, value));
            comment(construction, "metatrack:generated", "DESIGN_DESCRIPTION");

            ObjectNode run = processes.addObject().put("@id", sequencingProcess);
            run.set("executesProtocol", ref(sequencingProtocol));
            put(run, "performer", assay.sequencingLaboratory);
            run.putArray("inputs").add(ref(libraryId));
            ArrayNode outputs = run.putArray("outputs");
            run.set("previousProcess", ref(libraryProcess));
            ArrayNode runValues = run.putArray("parameterValues");
            parameterValue(runValues, ids, assay.id, "PLATFORM", assay.sequencingPlatform);
            parameterValue(runValues, ids, assay.id, "INSTRUMENT_MODEL", assay.instrumentModel);

            for (FileResponse file : sampleFiles) {
                outputs.add(ref(ids.of("file", file.uuid())));
                files.add(dataFile(ids, file));
            }
        }
        return result;
    }

    private ObjectNode dataFile(Ids ids, FileResponse file) {
        ObjectNode result = node(ids.of("file", file.uuid())).put("name", file.name());
        String type = fileType(file.name());
        if (type != null) {
            result.put("type", "Raw Data File");
            comment(result, "file type", type);
        }
        comment(result, "file checksum", file.md5());
        if (file.md5() != null) comment(result, "checksum_method", "MD5");
        comment(result, "metatrack:unencryptedMd5", file.unencryptedMd5());
        comment(result, "metatrack:readRole", file.readRole());
        comment(result, "metatrack:uploadStatus", file.status());
        // Storage keys and signed URLs are internal implementation details, not portable file locations.
        return result;
    }

    static String fileType(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.matches(".*\\.(fastq|fq)(\\.gz)?$")) return "fastq";
        if (lower.endsWith(".bam")) return "bam";
        if (lower.endsWith(".cram")) return "cram";
        return null;
    }

    static String designDescription(Assay assay) {
        List<String> parts = new ArrayList<>();
        if (assay.libraryStrategy != null) {
            parts.add(assay.libraryStrategy + (assay.librarySource != null ? " of " + assay.librarySource + " material" : ""));
        } else if (assay.librarySource != null) {
            parts.add(assay.librarySource + " material");
        }
        if (assay.librarySelection != null) parts.add(assay.librarySelection + " selection");
        if (assay.libraryLayout != null) parts.add(assay.libraryLayout + " layout");
        if (assay.libraryName != null) parts.add(assay.libraryName);
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    private ObjectNode taxonTerm(Integer taxId, Map<Integer, String> taxonNames) {
        String name = taxonNames.get(taxId);
        ObjectNode term = term(name != null ? name : taxId.toString());
        term.put("termSource", "NCBITaxon");
        term.put("termAccession", NCBI_TAXON_IRI + taxId);
        if (name == null) comment(term, "metatrack:note", "scientific name unavailable at export time");
        return term;
    }

    private ObjectNode parameter(Ids ids, UUID assayId, String name) {
        ObjectNode parameter = node(ids.of("parameter", assayId + ":" + name));
        parameter.set("parameterName", term(name));
        return parameter;
    }

    private void parameterValue(ArrayNode target, Ids ids, UUID assayId, String name, Object value) {
        if (value == null || value.toString().isBlank()) return;
        ObjectNode parameterValue = target.addObject();
        parameterValue.set("category", ref(ids.of("parameter", assayId + ":" + name)));
        parameterValue.set("value", term(value.toString()));
    }

    private ObjectNode characteristic(String categoryId, ObjectNode value) {
        ObjectNode characteristic = mapper.createObjectNode();
        characteristic.set("category", ref(categoryId));
        // MARS models every value as an ontology annotation, so plain numbers/strings are wrapped as well.
        characteristic.set("value", value);
        return characteristic;
    }

    private static String text(Object value) {
        return value instanceof BigDecimal number ? number.toPlainString() : value.toString();
    }

    private ObjectNode term(String value) {
        return mapper.createObjectNode().put("annotationValue", value);
    }

    private ObjectNode node(String id) {
        return mapper.createObjectNode().put("@id", id);
    }

    private ObjectNode ref(String id) {
        return node(id);
    }

    private void comment(ObjectNode target, String name, Object value) {
        if (value != null) target.withArray("comments").addObject().put("name", name).put("value", value.toString());
    }

    private void put(ObjectNode target, String name, String value) {
        if (value != null) target.put(name, value);
    }

    private record Ids(String instance) {
        String of(String kind, Object value) {
            return "urn:metatrack:" + instance + ":" + kind + ":" + value;
        }
    }

    /** Built-in categories are only declared once a sample uses them. */
    private final class Categories {
        private final Ids ids;
        private final ArrayNode target;
        private final Map<String, String> builtIn = new LinkedHashMap<>();

        Categories(Ids ids, ArrayNode target) {
            this.ids = ids;
            this.target = target;
        }

        ObjectNode add(String id, String name) {
            ObjectNode category = target.addObject().put("@id", id);
            category.set("characteristicType", term(name));
            return category;
        }

        String builtIn(String isaName) {
            return builtIn.computeIfAbsent(isaName, name -> {
                String id = ids.of("characteristic", name.replaceAll("[^A-Za-z0-9]+", "-")
                        .replaceAll("(^-|-$)", "").toLowerCase(Locale.ROOT));
                add(id, name);
                return id;
            });
        }
    }
}
