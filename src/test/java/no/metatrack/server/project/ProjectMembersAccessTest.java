package no.metatrack.server.project;

import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProjectMembersAccessTest {
    @Test
    void nonMembersCannotLoadMemberIdentities() {
        ProjectController controller = new ProjectController();
        controller.projectRoleCheck = mock(ProjectRoleCheck.class);
        controller.projectMemberService = mock(ProjectMemberService.class);
        assertEquals(403, assertThrows(WebApplicationException.class,
                () -> controller.getAllProjectMembers(1L)).getResponse().getStatus());
        verifyNoInteractions(controller.projectMemberService);
    }

    @Test
    void viewerReceivesUsernameAndEmail() {
        ProjectController controller = new ProjectController();
        controller.projectRoleCheck = mock(ProjectRoleCheck.class);
        controller.projectMemberService = mock(ProjectMemberService.class);
        when(controller.projectRoleCheck.isAtLeast(1L, ProjectRole.VIEWER)).thenReturn(true);
        ProjectMemberResponse member = new ProjectMemberResponse(UUID.randomUUID(), "scientist", "scientist@example.test", ProjectRole.VIEWER);
        when(controller.projectMemberService.listAllProjectMembers(1L)).thenReturn(List.of(member));
        assertEquals(List.of(member), controller.getAllProjectMembers(1L));
    }
}
