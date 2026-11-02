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

    @Test
    void addingMemberDoesNotWriteMembershipDirectly() {
        ProjectController controller = new ProjectController();
        controller.projectRoleCheck = mock(ProjectRoleCheck.class);
        controller.projectService = mock(ProjectService.class);
        controller.invitationCoordinator = mock(no.metatrack.server.invitation.InvitationCoordinator.class);
        when(controller.projectRoleCheck.isAtLeast(1L, ProjectRole.ADMIN)).thenReturn(true);
        UUID recipient = UUID.randomUUID();

        try (var response = controller.addMember(1L, recipient, new AddMemberRequest(ProjectRole.EDITOR))) {
            assertEquals(204, response.getStatus());
        }

        verify(controller.invitationCoordinator).create(1L, recipient, ProjectRole.EDITOR);
        verify(controller.projectService, never()).addMember(anyLong(), any(), any());
    }
}
