package no.metatrack.server.admin;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SystemAdminControllerTest {
    @Test
    void administrativeEndpointsAreRestrictedToSystemAdmins() {
        assertArrayEquals(new String[]{"system-admin"},
                SystemAdminController.class.getAnnotation(RolesAllowed.class).value());
    }

    @Test
    void rejectsPageOffsetsThatWouldOverflow() {
        SystemAdminController controller = new SystemAdminController();

        assertThrows(BadRequestException.class, () -> controller.projects(Integer.MAX_VALUE, 100));
    }
}