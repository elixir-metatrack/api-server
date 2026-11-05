package no.metatrack.server.isa;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import no.metatrack.server.assay.Assay;
import no.metatrack.server.file.File;
import no.metatrack.server.file.ReadRole;
import no.metatrack.server.file.SubProjectDatabase;
import no.metatrack.server.file.SubProjectTestProfile;
import no.metatrack.server.project.Project;
import no.metatrack.server.sample.Sample;
import no.metatrack.server.sample.metadata.CreateSampleMetadataFieldRequest;
import no.metatrack.server.sample.metadata.SampleMetadataFieldService;
import no.metatrack.server.sample.metadata.SampleMetadataFieldType;
import no.metatrack.server.sample.metadata.SampleMetadataService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@QuarkusTestResource(value = SubProjectDatabase.class, restrictToAnnotatedClass = true)
@TestProfile(SubProjectTestProfile.class)
@TestTransaction
class IsaJsonRoundTripTest {
    @Inject IsaJsonExportService exporter;
    @Inject IsaJsonImportService importer;
    @Inject SampleMetadataFieldService fields;
    @Inject SampleMetadataService metadata;

    @Test
    void exportThenImportRecreatesSamplesFieldsExperimentsAndFiles() {
        Project source = project("Round trip source");
        Sample sample = new Sample();
        sample.project = source;
        sample.name = "NorPa_0001";
        sample.taxId = 287;
        sample.hostTaxId = 9606;
        sample.collectionDate = LocalDate.of(2021, 11, 16);
        sample.location = "Norway";
        sample.isolationSource = "Blood";
        sample.mlst = "ST244";
        sample.sampleAccession = "SAMEA123";
        sample.persist();
        fields.create(source.id, new CreateSampleMetadataFieldRequest("concentration", "Concentration",
                SampleMetadataFieldType.NUMBER, "ng/µl"));
        fields.create(source.id, new CreateSampleMetadataFieldRequest("checked", "Quality checked",
                SampleMetadataFieldType.BOOLEAN, null));
        fields.create(source.id, new CreateSampleMetadataFieldRequest("unused", "Declared but empty",
                SampleMetadataFieldType.DATE, null));
        metadata.apply(source.id, sample, Map.of("concentration", new BigDecimal("12.50"), "checked", true));
        Assay assay = new Assay();
        assay.project = source;
        assay.name = "WGS";
        assay.libraryName = "Illumina DNA prep";
        assay.libraryStrategy = "WGS";
        assay.librarySource = "GENOMIC";
        assay.librarySelection = "RANDOM";
        assay.libraryLayout = "PAIRED";
        assay.insertSize = 250;
        assay.instrumentModel = "NextSeq 500";
        assay.sequencingPlatform = "ILLUMINA";
        assay.sequencingLaboratory = "UNN, Tromsø";
        assay.addSample(sample);
        assay.persistAndFlush();
        file(sample, assay, "NorPa_0001_R1.fastq.gz", ReadRole.FORWARD);
        file(sample, assay, "NorPa_0001_R2.fastq.gz", ReadRole.REVERSE);

        JsonNode original = exporter.export(source.id, null);
        Project target = project("Round trip target");
        var preview = importer.importDocument(target.id, original, null, false, true);
        assertTrue(preview.errors().isEmpty(), preview.errors().toString());
        assertFalse(preview.committed());
        assertEquals(0, Sample.count("project.id", target.id), "a preview must not write");
        assertEquals(3, preview.newFields().size());

        var report = importer.importDocument(target.id, original, null, true, true);
        assertTrue(report.committed(), report.errors().toString());
        JsonNode copy = exporter.export(target.id, null);
        assertEquals(content(original), content(copy));
    }

    @Test
    void conflictsAreReportedAndNothingIsWritten() {
        Project project = project("Conflicts");
        Sample existing = new Sample();
        existing.project = project;
        existing.name = "S-1";
        existing.persist();
        JsonNode document = IsaJsonImportServiceTest.minimalDocument("S-1", "not-a-date");
        var report = importer.importDocument(project.id, document, null, true, true);
        assertFalse(report.committed());
        assertEquals(1, Sample.count("project.id", project.id));
        assertTrue(report.errors().toString().contains("already exists"));
    }

    @Test
    void newCustomFieldsRequireTheAdminRole() {
        Project project = project("Editor import");
        JsonNode document = IsaJsonImportServiceTest.minimalDocument("S-2", "2021-01-01");
        var report = importer.importDocument(project.id, document, null, true, false);
        assertFalse(report.committed());
        assertTrue(report.errors().toString().contains("ADMIN"));
    }

    /** Everything except generated IDs and the project identity. */
    private static Map<String, String> content(JsonNode export) {
        Map<String, String> result = new TreeMap<>();
        JsonNode study = export.at("/studies/0");
        Map<String, String> names = new TreeMap<>();
        study.get("characteristicCategories").forEach(category ->
                names.put(category.get("@id").asText(), category.get("characteristicType").toString()));
        result.put("categories", new java.util.TreeSet<>(names.values()).toString());
        study.at("/materials/samples").forEach(sample -> {
            var values = new java.util.TreeSet<String>();
            sample.get("characteristics").forEach(characteristic -> values.add(
                    names.get(characteristic.at("/category/@id").asText()) + "=" + characteristic.get("value")));
            result.put("sample " + sample.get("name").asText(), values.toString());
        });
        JsonNode assay = study.at("/assays/0");
        result.put("assay comments", assay.get("comments").toString());
        result.put("platform", assay.get("technologyPlatform").asText());
        var files = new java.util.TreeSet<String>();
        assay.get("dataFiles").forEach(file -> files.add(file.get("name").asText() + file.get("comments")));
        result.put("files", files.toString());
        var parameters = new java.util.TreeSet<String>();
        assay.get("processSequence").forEach(process -> {
            process.path("parameterValues").forEach(value -> parameters.add(value.at("/value/annotationValue").asText()));
            if (process.has("performer")) parameters.add("performer=" + process.get("performer").asText());
        });
        result.put("parameters", parameters.toString());
        return result;
    }

    private Project project(String name) {
        Project project = new Project();
        project.name = name;
        project.owner = UUID.randomUUID();
        project.persist();
        return project;
    }

    private void file(Sample sample, Assay assay, String name, ReadRole role) {
        File.importPending(sample.project.id, assay.id, sample, assay, name, "0123456789abcdef0123456789abcdef", null, role)
                .ifPresent(message -> {
                    throw new AssertionError(message);
                });
    }
}
