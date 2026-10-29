package no.metatrack.server.spreadsheet;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import no.metatrack.server.project.Project;
import no.metatrack.server.project.ProjectRole;
import no.metatrack.server.project.ProjectRoleCheck;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpreadsheetExportControllerTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void requiresProjectViewerBeforeExporting(boolean experiment) {
        var controller = controller();
        var request = new SpreadsheetExportRequest(List.of("sample"));
        try (var project = mockStatic(Project.class)) {
            assertThrows(NotFoundException.class, () -> invoke(controller, experiment, request));
            project.when(() -> Project.projectExists(1L)).thenReturn(true);
            assertThrows(ForbiddenException.class, () -> invoke(controller, experiment, request));
            verifyNoInteractions(controller.exports);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void validatesSelectionAndReturnsPrivateWorkbookForViewer(boolean experiment) {
        var controller = controller();
        byte[] bytes = {1, 2, 3};
        when(controller.roleCheck.isAtLeast(1L, ProjectRole.VIEWER)).thenReturn(true);
        when(controller.exports.samples(eq(1L), anyList())).thenReturn(bytes);
        when(controller.exports.experiments(eq(1L), any(), anyList())).thenReturn(bytes);
        try (var project = mockStatic(Project.class)) {
            project.when(() -> Project.projectExists(1L)).thenReturn(true);
            assertThrows(BadRequestException.class, () -> invoke(controller, experiment, null));
            assertThrows(BadRequestException.class, () -> invoke(controller, experiment, new SpreadsheetExportRequest(List.of())));
            assertThrows(BadRequestException.class, () -> invoke(controller, experiment,
                    new SpreadsheetExportRequest(List.of("sample", "sample"))));
            verifyNoInteractions(controller.exports);
            try (var response = invoke(controller, experiment, new SpreadsheetExportRequest(List.of("sample")))) {
                assertEquals(200, response.getStatus());
                assertArrayEquals(bytes, (byte[]) response.getEntity());
                assertEquals(SpreadsheetTemplateController.XLSX_TYPE, response.getMediaType().toString());
                assertEquals("no-store", response.getHeaderString("Cache-Control"));
                assertTrue(response.getHeaderString("Content-Disposition").endsWith(".xlsx\""));
            }
        }
    }

    private jakarta.ws.rs.core.Response invoke(SpreadsheetExportController controller, boolean experiment,
            SpreadsheetExportRequest request) {
        return experiment ? controller.experiments(1L, UUID.randomUUID(), request) : controller.samples(1L, request);
    }

    private SpreadsheetExportController controller() {
        var controller = new SpreadsheetExportController();
        controller.roleCheck = mock(ProjectRoleCheck.class);
        controller.exports = mock(SpreadsheetExportService.class);
        return controller;
    }
}
