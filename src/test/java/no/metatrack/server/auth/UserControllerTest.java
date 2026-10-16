package no.metatrack.server.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
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
        controller.updateProfile(new ObjectMapper().readTree("{}"));
        verify(controller.profileService).getProfile(user);
        verify(controller.profileService).updateProfile(eq(user), eq(new UserProfilePatch(java.util.Map.of())));
        assertThrows(WebApplicationException.class, () -> controller.updateProfile(new ObjectMapper().readTree("{\"userId\":\"other\"}")));
        verifyNoMoreInteractions(controller.profileService);
    }
}