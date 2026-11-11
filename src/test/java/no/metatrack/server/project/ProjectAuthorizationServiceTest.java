package no.metatrack.server.project;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.WebApplicationException;
import no.metatrack.server.auth.CurrentUser;
import no.metatrack.server.auth.UserService;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class ProjectAuthorizationServiceTest {
    private static final Long PROJECT_ID = 10L;
    private static final UUID USER_ID = UUID.randomUUID();

    private final ProjectAuthorizationService service = new ProjectAuthorizationService();

    @Test
    void enforcesExplicitProjectRoleThresholds() {
        ProjectMember member = member(ProjectRole.ADMIN);
        try (MockedStatic<Project> projects = mockStatic(Project.class);
             MockedStatic<ProjectMember> members = mockStatic(ProjectMember.class)) {
            projects.when(() -> Project.projectExists(PROJECT_ID)).thenReturn(true);
            members.when(() -> ProjectMember.findMemberInProjectOptional(USER_ID, PROJECT_ID))
                    .thenReturn(Optional.of(member));

            assertTrue(service.isAtLeast(USER_ID, PROJECT_ID, ProjectRole.EDITOR));
            assertTrue(service.isAtLeast(USER_ID, PROJECT_ID, ProjectRole.ADMIN));
            assertFalse(service.isAtLeast(USER_ID, PROJECT_ID, ProjectRole.OWNER));
        }
    }

    @Test
    void systemAdminHasOwnerAccessOnlyForTheAuthenticatedUserId() {
        UserService userService = mock(UserService.class);
        service.userService = userService;
        when(userService.requireCurrentUser()).thenReturn(
                new CurrentUser(USER_ID.toString(), "admin", Set.of("system-admin"), null, null, null));

        try (MockedStatic<Project> projects = mockStatic(Project.class);
             MockedStatic<ProjectMember> members = mockStatic(ProjectMember.class)) {
            projects.when(() -> Project.projectExists(PROJECT_ID)).thenReturn(true);
            members.when(() -> ProjectMember.findMemberInProjectOptional(USER_ID, PROJECT_ID))
                    .thenReturn(Optional.empty());

            assertTrue(service.isAtLeast(USER_ID, PROJECT_ID, ProjectRole.OWNER));
            assertTrue(service.hasAtLeast(USER_ID, PROJECT_ID, ProjectRole.VIEWER));
            assertFalse(service.hasAtLeast(UUID.randomUUID(), PROJECT_ID, ProjectRole.VIEWER));
            assertThrows(WebApplicationException.class,
                    () -> service.isAtLeast(UUID.randomUUID(), PROJECT_ID, ProjectRole.VIEWER));
        }
    }

    @Test
    void uploadFromSubProjectRequiresEditorInBothProjects() {
        Project parent = new Project();
        parent.id = PROJECT_ID;
        Project subProject = new Project();
        subProject.id = 11L;
        subProject.parentProject = parent;
        ProjectMember childMembership = member(ProjectRole.EDITOR);
        ProjectMember parentMembership = member(ProjectRole.VIEWER);

        try (MockedStatic<PanacheEntityBase> persistence = mockStatic(PanacheEntityBase.class);
             MockedStatic<Project> projects = mockStatic(Project.class);
             MockedStatic<ProjectMember> members = mockStatic(ProjectMember.class)) {
            persistence.when(() -> Project.findByIdOptional(subProject.id))
                    .thenReturn(Optional.of(subProject));
            projects.when(() -> Project.projectExists(subProject.id)).thenReturn(true);
            projects.when(() -> Project.projectExists(parent.id)).thenReturn(true);
            members.when(() -> ProjectMember.findMemberInProjectOptional(USER_ID, subProject.id))
                    .thenReturn(Optional.of(childMembership));
            members.when(() -> ProjectMember.findMemberInProjectOptional(USER_ID, parent.id))
                    .thenReturn(Optional.of(parentMembership));

            assertThrows(ForbiddenException.class, () -> service.requireUploadRights(USER_ID, subProject.id));
        }
    }

    private ProjectMember member(ProjectRole role) {
        ProjectMember member = new ProjectMember();
        member.memberId = USER_ID;
        member.role = role;
        return member;
    }
}