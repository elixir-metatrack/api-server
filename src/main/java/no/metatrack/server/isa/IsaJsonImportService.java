package no.metatrack.server.isa;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import no.metatrack.server.assay.Assay;
import no.metatrack.server.assay.AssayService;
import no.metatrack.server.assay.vocabulary.AssayVocabularyService;
import no.metatrack.server.file.File;
import no.metatrack.server.file.ReadRole;
import no.metatrack.server.project.Project;
import no.metatrack.server.sample.CSVSampleSheetImportService;
import no.metatrack.server.sample.Sample;
import no.metatrack.server.sample.SampleService;
import no.metatrack.server.sample.metadata.CreateSampleMetadataFieldRequest;
import no.metatrack.server.sample.metadata.SampleMetadataField;
import no.metatrack.server.sample.metadata.SampleMetadataFieldService;
import no.metatrack.server.sample.metadata.SampleMetadataFieldType;
import no.metatrack.server.sample.metadata.SampleMetadataService;
import no.metatrack.server.sample.vocabulary.SampleVocabularyRules;
import no.metatrack.server.sample.vocabulary.SampleVocabularyService;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Imports one ISA-JSON study into an existing root project (docs/isa-json/README.md, rules I1-I7).
 * The same plan is used for the preview and the commit; the commit writes nothing if any error exists.
 */
