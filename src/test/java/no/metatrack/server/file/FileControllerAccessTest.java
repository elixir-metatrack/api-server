package no.metatrack.server.file;

import io.quarkus.security.Authenticated;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import no.metatrack.server.project.ProjectRole;
import no.metatrack.server.project.ProjectRoleCheck;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FileControllerAccessTest {
    private static final Long PROJECT_ID = 2L;
    private static final UUID FILE_UUID = UUID.fromString("729136e5-71e7-4c24-aad2-19f870e45d20");

    private FileController controller;

    @BeforeEach
    void setUp() {
        controller = new FileController();
        controller.projectRoleCheck = mock(ProjectRoleCheck.class);
        controller.fileIngestService = mock(FileIngestService.class);
    }

    @ParameterizedTest
    @EnumSource(value = ProjectRole.class, names = {"EDITOR", "ADMIN", "OWNER"})
    void editorAdminAndOwnerCanDeleteFile(ProjectRole role) {
        when(controller.projectRoleCheck.isAtLeast(PROJECT_ID, ProjectRole.EDITOR))
                .thenReturn(role == ProjectRole.EDITOR || role == ProjectRole.ADMIN || role == ProjectRole.OWNER);

        try (Response response = controller.deleteFile(PROJECT_ID, FILE_UUID)) {
            assertEquals(204, response.getStatus());
            assertFalse(response.hasEntity());
        }

        InOrder order = inOrder(controller.projectRoleCheck, controller.fileIngestService);
        order.verify(controller.projectRoleCheck).isAtLeast(PROJECT_ID, ProjectRole.EDITOR);
        order.verify(controller.fileIngestService).deleteFile(PROJECT_ID, FILE_UUID);
        verifyNoMoreInteractions(controller.projectRoleCheck, controller.fileIngestService);
    }

    @Test
    void viewerCannotDeleteFile() {
        when(controller.projectRoleCheck.isAtLeast(PROJECT_ID, ProjectRole.EDITOR)).thenReturn(false);

        ForbiddenException exception = assertThrows(ForbiddenException.class,
                () -> controller.deleteFile(PROJECT_ID, FILE_UUID));

        assertEquals(403, exception.getResponse().getStatus());
        verify(controller.projectRoleCheck).isAtLeast(PROJECT_ID, ProjectRole.EDITOR);
        verifyNoInteractions(controller.fileIngestService);
    }

    @Test
    void nonmemberCannotDeleteFile() {
        WebApplicationException denied = new WebApplicationException("User is not a member of project", 403);
        when(controller.projectRoleCheck.isAtLeast(PROJECT_ID, ProjectRole.EDITOR)).thenThrow(denied);

        WebApplicationException exception = assertThrows(WebApplicationException.class,
                () -> controller.deleteFile(PROJECT_ID, FILE_UUID));

        assertSame(denied, exception);
        assertEquals(403, exception.getResponse().getStatus());
        verify(controller.projectRoleCheck).isAtLeast(PROJECT_ID, ProjectRole.EDITOR);
        verifyNoInteractions(controller.fileIngestService);
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 409, 502})
    void propagatesServiceErrors(int status) {
        when(controller.projectRoleCheck.isAtLeast(PROJECT_ID, ProjectRole.EDITOR)).thenReturn(true);
        WebApplicationException failure = new WebApplicationException("File deletion failed", status);
        doThrow(failure).when(controller.fileIngestService).deleteFile(PROJECT_ID, FILE_UUID);

        WebApplicationException exception = assertThrows(WebApplicationException.class,
                () -> controller.deleteFile(PROJECT_ID, FILE_UUID));

        assertSame(failure, exception);
        assertEquals(status, exception.getResponse().getStatus());
        InOrder order = inOrder(controller.projectRoleCheck, controller.fileIngestService);
        order.verify(controller.projectRoleCheck).isAtLeast(PROJECT_ID, ProjectRole.EDITOR);
        order.verify(controller.fileIngestService).deleteFile(PROJECT_ID, FILE_UUID);
        verifyNoMoreInteractions(controller.projectRoleCheck, controller.fileIngestService);
    }

    @Test
    void deleteFileDeclaresAuthenticatedProjectScopedRouteAndResponses() throws NoSuchMethodException {
        Method method = FileController.class.getMethod("deleteFile", Long.class, UUID.class);

        assertEquals("/api/projects/{projectId}/files", FileController.class.getAnnotation(Path.class).value());
        assertTrue(method.isAnnotationPresent(DELETE.class));
        assertTrue(method.isAnnotationPresent(Authenticated.class));
        assertEquals("/{fileUuid}", method.getAnnotation(Path.class).value());
        assertEquals("projectId", method.getParameters()[0].getAnnotation(PathParam.class).value());
        assertEquals("fileUuid", method.getParameters()[1].getAnnotation(PathParam.class).value());
        assertEquals(Response.class, method.getReturnType());
        assertNotNull(method.getAnnotation(Operation.class));
        APIResponses responses = method.getAnnotation(APIResponses.class);
        assertNotNull(responses);
        assertEquals(Set.of("204", "401", "403", "404", "409", "502"),
                Arrays.stream(responses.value()).map(APIResponse::responseCode).collect(Collectors.toSet()));
    }
}