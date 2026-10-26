package no.metatrack.server.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserControllerTest {
    @Test
    void authenticatesBothMethodsBeforeCallingProfileService() {
        var controller = new UserController();
        controller.userService = mock(UserService.class);
        controller.profileService = mock(UserProfileService.class);
        when(controller.userService.requireCurrentUser()).thenThrow(new WebApplicationException(401));
        assertThrows(WebApplicationException.class, controller::getUserInfo);
        assertThrows(WebApplicationException.class, () -> controller.updateProfile(null));
        verifyNoInteractions(controller.profileService);
    }

    @Test
    void delegatesOnlyAuthenticatedSubjectAndRejectsInjectedTarget() throws Exception {
        var controller = new UserController();
        controller.userService = mock(UserService.class);
        controller.profileService = mock(UserProfileService.class);
        var user = new CurrentUser("subject", "name", Set.of(), null, null, null);
        when(controller.userService.requireCurrentUser()).thenReturn(user);
        controller.getUserInfo();
        controller.updateProfile(new ObjectMapper().readValue("{}", UserProfileRequest.class));
        verify(controller.profileService).getProfile(user);
        verify(controller.profileService).updateProfile(eq(user), eq(new UserProfilePatch(java.util.Map.of())));
        var invalid = new ObjectMapper().readValue("{\"userId\":\"other\"}", UserProfileRequest.class);
        assertThrows(WebApplicationException.class, () -> controller.updateProfile(invalid));
        verifyNoMoreInteractions(controller.profileService);
    }

    @Test
    void publishesTypedRequestSchemaForProfileFields() throws Exception {
        var endpoint = UserController.class.getMethod("updateProfile", UserProfileRequest.class);
        var requestBody = endpoint.getAnnotation(RequestBody.class);
        assertNotNull(requestBody);
        assertEquals(UserProfileRequest.class, requestBody.content()[0].schema().implementation());
        for (String getter : new String[]{"getFirstName", "getLastName", "getCountry", "getInstitution", "getOrcid"}) {
            assertEquals(String.class, UserProfileRequest.class.getMethod(getter).getReturnType());
        }
    }
}