@ApplicationScoped
public class IsaJsonImportService {
    private static final Pattern NCBI_TAXON = Pattern.compile("NCBITaxon[_:](\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern READ_ONE = Pattern.compile("(?i)[._-]R?1(?=[._-])");
    private static final Pattern READ_TWO = Pattern.compile("(?i)[._-]R?2(?=[._-])");
    private static final Map<String, String> PARAMETER_ALIASES = Map.of(
            "SEQUENCING_INSTRUMENT", "INSTRUMENT_MODEL",
            "INSTRUMENT", "INSTRUMENT_MODEL",
            "INSERT_SIZE", "NOMINAL_LENGTH",
            "SEQUENCING_PLATFORM", "PLATFORM");

    @Inject SampleService sampleService;
    @Inject SampleMetadataFieldService fieldService;
    @Inject SampleMetadataService metadataService;
    @Inject SampleVocabularyService sampleVocabulary;
    @Inject AssayService assayService;
    @Inject AssayVocabularyService assayVocabulary;

    public record Problem(String item, String field, String value, String message) {}

    public record NewField(String key, String label, SampleMetadataFieldType type) {}

    public record AssaySummary(String name, int samples, int files) {}

    public record StudyOption(String identifier, String title) {}

    public record Report(boolean committed, String study, List<StudyOption> studies, List<String> samples,
                         List<NewField> newFields, List<AssaySummary> assays, List<String> ignored,
                         List<Problem> errors) {}

    @Transactional
    public Report importDocument(Long projectId, JsonNode document, String studyIdentifier, boolean commit,
                                 boolean mayCreateFields) {
        Project project = Project.<Project>findByIdOptional(projectId).orElseThrow(NotFoundException::new);
        if (project.isSubProject()) {
            throw new BadRequestException("Import ISA-JSON into the parent project; sub-projects only link samples");
        }
        Plan plan = new Plan(project, document, studyIdentifier, mayCreateFields);
        boolean write = commit && plan.errors.isEmpty();
        if (write) plan.write();
        return new Report(write, plan.studyName, plan.studyOptions, plan.samples.stream().map(s -> s.sample.name).toList(),
                plan.fields.values().stream().filter(field -> field.existing == null)
                        .map(field -> new NewField(field.key, field.label, field.type)).toList(),
                plan.assays.stream().map(a -> new AssaySummary(a.name, a.members.size(), a.files.size())).toList(),
                List.copyOf(plan.ignored), List.copyOf(plan.errors));
    }

    private final class Plan {
        final Project project;
        final List<Problem> errors = new ArrayList<>();
        final Set<String> ignored = new LinkedHashSet<>();
        final Map<String, FieldPlan> fields = new LinkedHashMap<>();
        final List<SamplePlan> samples = new ArrayList<>();
        final Map<String, SamplePlan> samplesByIsaId = new HashMap<>();
        final Set<String> studySampleIds = new HashSet<>();
        final List<AssayPlan> assays = new ArrayList<>();
        final Map<String, JsonNode> categories = new HashMap<>();
        final Map<String, String> units = new HashMap<>();
        final Map<String, String> parameterNames = new HashMap<>();
        final Map<String, JsonNode> protocols = new HashMap<>();
        final List<SampleMetadataField> existingFields;
        final List<StudyOption> studyOptions = new ArrayList<>();
        final Map<String, List<String>> skippedFiles = new LinkedHashMap<>();
        String studyName;

        Plan(Project project, JsonNode document, String studyIdentifier, boolean mayCreateFields) {
            this.project = project;
            this.existingFields = SampleMetadataField.list("project.id = ?1 and archivedOn is null", project.id);
            JsonNode investigation = document.has("investigation") ? document.get("investigation") : document;
            JsonNode study = chooseStudy(investigation, studyIdentifier);
            if (study == null) return;
            studyName = Optional.ofNullable(text(study.get("title"))).orElse(text(study.get("identifier")));
            noteIgnored(investigation, study);
            index(study);
            planSamples(study);
            planDeclaredFields(study);
            finishFields(mayCreateFields);
            validateSamples();
            for (JsonNode assay : study.path("assays")) planAssay(assay);
            skippedFiles.forEach((reason, names) -> ignored.add(names.size() + " file(s) " + reason + ": "
                    + String.join(", ", names.subList(0, Math.min(5, names.size())))
                    + (names.size() > 5 ? ", ..." : "")));
        }

        private JsonNode chooseStudy(JsonNode investigation, String identifier) {
            JsonNode studies = investigation.path("studies");
            studies.forEach(study -> studyOptions.add(new StudyOption(text(study.get("identifier")), text(study.get("title")))));
            if (!studies.isArray() || studies.isEmpty()) {
                errors.add(new Problem("document", "studies", null, "The document contains no study"));
                return null;
            }
            if (identifier == null || identifier.isBlank()) {
                if (studies.size() == 1) return studies.get(0);
                errors.add(new Problem("document", "studies", String.valueOf(studies.size()),
                        "The document contains several studies; choose one by its identifier"));
                return null;
            }
            for (JsonNode study : studies) {
                if (identifier.equals(text(study.get("identifier")))) {
                    if (studies.size() > 1) ignored.add((studies.size() - 1) + " other stud(ies) in the document");
                    return study;
                }
            }
            errors.add(new Problem("document", "studies", identifier, "No study with this identifier"));
            return null;
        }

        private void noteIgnored(JsonNode investigation, JsonNode study) {
            for (String key : List.of("publications", "people")) {
                if (investigation.path(key).size() + study.path(key).size() > 0) ignored.add(key);
            }
            if (study.path("factors").size() > 0) ignored.add("factors and factor values");
            if (study.path("studyDesignDescriptors").size() > 0) ignored.add("study design descriptors");
        }

        private void index(JsonNode study) {
            List<JsonNode> scopes = new ArrayList<>(List.of(study));
            study.path("assays").forEach(scopes::add);
            for (JsonNode scope : scopes) {
                for (JsonNode category : scope.path("characteristicCategories")) {
                    if (category.has("@id")) categories.put(category.get("@id").asText(), category);
                }
                for (JsonNode unit : scope.path("unitCategories")) {
                    if (unit.has("@id")) units.put(unit.get("@id").asText(), text(unit.get("annotationValue")));
                }
            }
            for (JsonNode protocol : study.path("protocols")) {
                if (protocol.has("@id")) protocols.put(protocol.get("@id").asText(), protocol);
                for (JsonNode parameter : protocol.path("parameters")) {
                    if (parameter.has("@id")) {
                        parameterNames.put(parameter.get("@id").asText(), text(parameter.get("parameterName")));
                    }
                }
            }
        }

        // ---- samples (I3, I4) ----

        private void planSamples(JsonNode study) {
            Map<String, JsonNode> sources = new HashMap<>();
            for (JsonNode source : study.at("/materials/sources")) sources.put(id(source), source);
            Map<String, List<String>> sourcesOfSample = new HashMap<>();
            for (JsonNode process : study.path("processSequence")) {
                for (JsonNode output : process.path("outputs")) {
                    for (JsonNode input : process.path("inputs")) {
                        if (sources.containsKey(id(input))) {
                            sourcesOfSample.computeIfAbsent(id(output), k -> new ArrayList<>()).add(id(input));
                        }
                    }
                }
            }
            Set<String> names = new HashSet<>();
            for (JsonNode isaSample : study.at("/materials/samples")) {
                SamplePlan plan = new SamplePlan(id(isaSample));
                studySampleIds.add(plan.isaId);
                String name = text(isaSample.get("name"));
                plan.sample.name = name;
                if (name == null) {
                    errors.add(new Problem(plan.isaId, "name", null, "Sample has no name"));
                    continue;
                }
                if (!names.add(name)) {
                    errors.add(new Problem(name, "name", name, "Duplicate sample name within this file: '" + name + "'"));
                    continue;
                }
                if (Sample.sampleExistsByName(name, project.id)) {
                    errors.add(new Problem(name, "name", name, "Sample name '" + name + "' already exists in this project"));
                    continue;
                }
                // Source characteristics first; the sample's own value wins.
                Map<String, JsonNode> characteristics = new LinkedHashMap<>();
                Set<String> sourceIds = new LinkedHashSet<>(sourcesOfSample.getOrDefault(plan.isaId, List.of()));
                isaSample.path("derivesFrom").forEach(ref -> sourceIds.add(id(ref)));
                for (String sourceId : sourceIds) {
                    JsonNode source = sources.get(sourceId);
                    if (source != null) collect(source, characteristics);
                }
                collect(isaSample, characteristics);
                characteristics.forEach((name2, characteristic) -> apply(plan, name2, characteristic));
                if (plan.sample.taxId == null) plan.sample.taxId = plan.organismTaxId;
                if (plan.sample.hostTaxId == null) plan.sample.hostTaxId = plan.hostTaxId;
                // TaxHub only resolves IDs to names, not names to IDs.
                if (plan.sample.taxId == null && plan.organismLabel != null) {
                    errors.add(new Problem(name, "organism", plan.organismLabel,
                            "Organism needs an NCBI taxonomy ID (termAccession NCBITaxon_<id> or a tax_id characteristic)"));
                }
                if (plan.sample.hostTaxId == null && plan.hostLabel != null) {
                    errors.add(new Problem(name, "host scientific name", plan.hostLabel,
                            "Host needs an NCBI taxonomy ID (termAccession NCBITaxon_<id> or a host taxid characteristic)"));
                }
                samples.add(plan);
                samplesByIsaId.put(plan.isaId, plan);
            }
        }

        /** Metatrack exports declare every active custom field, also those without values. */
        private void planDeclaredFields(JsonNode study) {
            for (JsonNode category : study.path("characteristicCategories")) {
                JsonNode type = category.path("characteristicType");
                String key = comment(type, "metatrack:fieldKey");
                if (key == null) continue;
                FieldPlan field = declareField(key, text(type), comment(type, "metatrack:fieldType"));
                field.tooltip = comment(type, "metatrack:tooltip");
            }
        }

        private void collect(JsonNode material, Map<String, JsonNode> target) {
            for (JsonNode characteristic : material.path("characteristics")) {
                JsonNode category = categoryOf(characteristic);
                String name = text(category.path("characteristicType"));
                if (name != null) target.put(IsaSampleFields.normalize(name), characteristic);
            }
        }

        private JsonNode categoryOf(JsonNode characteristic) {
            JsonNode category = characteristic.path("category");
            if (category.has("characteristicType")) return category;
            return categories.getOrDefault(id(category), category);
        }

        private void apply(SamplePlan plan, String normalizedName, JsonNode characteristic) {
            JsonNode category = categoryOf(characteristic);
            JsonNode type = category.path("characteristicType");
            String label = text(type);
            String value = valueWithUnit(characteristic, units);
            if (value == null) return;
            String fieldKey = comment(type, "metatrack:fieldKey");
            if (fieldKey != null) {
                customValue(plan, fieldKey, label, comment(type, "metatrack:fieldType"), value);
                return;
            }
            if (IsaSampleFields.isOrganism(label)) {
                plan.organismTaxId = taxId(characteristic.get("value"));
                if (plan.organismTaxId == null) plan.organismLabel = value;
                return;
            }
            if (IsaSampleFields.isHostOrganism(label)) {
                plan.hostTaxId = taxId(characteristic.get("value"));
                if (plan.hostTaxId == null) plan.hostLabel = value;
                return;
            }
            Optional<IsaSampleFields.Field> builtIn = IsaSampleFields.find(label);
            if (builtIn.isPresent()) {
                try {
                    builtIn.get().setter().accept(plan.sample, value);
                } catch (RuntimeException e) {
                    errors.add(new Problem(plan.sample.name, builtIn.get().isaName(), value,
                            "Invalid value for " + builtIn.get().isaName()
                                    + (builtIn.get().property().equals("collectionDate") ? " (expected yyyy-MM-dd)" : "")));
                }
                return;
            }
            SampleMetadataField existing = existingFields.stream()
                    .filter(field -> field.label.equalsIgnoreCase(label) || field.key.equals(keyFor(label)))
                    .findFirst().orElse(null);
            customValue(plan, existing != null ? existing.key : keyFor(label), label, null, value);
        }

        private Integer taxId(JsonNode value) {
            String accession = text(value == null ? null : value.get("termAccession"));
            if (accession != null) {
                Matcher matcher = NCBI_TAXON.matcher(accession);
                if (matcher.find()) return Integer.valueOf(matcher.group(1));
            }
            String text = text(value);
            try {
                return text == null ? null : IsaSampleFields.taxId(text);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        private void customValue(SamplePlan plan, String key, String label, String declaredType, String value) {
            FieldPlan field = declareField(key, label, declaredType);
            field.rawValues.add(value);
            plan.rawCustom.put(key, value);
        }

        private FieldPlan declareField(String key, String label, String declaredType) {
            return fields.computeIfAbsent(key, unused -> {
                SampleMetadataField existing = existingFields.stream().filter(f -> f.key.equals(key)).findFirst().orElse(null);
                FieldPlan created = new FieldPlan(key, existing != null ? existing.label : label, existing);
                if (existing != null) created.type = existing.type;
                else if (declaredType != null) {
                    try {
                        created.type = SampleMetadataFieldType.valueOf(declaredType);
                    } catch (IllegalArgumentException e) {
                        created.type = null;
                    }
                }
                return created;
            });
        }

        private void finishFields(boolean mayCreateFields) {
            Set<String> newKeys = new HashSet<>();
            for (FieldPlan field : fields.values()) {
                if (field.existing != null) continue;
                try {
                    SampleMetadataFieldService.validateKey(field.key);
                    SampleMetadataFieldService.validateLabel(field.label);
                } catch (BadRequestException e) {
                    errors.add(new Problem(field.label, field.key, null, "Cannot create custom field: " + e.getMessage()));
                }
                newKeys.add(field.key);
                if (field.type == null) {
                    field.type = !field.rawValues.isEmpty() && field.rawValues.stream().allMatch(IsaJsonImportService::isNumber)
                            ? SampleMetadataFieldType.NUMBER : SampleMetadataFieldType.TEXT;
                }
            }
            if (!newKeys.isEmpty() && !mayCreateFields) {
                errors.add(new Problem("custom fields", String.join(", ", newKeys), null,
                        "Creating custom fields requires the ADMIN role in this project"));
            }
            for (SamplePlan plan : samples) {
                plan.rawCustom.forEach((key, raw) -> {
                    FieldPlan field = fields.get(key);
                    try {
                        plan.custom.put(key, metadataService.parseCsvValue(field.type, raw));
                    } catch (BadRequestException e) {
                        errors.add(new Problem(plan.sample.name, key, raw, e.getMessage()));
                    }
                });
            }
        }

        private void validateSamples() {
            SampleVocabularyRules rules = sampleVocabulary.loadRules(project.id);
            for (SamplePlan plan : samples) {
                SampleVocabularyService.validate(rules, plan.sample.name,
                                CSVSampleSheetImportService.builtInValues(plan.sample), plan.custom)
                        .forEach(violation -> errors.add(new Problem(violation.sample(), violation.fieldKey(),
                                String.valueOf(violation.rejectedValue()), violation.message())));
            }
        }

        // ---- assays, membership, files (I5, I6) ----

        private void planAssay(JsonNode isaAssay) {
            AssayPlan plan = new AssayPlan();
            plan.name = Optional.ofNullable(comment(isaAssay, "metatrack:assayName"))
                    .orElseGet(() -> assayName(Optional.ofNullable(text(isaAssay.get("filename"))).orElse(id(isaAssay))));
            if (Assay.count("name = ?1 and project.id = ?2", plan.name, project.id) > 0) {
                errors.add(new Problem(plan.name, "name", plan.name, "An experiment with this name already exists"));
            }
            Map<String, Set<String>> values = new LinkedHashMap<>();
            Map<String, JsonNode> producers = new HashMap<>();
            for (JsonNode process : isaAssay.path("processSequence")) {
                process.path("outputs").forEach(output -> producers.put(id(output), process));
                for (JsonNode value : process.path("parameterValues")) {
                    String name = parameterNames.get(id(value.path("category")));
                    if (name == null) continue;
                    String key = name.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
                    key = PARAMETER_ALIASES.getOrDefault(key, key);
                    String text = text(value.get("value"));
                    if (text != null) values.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(text);
                }
                String performer = text(process.get("performer"));
                if (performer != null) values.computeIfAbsent("PERFORMER", k -> new LinkedHashSet<>()).add(performer);
                JsonNode protocol = protocols.get(id(process.path("executesProtocol")));
                if (protocol != null && "library construction".equalsIgnoreCase(text(protocol.get("protocolType")))
                        && !"library construction".equalsIgnoreCase(text(protocol.get("name")))) {
                    values.computeIfAbsent("PROTOCOL_NAME", k -> new LinkedHashSet<>()).add(text(protocol.get("name")));
                }
            }
            plan.libraryName = single(plan, values, "LIBRARY_CONSTRUCTION_PROTOCOL", "PROTOCOL_NAME");
            plan.librarySource = single(plan, values, "LIBRARY_SOURCE");
            plan.librarySelection = single(plan, values, "LIBRARY_SELECTION");
            plan.libraryStrategy = single(plan, values, "LIBRARY_STRATEGY");
            plan.libraryLayout = single(plan, values, "LIBRARY_LAYOUT");
            plan.platform = single(plan, values, "PLATFORM");
            plan.instrumentModel = Optional.ofNullable(single(plan, values, "INSTRUMENT_MODEL"))
                    .orElse(plan.platform == null ? text(isaAssay.get("technologyPlatform")) : null);
            plan.sequencingLaboratory = single(plan, values, "PERFORMER");
            String insertSize = single(plan, values, "NOMINAL_LENGTH");
            if (insertSize != null) {
                try {
                    plan.insertSize = new BigDecimal(insertSize).intValueExact();
                } catch (ArithmeticException | NumberFormatException e) {
                    errors.add(new Problem(plan.name, "NOMINAL_LENGTH", insertSize, "Insert size must be a whole number"));
                }
            }
            for (String unmapped : List.of("LIBRARY_NAME", "DESIGN_DESCRIPTION")) {
                if (values.containsKey(unmapped)) ignored.add(unmapped + " (no Metatrack field)");
            }
            plan.studyAccession = comment(isaAssay, "metatrack:studyAccession");
            plan.experimentAccession = comment(isaAssay, "metatrack:experimentAccession");
            plan.runAccession = comment(isaAssay, "metatrack:runAccession");
            Map<String, String> vocabulary = new LinkedHashMap<>();
            vocabulary.put("study_accession", plan.studyAccession);
            vocabulary.put("instrument_model", plan.instrumentModel);
            vocabulary.put("library_name", plan.libraryName);
            vocabulary.put("library_source", plan.librarySource);
            vocabulary.put("library_selection", plan.librarySelection);
            vocabulary.put("library_strategy", plan.libraryStrategy);
            vocabulary.put("library_layout", plan.libraryLayout);
            assayVocabulary.validate(plan.name, vocabulary).forEach(violation ->
                    errors.add(new Problem(violation.assay(), violation.field(), vocabulary.get(violation.field()),
                            violation.message())));

            Map<String, JsonNode> otherMaterials = new HashMap<>();
            for (JsonNode material : isaAssay.at("/materials/otherMaterials")) {
                otherMaterials.put(id(material), material);
                if (material.path("characteristics").size() > 0) ignored.add("extract/library characteristics");
            }
            for (JsonNode ref : isaAssay.at("/materials/samples")) {
                SamplePlan sample = samplesByIsaId.get(id(ref));
                if (sample != null) plan.members.add(sample);
            }
            Set<String> fileKeys = new HashSet<>();
            for (JsonNode dataFile : isaAssay.path("dataFiles")) {
                String fileName = text(dataFile.get("name"));
                if ("Derived Data File".equalsIgnoreCase(text(dataFile.get("type")))) {
                    ignored.add("derived data files");
                    continue;
                }
                Set<String> lineage = new LinkedHashSet<>();
                trace(id(dataFile), producers, otherMaterials, lineage, new HashSet<>());
                // A Metatrack file belongs to exactly one sample; other files are listed, not imported.
                if (lineage.size() != 1) {
                    skip(lineage.isEmpty() ? "not produced by any process of a sample" : "belonging to several samples",
                            fileName);
                    continue;
                }
                SamplePlan sample = samplesByIsaId.get(lineage.iterator().next());
                if (sample == null) continue; // the sample itself was rejected; that error is already reported
                String md5 = comment(dataFile, "file checksum");
                if (fileName == null || md5 == null) {
                    skip("without name or md5 checksum", fileName);
                    continue;
                }
                if (!fileKeys.add(sample.sample.name + "/" + fileName)) {
                    errors.add(new Problem(plan.name, "file", fileName, "Duplicate file reference in import"));
                    continue;
                }
                plan.members.add(sample);
                plan.files.add(new FilePlan(sample, fileName, md5, comment(dataFile, "metatrack:unencryptedMd5"),
                        readRole(comment(dataFile, "metatrack:readRole"), fileName, plan.libraryLayout)));
            }
            assays.add(plan);
        }

        private void skip(String reason, String fileName) {
            skippedFiles.computeIfAbsent(reason, k -> new ArrayList<>()).add(fileName == null ? "(unnamed)" : fileName);
        }

        private void trace(String nodeId, Map<String, JsonNode> producers, Map<String, JsonNode> otherMaterials,
                           Set<String> result, Set<String> visited) {
            if (nodeId == null || !visited.add(nodeId)) return;
            if (studySampleIds.contains(nodeId)) {
                result.add(nodeId);
                return;
            }
            JsonNode material = otherMaterials.get(nodeId);
            if (material != null) {
                material.path("derivesFrom").forEach(ref -> trace(id(ref), producers, otherMaterials, result, visited));
            }
            JsonNode producer = producers.get(nodeId);
            if (producer != null) {
                producer.path("inputs").forEach(input -> trace(id(input), producers, otherMaterials, result, visited));
            }
        }

        private String single(AssayPlan plan, Map<String, Set<String>> values, String... keys) {
            for (String key : keys) {
                Set<String> found = values.get(key);
                if (found == null || found.isEmpty()) continue;
                if (found.size() > 1) {
                    errors.add(new Problem(plan.name, key, String.join(" | ", found),
                            "Metatrack stores one value per experiment, but the samples of this assay differ"));
                }
                return found.iterator().next();
            }
            return null;
        }

        // ---- commit ----

        void write() {
            for (FieldPlan field : fields.values()) {
                if (field.existing == null) {
                    fieldService.create(project.id, new CreateSampleMetadataFieldRequest(field.key, field.label, field.type, field.tooltip));
                }
            }
            for (SamplePlan plan : samples) {
                Sample s = plan.sample;
                plan.created = sampleService.createSample(project.id, s.name, s.alias, s.taxId, s.hostTaxId, s.mlst,
                        s.location, s.sequencingLab, s.institution, s.isolationSource, s.collectionDate,
                        s.hostHealthState, s.projectTitle, s.description, s.isolate, s.collectedBy, s.latitude,
                        s.longitude, s.environmentalSample, s.hostAssociated, s.hostCommonName, s.hostSubjectId,
                        s.collectorName, s.collectingInstitution, s.hostSex, s.influenzaTestMethod,
                        s.influenzaTestResult, s.otherPathogensTested, s.otherPathogensTestResult, s.hostHabitat,
                        s.isolationSourceHostAssociated, s.hostBehaviour, s.isolationSourceNonHostAssociated,
                        s.influenzaVirusType, s.influenzaSubType, s.serovar, s.strain, s.hostAge, s.county, s.commune,
                        s.hospitalHealthInstitution, s.projectAccession, s.studyAccession, s.sampleAccession,
                        s.secondaryAccession, plan.custom);
            }
            for (AssayPlan plan : assays) {
                Assay assay = assayService.createAssay(project.id, plan.name, plan.studyAccession, plan.instrumentModel,
                        plan.libraryName, plan.librarySource, plan.libraryStrategy, plan.librarySelection,
                        plan.libraryLayout, plan.insertSize, plan.experimentAccession, plan.runAccession);
                assay.sequencingPlatform = plan.platform;
                assay.sequencingLaboratory = plan.sequencingLaboratory;
                if (!assay.isPersistent()) assay.persist();
                plan.members.forEach(member -> assay.addSample(member.created));
                // Files reference the assay_sample row (fk_file_assay_sample); write memberships first.
                Assay.getEntityManager().flush();
                for (FilePlan file : plan.files) {
                    File.importPending(project.id, assay.id, file.sample.created, assay, file.name, file.md5,
                            file.unencryptedMd5, file.readRole).ifPresent(message -> {
                        throw new BadRequestException(file.name + ": " + message);
                    });
                }
            }
        }
    }

    private static final class FieldPlan {
        final String key;
        final String label;
        final SampleMetadataField existing;
        final List<String> rawValues = new ArrayList<>();
        SampleMetadataFieldType type;
        String tooltip;

        FieldPlan(String key, String label, SampleMetadataField existing) {
            this.key = key;
            this.label = label;
            this.existing = existing;
        }
    }

    private static final class SamplePlan {
        final String isaId;
        final Sample sample = new Sample();
        final Map<String, String> rawCustom = new LinkedHashMap<>();
        final Map<String, Object> custom = new LinkedHashMap<>();
        Integer organismTaxId;
        Integer hostTaxId;
        String organismLabel;
        String hostLabel;
        Sample created;

        SamplePlan(String isaId) {
            this.isaId = isaId;
        }
    }

    private static final class AssayPlan {
        String name;
        String studyAccession;
        String experimentAccession;
        String runAccession;
        String instrumentModel;
        String platform;
        String sequencingLaboratory;
        String libraryName;
        String librarySource;
        String librarySelection;
        String libraryStrategy;
        String libraryLayout;
        Integer insertSize;
        final Set<SamplePlan> members = new LinkedHashSet<>();
        final List<FilePlan> files = new ArrayList<>();
    }

    private record FilePlan(SamplePlan sample, String name, String md5, String unencryptedMd5, ReadRole readRole) {}

    static ReadRole readRole(String declared, String fileName, String layout) {
        if (declared != null) {
            try {
                return ReadRole.valueOf(declared.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // fall through to the file name
            }
        }
        if ("SINGLE".equalsIgnoreCase(layout)) return ReadRole.SINGLE;
        if (READ_ONE.matcher(fileName).find()) return ReadRole.FORWARD;
        if (READ_TWO.matcher(fileName).find()) return ReadRole.REVERSE;
        return null;
    }

    /** "a_wgs.txt" -> "wgs"; ARC style "assays/RNASeq/isa.assay" -> "RNASeq". */
    static String assayName(String filename) {
        String name = filename.replaceFirst("(?i)/isa\\.assay(\\.xlsx)?$", "");
        name = name.substring(name.lastIndexOf('/') + 1);
        return name.replaceFirst("^a_", "").replaceFirst("\\.[A-Za-z]+$", "");
    }

    /** Lower-case snake case key for a custom field label; prefixed if it would clash with a built-in field. */
    static String keyFor(String label) {
        String key = label.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("(^_+|_+$)", "");
        if (key.isEmpty() || !Character.isLetter(key.charAt(0))) key = "f_" + key;
        try {
            SampleMetadataFieldService.validateKey(key);
        } catch (BadRequestException e) {
            key = "isa_" + key;
        }
        return key.length() > 64 ? key.substring(0, 64) : key;
    }

    private static String valueWithUnit(JsonNode characteristic, Map<String, String> units) {
        String value = text(characteristic.get("value"));
        if (value == null) return null;
        JsonNode unit = characteristic.path("unit");
        String unitText = unit.has("annotationValue") ? text(unit.get("annotationValue")) : units.get(id(unit));
        return unitText == null ? value : value + " " + unitText;
    }

    static String text(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return null;
        if (value.isObject()) return value.has("annotationValue") ? text(value.get("annotationValue")) : null;
        if (value.isNumber()) return value.decimalValue().toPlainString();
        String text = value.asText().trim();
        return text.isEmpty() ? null : text;
    }

    private static String id(JsonNode node) {
        return node == null || !node.has("@id") ? null : node.get("@id").asText();
    }

    private static String comment(JsonNode node, String name) {
        for (JsonNode comment : node.path("comments")) {
            if (name.equalsIgnoreCase(text(comment.get("name")))) return text(comment.get("value"));
        }
        return null;
    }

    private static boolean isNumber(String value) {
        try {
            new BigDecimal(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
