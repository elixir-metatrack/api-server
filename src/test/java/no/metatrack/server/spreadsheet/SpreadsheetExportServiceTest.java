package no.metatrack.server.spreadsheet;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import jakarta.ws.rs.NotFoundException;
import no.metatrack.server.assay.Assay;
import no.metatrack.server.file.File;
import no.metatrack.server.sample.Sample;
import no.metatrack.server.sample.metadata.SampleMetadataFieldService;
import no.metatrack.server.sample.metadata.SampleMetadataService;
import no.metatrack.server.sample.vocabulary.SampleVocabularyManagementService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpreadsheetExportServiceTest {
    @Test
    void loadsOnlySelectedProjectSamplesAndPreservesRequestedOrder() {
        var service = service();
        Sample first = sample("first");
        Sample second = sample("second");
        List<String> names = List.of("second", "first");
        when(service.metadata.getActiveMetadata(List.of(second, first))).thenReturn(Map.of());
        try (var entities = mockStatic(PanacheEntityBase.class)) {
            entities.when(() -> Sample.list("project.id = ?1 and name in ?2", 42L, names))
                    .thenReturn(List.of(first, second));
            service.samples(42L, names);
            verify(service.writer).samples(eq(List.of(second, first)), anyList(), eq(Map.of()), anyList());
            verify(service.fields).list(42L, false);
            verify(service.vocabularies).list(42L);
        }
    }

    @Test
    void refusesUnavailableSamplesWithoutExportingAPartialSelection() {
        var service = service();
        List<String> names = List.of("present", "outside-project");
        try (var entities = mockStatic(PanacheEntityBase.class)) {
            entities.when(() -> Sample.list("project.id = ?1 and name in ?2", 42L, names))
                    .thenReturn(List.of(sample("present")));
            assertThrows(NotFoundException.class, () -> service.samples(42L, names));
            verifyNoInteractions(service.writer, service.metadata, service.fields, service.vocabularies);
        }
    }

    @Test
    void requiresAssayInProjectAndSelectedSamplesLinkedToIt() {
        var service = service();
        Assay assay = new Assay();
        assay.id = UUID.randomUUID();
        List<String> names = List.of("sample");
        PanacheQuery<Assay> query = mock(PanacheQuery.class);
        when(query.firstResultOptional()).thenReturn(Optional.empty());
        try (var entities = mockStatic(PanacheEntityBase.class)) {
            entities.when(() -> Assay.find("id = ?1 and project.id = ?2", assay.id, 42L)).thenReturn(query);
            assertThrows(NotFoundException.class, () -> service.experiments(42L, assay.id, names));
            when(query.firstResultOptional()).thenReturn(Optional.of(assay));
            entities.when(() -> Sample.list(
                    "select s from Sample s join s.assays a where s.project.id = ?1 and a.id = ?2 and s.name in ?3",
                    42L, assay.id, names)).thenReturn(List.of());
            assertThrows(NotFoundException.class, () -> service.experiments(42L, assay.id, names));
            verifyNoInteractions(service.writer);
            Sample sample = sample("sample");
            entities.when(() -> Sample.list(
                    "select s from Sample s join s.assays a where s.project.id = ?1 and a.id = ?2 and s.name in ?3",
                    42L, assay.id, names)).thenReturn(List.of(sample));
            entities.when(() -> File.list(
                    "sample.project.id = ?1 and assay.project.id = ?1 and assay.id = ?2 and sample.name in ?3 order by fileName",
                    42L, assay.id, names)).thenReturn(List.of());
            service.experiments(42L, assay.id, names);
            verify(service.writer).experiments(assay, List.of(sample), List.of());
        }
    }

    private Sample sample(String name) {
        Sample sample = new Sample();
        sample.id = UUID.randomUUID();
        sample.name = name;
        return sample;
    }

    private SpreadsheetExportService service() {
        var service = new SpreadsheetExportService();
        service.fields = mock(SampleMetadataFieldService.class);
        service.metadata = mock(SampleMetadataService.class);
        service.vocabularies = mock(SampleVocabularyManagementService.class);
        service.writer = mock(SpreadsheetExportWriter.class);
        return service;
    }
}
