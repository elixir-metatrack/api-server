package no.metatrack.server.isa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import no.metatrack.server.assay.Assay;
import no.metatrack.server.file.FileResponse;
import no.metatrack.server.file.ReadRole;
import no.metatrack.server.file.UploadStatus;
import no.metatrack.server.project.Project;
import no.metatrack.server.sample.Sample;
import no.metatrack.server.sample.SampleResponse;
import no.metatrack.server.sample.metadata.SampleMetadataField;
import no.metatrack.server.sample.metadata.SampleMetadataFieldType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class IsaJsonExportWriterTest {
    private final IsaJsonExportWriter writer = new IsaJsonExportWriter();

    IsaJsonExportWriterTest() {
        writer.mapper = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Test
    void preservesCustomTypesAndPrecisionWithoutMergingFieldsWithTheSameLabel() {
        Sample sample = sample();
        sample.collectionDate = LocalDate.of(2026, 10, 19);
        List<SampleMetadataField> fields = List.of(
                field("concentration", SampleMetadataFieldType.NUMBER),
                field("checked", SampleMetadataFieldType.BOOLEAN),
                field("extracted", SampleMetadataFieldType.DATE));
        BigDecimal precise = new BigDecimal("1234567890.1234567890123456789");
        var export = writer.write(project(), List.of(SampleResponse.fromEntity(sample, Map.of(
                "concentration", precise, "checked", false, "extracted", LocalDate.of(2026, 10, 18)))), fields, List.of(),
                Map.of(), "test");
        JsonNode study = export.at("/studies/0");
        JsonNode characteristics = study.at("/materials/samples/0/characteristics");
        assertEquals(4, study.get("characteristicCategories").size());
        assertTrue(characteristics.toString().contains("1234567890.1234567890123456789"));
        assertTrue(characteristics.toString().contains("\"false\""));
        assertTrue(characteristics.toString().contains("2026-10-18"));
        assertTrue(characteristics.toString().contains("2026-10-19"));
        assertTrue(study.toString().contains("metatrack:fieldType"));
        assertReferencesResolve(export);
    }

    @Test
    void keepsBothReadFilesOnOneSampleProcessAndDoesNotExposeStorageCredentials() {
        Sample sample = sample();
        Assay assay = new Assay();
        assay.id = UUID.randomUUID();
        assay.name = "Paired sequencing";
        assay.libraryLayout = "PAIRED";
        assay.libraryStrategy = "WGS";
        var data = new IsaJsonExportService.AssayData(assay, List.of(sample.id), List.of(
                file(sample.id, assay.id, "R1.fastq.gz", ReadRole.FORWARD),
                file(sample.id, assay.id, "R2.fastq.gz", ReadRole.REVERSE)));
        var export = write(sample, List.of(data));
        JsonNode isaAssay = export.at("/studies/0/assays/0");
        assertEquals(2, isaAssay.get("dataFiles").size());
        // library construction (sample -> library) and sequencing (library -> both read files)
        assertEquals(2, isaAssay.get("processSequence").size());
        assertEquals(1, isaAssay.at("/materials/otherMaterials").size());
        assertEquals(isaAssay.at("/materials/otherMaterials/0/@id"), isaAssay.at("/processSequence/1/inputs/0/@id"));
        assertEquals(2, isaAssay.at("/processSequence/1/outputs").size());
        assertEquals("Whole Genome Sequencing", comment(isaAssay, "STUDY_TYPE"));
        assertTrue(export.toString().contains("LIBRARY_LAYOUT"));
        assertFalse(export.toString().contains("private-storage-key"));
        assertFalse(export.toString().contains("private-path"));
        assertFalse(export.toString().contains("uploadedBy"));
        assertReferencesResolve(export);
        assertEquals(export, write(sample, List.of(data)));
    }

    @Test
    void emptyProjectStillExportsAnInvestigationAndStudy() {
        var export = writer.write(project(), List.of(), List.of(), List.of(), Map.of(), "test");
        assertEquals("ISA demo", export.get("title").asText());
        assertEquals(1, export.get("studies").size());
        assertTrue(export.at("/studies/0/materials/samples").isEmpty());
        assertReferencesResolve(export);
    }

    @Test
    void writesOrganismAsNcbiTaxonTermOnSourceAndSampleAndWrapsEveryValue() {
        Sample sample = sample();
        sample.taxId = 287;
        sample.hostTaxId = 9606;
        sample.hostSubjectId = "patient-17";
        sample.location = "Norway";
        var export = writer.write(project(), List.of(SampleResponse.fromEntity(sample, Map.of())), List.of(), List.of(),
                Map.of(287, "Pseudomonas aeruginosa"), "test");
        JsonNode study = export.at("/studies/0");
        JsonNode organism = study.at("/materials/sources/0/characteristics/0/value");
        assertEquals("Pseudomonas aeruginosa", organism.get("annotationValue").asText());
        assertEquals("http://purl.obolibrary.org/obo/NCBITaxon_287", organism.get("termAccession").asText());
        JsonNode characteristics = study.at("/materials/samples/0/characteristics");
        characteristics.forEach(characteristic -> assertTrue(characteristic.get("value").isObject()));
        assertEquals(1, characteristics.findValues("annotationValue").stream().filter(v -> v.asText().equals("287")).count());
        // Missing names fall back to the numeric ID; patient references are never exported.
        assertTrue(characteristics.toString().contains("NCBITaxon_9606"));
        assertFalse(export.toString().contains("patient-17"));
        assertTrue(export.toString().contains("geographic location (country and/or sea)"));
        assertTrue(export.toString().startsWith("{\"@id\":\"urn:metatrack:test:investigation:42\""));
        assertReferencesResolve(export);
    }

    @Test
    void everySampleFieldIsInTheRegisterSoNewFieldsAreNotSilentlyDropped() {
        Set<String> handledElsewhere = Set.of("id", "name", "createdOn", "modifiedOn", "customMetadata");
        Set<String> registered = new HashSet<>();
        IsaSampleFields.FIELDS.forEach(field -> registered.add(field.property()));
        for (var component : SampleResponse.class.getRecordComponents()) {
            if (handledElsewhere.contains(component.getName())) continue;
            assertTrue(registered.contains(component.getName()),
                    "SampleResponse." + component.getName() + " needs an entry in IsaSampleFields");
        }
    }

    @Test
    void exportsTheBioSamplesAccessionUnderTheNameMarsReads() {
        Sample sample = sample();
        sample.sampleAccession = "SAMEA123";
        sample.secondaryAccession = "ERS456";
        var export = write(sample, List.of());
        JsonNode study = export.at("/studies/0");
        String categoryId = null;
        for (JsonNode category : study.get("characteristicCategories")) {
            if (category.at("/characteristicType/annotationValue").asText().equals("accession")) {
                categoryId = category.get("@id").asText();
            }
        }
        assertNotNull(categoryId);
        boolean found = false;
        for (JsonNode characteristic : study.at("/materials/samples/0/characteristics")) {
            if (characteristic.at("/category/@id").asText().equals(categoryId)) {
                assertEquals("SAMEA123", characteristic.at("/value/annotationValue").asText());
                found = true;
            }
        }
        assertTrue(found);
        assertTrue(export.toString().contains("ERS456"));
    }

    @Test
    void refusesFileGroupsThatAreNotOneRun() {
        Sample sample = sample();
        Assay assay = new Assay();
        assay.id = UUID.randomUUID();
        assay.name = "Lanes";
        assay.libraryLayout = "PAIRED";
        UUID outsider = UUID.randomUUID();
        var twoForward = new IsaJsonExportService.AssayData(assay, List.of(sample.id), List.of(
                file(sample.id, assay.id, "L1_R1.fastq.gz", ReadRole.FORWARD),
                file(sample.id, assay.id, "L2_R1.fastq.gz", ReadRole.FORWARD),
                file(outsider, assay.id, "orphan.fastq.gz", ReadRole.SINGLE)));
        List<String> problems = IsaJsonExportWriter.problems(List.of(twoForward));
        assertEquals(2, problems.size());
        assertTrue(problems.get(0).contains("orphan.fastq.gz"));
        assertTrue(problems.get(1).contains("not a single run"));
        var singleForPaired = new IsaJsonExportService.AssayData(assay, List.of(sample.id), List.of(
                file(sample.id, assay.id, "only.fastq.gz", ReadRole.SINGLE)));
        assertTrue(IsaJsonExportWriter.problems(List.of(singleForPaired)).get(0).contains("layout PAIRED"));
    }

    private com.fasterxml.jackson.databind.node.ObjectNode write(Sample sample, List<IsaJsonExportService.AssayData> data) {
        return writer.write(project(), List.of(SampleResponse.fromEntity(sample, Map.of())), List.of(), data, Map.of(), "test");
    }

    private static String comment(JsonNode node, String name) {
        for (JsonNode comment : node.path("comments")) {
            if (name.equals(comment.get("name").asText())) return comment.get("value").asText();
        }
        return null;
    }

    private static Project project() {
        Project project = new Project();
        project.id = 42L;
        project.name = "ISA demo";
        return project;
    }

    private static Sample sample() {
        Sample sample = new Sample();
        sample.id = UUID.randomUUID();
        sample.name = "S-001";
        return sample;
    }

    private static SampleMetadataField field(String key, SampleMetadataFieldType type) {
        SampleMetadataField field = new SampleMetadataField();
        field.id = UUID.randomUUID();
        field.key = key;
        field.label = "Same label";
        field.type = type;
        return field;
    }

    private static FileResponse file(UUID sample, UUID assay, String name, ReadRole role) {
        return new FileResponse(UUID.randomUUID(), name, "private-path", "private-storage-key",
                UploadStatus.PENDING, UUID.randomUUID(), sample, assay, role, "a".repeat(32), null);
    }

    private static void assertReferencesResolve(JsonNode document) {
        Set<String> definitions = new HashSet<>();
        Set<String> references = new HashSet<>();
        collectIds(document, definitions, references);
        assertTrue(definitions.containsAll(references), "Every @id reference must resolve within the document");
    }

    private static void collectIds(JsonNode node, Set<String> definitions, Set<String> references) {
        if (node.isObject() && node.has("@id")) {
            String id = node.get("@id").asText();
            if (node.size() == 1) references.add(id);
            else assertTrue(definitions.add(id), "Duplicate definition: " + id);
        }
        node.forEach(child -> collectIds(child, definitions, references));
    }
}
