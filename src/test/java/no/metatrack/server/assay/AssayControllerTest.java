package no.metatrack.server.assay;

import jakarta.ws.rs.NotFoundException;
import no.metatrack.server.project.Project;
import no.metatrack.server.project.ProjectRole;
import no.metatrack.server.project.ProjectRoleCheck;
import no.metatrack.server.sample.metadata.SampleMetadataService;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class AssayControllerTest {
    @Test
    void rejectsAssayFromAnotherProjectBeforeLoadingMetadata() {
        Long projectId = 1L;
        UUID assayId = UUID.randomUUID();
        AssayController controller = new AssayController();
        controller.projectRoleCheck = mock(ProjectRoleCheck.class);
        controller.assayService = mock(AssayService.class);
        controller.metadataService = mock(SampleMetadataService.class);

        try (MockedStatic<Project> project = mockStatic(Project.class);
             MockedStatic<Assay> assay = mockStatic(Assay.class)) {
            project.when(() -> Project.projectExists(projectId)).thenReturn(true);
            when(controller.projectRoleCheck.isAtLeast(projectId, ProjectRole.VIEWER)).thenReturn(true);
            assay.when(() -> Assay.existsAssayByIdInProjectOptional(projectId, assayId)).thenReturn(false);

            assertThrows(NotFoundException.class, () -> controller.getSamplesInAssay(projectId, assayId));

            verifyNoInteractions(controller.assayService, controller.metadataService);
        }
    }
}