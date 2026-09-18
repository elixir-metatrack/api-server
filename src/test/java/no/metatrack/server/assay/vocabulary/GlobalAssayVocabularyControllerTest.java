package no.metatrack.server.assay.vocabulary;

import io.quarkus.security.Authenticated;
import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GlobalAssayVocabularyControllerTest {
    private final GlobalAssayVocabularyController controller = new GlobalAssayVocabularyController();
    private final GlobalAssayVocabularyManagementService service = mock(GlobalAssayVocabularyManagementService.class);

    GlobalAssayVocabularyControllerTest() {
        controller.vocabularyService = service;
    }

    @Test
    void exposesReadsToAuthenticatedUsers() {
        AssayVocabularyResponse response = response();
        when(service.list()).thenReturn(List.of(response));
        when(service.get("library_layout")).thenReturn(response);

        assertTrue(GlobalAssayVocabularyController.class.isAnnotationPresent(Authenticated.class));
        assertFalse(method("list").isAnnotationPresent(RolesAllowed.class));
        assertFalse(method("get", String.class).isAnnotationPresent(RolesAllowed.class));
        assertEquals(List.of(response), controller.list());
        assertSame(response, controller.get("library_layout"));
        verify(service).list();
        verify(service).get("library_layout");
    }

    @Test
    void restrictsMutationsToSystemAdmins() {
        assertArrayEquals(new String[]{"system-admin"}, method(
                "replace", String.class, PutAssayVocabularyRequest.class).getAnnotation(RolesAllowed.class).value());
        assertArrayEquals(new String[]{"system-admin"}, method(
                "delete", String.class).getAnnotation(RolesAllowed.class).value());
    }

    @Test
    void declaresExpectedRoutesAndRequestValidation() {
        assertEquals("/api/assay-vocabularies", GlobalAssayVocabularyController.class.getAnnotation(Path.class).value());
        assertTrue(method("list").isAnnotationPresent(GET.class));
        assertTrue(method("get", String.class).isAnnotationPresent(GET.class));
        Method replace = method("replace", String.class, PutAssayVocabularyRequest.class);
        assertTrue(replace.isAnnotationPresent(PUT.class));
        assertTrue(replace.getParameters()[1].isAnnotationPresent(Valid.class));
        assertTrue(method("delete", String.class).isAnnotationPresent(DELETE.class));
        for (Method method : List.of(method("get", String.class), replace, method("delete", String.class))) {
            assertEquals("/{fieldKey}", method.getAnnotation(Path.class).value());
            assertEquals("fieldKey", method.getParameters()[0].getAnnotation(PathParam.class).value());
        }
    }

    @Test
    void returnsReplacementAndNoContentOnDeletion() {
        PutAssayVocabularyRequest request = new PutAssayVocabularyRequest(List.of("paired"));
        AssayVocabularyResponse response = response();
        when(service.replace("library_layout", request)).thenReturn(response);

        assertSame(response, controller.replace("library_layout", request));
        try (Response deleted = controller.delete("library_layout")) {
            assertEquals(204, deleted.getStatus());
            assertFalse(deleted.hasEntity());
        }
        verify(service).replace("library_layout", request);
        verify(service).delete("library_layout");
    }

    @Test
    void preservesServiceErrorSemantics() {
        when(service.get("unknown")).thenThrow(new BadRequestException());
        when(service.get("library_layout")).thenThrow(new NotFoundException());
        PutAssayVocabularyRequest request = new PutAssayVocabularyRequest(List.of());
        when(service.replace("library_layout", request)).thenThrow(new BadRequestException());
        doThrow(new NotFoundException()).when(service).delete("library_layout");

        assertEquals(400, assertThrows(BadRequestException.class, () -> controller.get("unknown")).getResponse().getStatus());
        assertEquals(404, assertThrows(NotFoundException.class, () -> controller.get("library_layout")).getResponse().getStatus());
        assertEquals(400, assertThrows(BadRequestException.class,
                () -> controller.replace("library_layout", request)).getResponse().getStatus());
        assertEquals(404, assertThrows(NotFoundException.class,
                () -> controller.delete("library_layout")).getResponse().getStatus());
    }

    private static AssayVocabularyResponse response() {
        return AssayVocabularyResponse.eligible(AssayVocabularyBuiltInCatalog.find("library_layout").orElseThrow());
    }

    private static Method method(String name, Class<?>... parameterTypes) {
        try {
            return GlobalAssayVocabularyController.class.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException exception) {
            throw new AssertionError(exception);
        }
    }
}