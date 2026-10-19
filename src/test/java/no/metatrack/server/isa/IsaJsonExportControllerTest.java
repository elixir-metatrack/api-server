package no.metatrack.server.isa;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.ws.rs.ForbiddenException;
import no.metatrack.server.project.ProjectRole;
import no.metatrack.server.project.ProjectRoleCheck;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IsaJsonExportControllerTest {
    @Test
    void rejectsUnauthorizedExportBeforeLoadingAnyMetadata() {
        IsaJsonExportController controller = controller();
        assertThrows(ForbiddenException.class, () -> controller.export(12L));
        verifyNoInteractions(controller.exportService);
    }

    @Test
    void viewerCanDownloadANonCacheableJsonAttachment() {
        IsaJsonExportController controller = controller();
        when(controller.projectRoleCheck.isAtLeast(12L, ProjectRole.VIEWER)).thenReturn(true);
        when(controller.exportService.export(12L)).thenReturn(JsonNodeFactory.instance.objectNode().put("title", "Demo"));
        try (var response = controller.export(12L)) {
            assertEquals(200, response.getStatus());
            assertEquals("private, no-store", response.getHeaderString("Cache-Control"));
            assertEquals("attachment; filename=\"project-12-isa.json\"", response.getHeaderString("Content-Disposition"));
        }
    }

    private IsaJsonExportController controller() {
        IsaJsonExportController controller = new IsaJsonExportController();
        controller.exportService = mock(IsaJsonExportService.class);
        controller.projectRoleCheck = mock(ProjectRoleCheck.class);
        return controller;
    }
}
