package no.metatrack.server.sample;

import jakarta.ws.rs.ForbiddenException;
import no.metatrack.server.file.FileIngestService;
import no.metatrack.server.project.Project;
import no.metatrack.server.project.ProjectRoleCheck;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class SampleControllerAccessTest {
    @Test
    void viewerCannotPatchCreateDeleteOrImportSamples() {
        SampleController controller = new SampleController();
        controller.projectRoleCheck = mock(ProjectRoleCheck.class);
        controller.sampleService = mock(SampleService.class);
        controller.fileIngestService = mock(FileIngestService.class);
        controller.csvSampleSheetImportService = mock(CSVSampleSheetImportService.class);
        UUID sampleId = UUID.randomUUID();

        try (MockedStatic<Project> projects = mockStatic(Project.class)) {
            projects.when(() -> Project.projectExists(2L)).thenReturn(true);
            assertThrows(ForbiddenException.class, () -> controller.updateSample(2L, sampleId, null));
            assertThrows(ForbiddenException.class, () -> controller.createSample(2L, null));
            assertThrows(ForbiddenException.class, () -> controller.deleteSample(2L, sampleId));
            assertThrows(ForbiddenException.class, () -> controller.batchUpdateSamples(2L, null));
            assertThrows(ForbiddenException.class, () -> controller.importCSV(2L, null));
            assertThrows(ForbiddenException.class, () -> controller.deleteFile(2L, sampleId, UUID.randomUUID()));
            verifyNoInteractions(controller.sampleService, controller.fileIngestService, controller.csvSampleSheetImportService);
        }
    }
}
