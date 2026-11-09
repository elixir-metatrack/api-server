package no.metatrack.server.assay;

import jakarta.ws.rs.NotFoundException;
import no.metatrack.server.project.Project;
import no.metatrack.server.project.ProjectRole;
import no.metatrack.server.project.ProjectRoleCheck;
import no.metatrack.server.sample.metadata.SampleMetadataService;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssayControllerTest {
    @Test
    void createForwardsSequencingFieldsAndReturnsThem() {
        Long projectId = 1L;
        AssayController controller = new AssayController();
        controller.projectRoleCheck = mock(ProjectRoleCheck.class);
        controller.assayService = mock(AssayService.class);
        Assay assay = new Assay();
        assay.sequencingPlatform = "ILLUMINA";
        assay.sequencingLaboratory = "Lab A";

        try (MockedStatic<Project> project = mockStatic(Project.class)) {
            project.when(() -> Project.projectExists(projectId)).thenReturn(true);
            when(controller.projectRoleCheck.isAtLeast(projectId, ProjectRole.EDITOR)).thenReturn(true);
            when(controller.assayService.createAssay(
                    eq(projectId), eq("new"), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                    isNull(), isNull(), isNull(), eq("ILLUMINA"), eq("Lab A"))).thenReturn(assay);

            AssayResponse response = controller.createNewAssay(projectId, new CreateAssayRequest(
                    "new", null, null, null, null, null, null, null, null, null, null, "ILLUMINA", "Lab A"));

            assertEquals("ILLUMINA", response.sequencingPlatform());
            assertEquals("Lab A", response.sequencingLaboratory());
        }
    }

    @Test
    void patchForwardsSequencingFields() {
        Long projectId = 1L;
        UUID assayId = UUID.randomUUID();
        AssayController controller = new AssayController();
        controller.projectRoleCheck = mock(ProjectRoleCheck.class);
        controller.assayService = mock(AssayService.class);

        try (MockedStatic<Project> project = mockStatic(Project.class)) {
            project.when(() -> Project.projectExists(projectId)).thenReturn(true);
            when(controller.projectRoleCheck.isAtLeast(projectId, ProjectRole.EDITOR)).thenReturn(true);

            var response = controller.updateAssay(projectId, assayId, new PatchAssayRequest(
                    null, null, null, null, null, null, null, null, null, null, null, "ONT", "Lab B"));

            assertEquals(204, response.getStatus());
            verify(controller.assayService).updateAssay(
                    eq(projectId), eq(assayId), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                    isNull(), isNull(), isNull(), isNull(), eq("ONT"), eq("Lab B"));
        }
    }

    @Test
    void rejectsAssayFromAnotherProjectBeforeLoadingMetadata() {
        Long projectId = 1L;
        UUID assayId = UUID.randomUUID();
        AssayController controller = new AssayController();
        controller.projectRoleCheck = mock(ProjectRoleCheck.class);
        controller.assayService = mock(AssayService.class);
        controller.metadataService = mock(SampleMetadataService.class);

        try (MockedStatic<Project> project = mockStatic(Project.class)) {
            project.when(() -> Project.projectExists(projectId)).thenReturn(true);
            when(controller.projectRoleCheck.isAtLeast(projectId, ProjectRole.VIEWER)).thenReturn(true);
            when(controller.assayService.getAllSamplesInAssay(projectId, assayId)).thenThrow(new NotFoundException());

            assertThrows(NotFoundException.class, () -> controller.getSamplesInAssay(projectId, assayId));

            verifyNoInteractions(controller.metadataService);
        }
    }
}
