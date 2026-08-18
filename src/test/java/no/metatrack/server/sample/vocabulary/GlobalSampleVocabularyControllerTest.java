package no.metatrack.server.sample.vocabulary;

import io.quarkus.security.Authenticated;
import jakarta.annotation.security.RolesAllowed;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GlobalSampleVocabularyControllerTest {
    @Test
    void exposesReadsToAuthenticatedUsers() {
        GlobalSampleVocabularyController controller = new GlobalSampleVocabularyController();
        controller.vocabularyService = mock(GlobalSampleVocabularyManagementService.class);
        when(controller.vocabularyService.list()).thenReturn(List.of());

        assertTrue(GlobalSampleVocabularyController.class.isAnnotationPresent(Authenticated.class));
        assertFalse(method("list").isAnnotationPresent(RolesAllowed.class));
        assertFalse(method("get", String.class).isAnnotationPresent(RolesAllowed.class));
        assertEquals(List.of(), controller.list());
        verify(controller.vocabularyService).list();
    }

    @Test
    void restrictsMutationsToSystemAdmins() {
        assertArrayEquals(new String[]{"system-admin"}, method(
                "replace", String.class, PutSampleVocabularyRequest.class).getAnnotation(RolesAllowed.class).value());
        assertArrayEquals(new String[]{"system-admin"}, method(
                "delete", String.class).getAnnotation(RolesAllowed.class).value());
    }

    private Method method(String name, Class<?>... parameterTypes) {
        try {
            return GlobalSampleVocabularyController.class.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException exception) {
            throw new AssertionError(exception);
        }
    }
}