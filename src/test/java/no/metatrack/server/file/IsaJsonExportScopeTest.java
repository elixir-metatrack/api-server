package no.metatrack.server.file;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import no.metatrack.server.assay.Assay;
import no.metatrack.server.isa.IsaJsonExportService;
import no.metatrack.server.project.Project;
import no.metatrack.server.sample.Sample;
import no.metatrack.server.sample.metadata.CreateSampleMetadataFieldRequest;
import no.metatrack.server.sample.metadata.SampleMetadataFieldService;
import no.metatrack.server.sample.metadata.SampleMetadataFieldType;
import no.metatrack.server.sample.metadata.SampleMetadataService;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@QuarkusTestResource(value = SubProjectDatabase.class, restrictToAnnotatedClass = true)
@TestProfile(SubProjectTestProfile.class)
@TestTransaction
class IsaJsonExportScopeTest {
    @Inject IsaJsonExportService exporter;
    @Inject SampleMetadataFieldService fields;
    @Inject SampleMetadataService metadata;

    @Test
    void subProjectContainsOnlyLinkedSamplesAndTheirFilesWithInheritedCustomFields() {
        Project root = project("Parent private metadata");
        Project child = project("Shared subset");
        child.parentProject = root;
        Sample visible = sample(root, "VISIBLE");
        Sample hidden = sample(root, "HIDDEN");
        child.linkedSamples.add(visible);
        Assay assay = new Assay();
        assay.project = root;
        assay.name = "Shared assay";
        assay.addSample(visible);
        assay.addSample(hidden);
        assay.persistAndFlush();
        file(visible, assay, "visible.fastq.gz");
        file(hidden, assay, "hidden.fastq.gz");
        fields.create(root.id, new CreateSampleMetadataFieldRequest("group", "Treatment group", SampleMetadataFieldType.TEXT, null));
        metadata.apply(root.id, visible, Map.of("group", "control"));
        metadata.apply(root.id, hidden, Map.of("group", "private"));

        var subset = exporter.export(child.id);
        assertEquals(1, subset.at("/studies/0/materials/samples").size());
        assertEquals(1, subset.at("/studies/0/assays/0/materials/samples").size());
        assertEquals(1, subset.at("/studies/0/assays/0/dataFiles").size());
        assertTrue(subset.toString().contains("control"));
        assertFalse(subset.toString().contains("HIDDEN"));
        assertFalse(subset.toString().contains("hidden.fastq"));
        assertFalse(subset.toString().contains("Parent private metadata"));
        var full = exporter.export(root.id);
        assertEquals(2, full.at("/studies/0/materials/samples").size());
        assertEquals(2, full.at("/studies/0/assays/0/dataFiles").size());
    }

    private Project project(String name) {
        Project project = new Project();
        project.name = name;
        project.owner = UUID.randomUUID();
        project.persist();
        return project;
    }

    private Sample sample(Project project, String name) {
        Sample sample = new Sample();
        sample.project = project;
        sample.name = name;
        sample.persist();
        return sample;
    }

    private void file(Sample sample, Assay assay, String name) {
        File file = new File();
        file.uuid = UUID.randomUUID();
        file.sample = sample;
        file.assay = assay;
        file.fileName = name;
        file.objectKey = "internal/" + name;
        file.virtualPath = "private/" + name;
        file.status = UploadStatus.PENDING;
        file.persist();
    }
}
