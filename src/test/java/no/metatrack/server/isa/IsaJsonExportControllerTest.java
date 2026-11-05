package no.metatrack.server.isa;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import no.metatrack.server.project.ProjectRole;
import no.metatrack.server.project.ProjectRoleCheck;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

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
        when(controller.exportService.export(12L, null)).thenReturn(JsonNodeFactory.instance.objectNode().put("title", "Demo"));
        try (var response = controller.export(12L)) {
            assertEquals(200, response.getStatus());
            assertEquals("private, no-store", response.getHeaderString("Cache-Control"));
            assertEquals("attachment; filename=\"project-12-isa.json\"", response.getHeaderString("Content-Disposition"));
        }
    }

    @Test
    void selectionExportsOnlyTheNamedSamples() {
        IsaJsonExportController controller = controller();
        when(controller.projectRoleCheck.isAtLeast(12L, ProjectRole.VIEWER)).thenReturn(true);
        when(controller.exportService.export(12L, Set.of("S-1", "S-2")))
                .thenReturn(JsonNodeFactory.instance.objectNode().put("title", "Demo"));
        try (var response = controller.exportSelection(12L, new IsaJsonExportRequest(List.of("S-1", "S-2")))) {
            assertEquals(200, response.getStatus());
            assertEquals("attachment; filename=\"project-12-selection-isa.json\"",
                    response.getHeaderString("Content-Disposition"));
        }
    }

    @Test
    void selectionRejectsEmptyOrDuplicateNamesAndUnauthorizedCallers() {
        IsaJsonExportController controller = controller();
        assertThrows(ForbiddenException.class,
                () -> controller.exportSelection(12L, new IsaJsonExportRequest(List.of("S-1"))));
        when(controller.projectRoleCheck.isAtLeast(12L, ProjectRole.VIEWER)).thenReturn(true);
        assertThrows(BadRequestException.class, () -> controller.exportSelection(12L, null));
        assertThrows(BadRequestException.class,
                () -> controller.exportSelection(12L, new IsaJsonExportRequest(List.of())));
        assertThrows(BadRequestException.class,
                () -> controller.exportSelection(12L, new IsaJsonExportRequest(List.of("S-1", "S-1"))));
        verifyNoInteractions(controller.exportService);
    }

    private IsaJsonExportController controller() {
        IsaJsonExportController controller = new IsaJsonExportController();
        controller.exportService = mock(IsaJsonExportService.class);
        controller.projectRoleCheck = mock(ProjectRoleCheck.class);
        return controller;
    }
}
