package no.metatrack.server.sample;

import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublicSampleControllerTest {
    @Test
    void delegatesRequestedPageAndExplicitlyPermitsAnonymousAccess() throws Exception {
        PublicSampleController controller = new PublicSampleController();
        controller.publicSampleService = mock(PublicSampleService.class);
        PublicSamplePage expected = new PublicSamplePage(List.of(), 2, 100, 200, 2);
        when(controller.publicSampleService.getPage(2)).thenReturn(expected);

        assertSame(expected, controller.getSamples(2));
        verify(controller.publicSampleService).getPage(2);
        Method endpoint = PublicSampleController.class.getMethod("getSamples", int.class);
        assertTrue(endpoint.isAnnotationPresent(PermitAll.class));
        assertEquals("/api/public/samples", PublicSampleController.class.getAnnotation(jakarta.ws.rs.Path.class).value());
    }

    @Test
    void rejectsNegativePage() {
        PublicSampleController controller = new PublicSampleController();
        controller.publicSampleService = mock(PublicSampleService.class);

        assertThrows(BadRequestException.class, () -> controller.getSamples(-1));
        verifyNoInteractions(controller.publicSampleService);
    }
}