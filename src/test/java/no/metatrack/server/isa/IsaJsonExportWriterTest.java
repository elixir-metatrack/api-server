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
                "concentration", precise, "checked", false, "extracted", LocalDate.of(2026, 10, 18)))), fields, List.of());
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
        var export = writer.write(project(), List.of(SampleResponse.fromEntity(sample, Map.of())), List.of(), List.of(data));
        JsonNode isaAssay = export.at("/studies/0/assays/0");
        assertEquals(2, isaAssay.get("dataFiles").size());
        assertEquals(1, isaAssay.get("processSequence").size());
        assertEquals(2, isaAssay.at("/processSequence/0/outputs").size());
        assertTrue(export.toString().contains("LIBRARY_LAYOUT"));
        assertFalse(export.toString().contains("private-storage-key"));
        assertFalse(export.toString().contains("private-path"));
        assertFalse(export.toString().contains("uploadedBy"));
        assertReferencesResolve(export);
        assertEquals(export, writer.write(project(), List.of(SampleResponse.fromEntity(sample, Map.of())), List.of(), List.of(data)));
    }

    @Test
    void emptyProjectStillExportsAnInvestigationAndStudy() {
        var export = writer.write(project(), List.of(), List.of(), List.of());
        assertEquals("ISA demo", export.get("title").asText());
        assertEquals(1, export.get("studies").size());
        assertTrue(export.at("/studies/0/materials/samples").isEmpty());
        assertReferencesResolve(export);
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
