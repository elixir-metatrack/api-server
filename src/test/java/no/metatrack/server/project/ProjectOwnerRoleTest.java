package no.metatrack.server.project;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.LockModeType;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import no.metatrack.server.auth.keycloak.IdentityLookupService;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProjectOwnerRoleTest {
    private final Long projectId = 1L;
    private final UUID memberId = UUID.randomUUID();
    private final Project project = new Project();
    private final ProjectMember member = new ProjectMember();
    private final ProjectService service = new ProjectService(mock(IdentityLookupService.class));

    ProjectOwnerRoleTest() {
        project.id = projectId;
        member.memberId = memberId;
        member.project = project;
        project.projectMembers.add(member);
        service.projectRoleCheck = mock(ProjectRoleCheck.class);
    }

    @Test
    void adminCannotPromoteThemselvesToOwner() {
        member.role = ProjectRole.ADMIN;
        withMembership(2, () -> {
            assertThrows(ForbiddenException.class, () -> service.updateMemberRole(projectId, memberId, ProjectRole.OWNER));
            assertEquals(ProjectRole.ADMIN, member.role);
        });
    }

    @Test
    void adminCannotDemoteOrRemoveAnyOwner() {
        member.role = ProjectRole.OWNER;
        withMembership(2, () -> {
            assertThrows(ForbiddenException.class, () -> service.updateMemberRole(projectId, memberId, ProjectRole.VIEWER));
            assertThrows(ForbiddenException.class, () -> service.removeMember(projectId, memberId));
            assertEquals(ProjectRole.OWNER, member.role);
            assertTrue(project.projectMembers.contains(member));
        });
    }

    @Test
    void evenAnOwnerCannotDemoteOrRemoveTheLastOwner() {
        member.role = ProjectRole.OWNER;
        when(service.projectRoleCheck.isAtLeast(projectId, ProjectRole.OWNER)).thenReturn(true);
        withMembership(1, () -> {
            assertThrows(BadRequestException.class, () -> service.updateMemberRole(projectId, memberId, ProjectRole.ADMIN));
            assertThrows(BadRequestException.class, () -> service.removeMember(projectId, memberId));
            assertEquals(ProjectRole.OWNER, member.role);
            assertTrue(project.projectMembers.contains(member));
        });
    }

    @Test
    void ownerCanTransferOwnershipWhenAnotherOwnerRemains() {
        member.role = ProjectRole.OWNER;
        when(service.projectRoleCheck.isAtLeast(projectId, ProjectRole.OWNER)).thenReturn(true);
        withMembership(2, () -> {
            service.updateMemberRole(projectId, memberId, ProjectRole.ADMIN);
            assertEquals(ProjectRole.ADMIN, member.role);
            service.updateMemberRole(projectId, memberId, ProjectRole.OWNER);
            assertEquals(ProjectRole.OWNER, member.role);
            service.removeMember(projectId, memberId);
            assertFalse(project.projectMembers.contains(member));
        });
    }

    @Test
    void retainingTheLastOwnerRoleIsANoOp() {
        member.role = ProjectRole.OWNER;
        when(service.projectRoleCheck.isAtLeast(projectId, ProjectRole.OWNER)).thenReturn(true);
        withMembership(1, () -> service.updateMemberRole(projectId, memberId, ProjectRole.OWNER));
        assertEquals(ProjectRole.OWNER, member.role);
    }

    @Test
    void addingMembersIncludingApprovedJoinRequestsCannotGrantOwnerAsAdmin() {
        withMembership(1, () -> {
            assertThrows(ForbiddenException.class, () -> service.addMember(projectId, UUID.randomUUID(), ProjectRole.OWNER));
            assertEquals(1, project.projectMembers.size());
        });
    }

    @Test
    void ordinaryRoleManagementAndNullValidationRemainSupported() {
        member.role = ProjectRole.VIEWER;
        withMembership(1, () -> {
            service.updateMemberRole(projectId, memberId, ProjectRole.EDITOR);
            assertEquals(ProjectRole.EDITOR, member.role);
            assertThrows(BadRequestException.class, () -> service.updateMemberRole(projectId, memberId, null));
            assertThrows(BadRequestException.class, () -> service.addMember(projectId, UUID.randomUUID(), null));
            service.removeMember(projectId, memberId);
            assertFalse(project.projectMembers.contains(member));
            verifyNoInteractions(service.projectRoleCheck);
        });
    }

    private void withMembership(long ownerCount, Runnable assertions) {
        try (MockedStatic<PanacheEntityBase> persistence = mockStatic(PanacheEntityBase.class);
             MockedStatic<ProjectMember> members = mockStatic(ProjectMember.class)) {
            persistence.when(() -> Project.findByIdOptional(projectId, LockModeType.PESSIMISTIC_WRITE)).thenReturn(Optional.of(project));
            members.when(() -> ProjectMember.isMember(memberId, projectId)).thenReturn(true);
            members.when(() -> ProjectMember.findMemberInProjectOptional(memberId, projectId)).thenReturn(Optional.of(member));
            persistence.when(() -> ProjectMember.count("project.id = ?1 and role = ?2", projectId, ProjectRole.OWNER)).thenReturn(ownerCount);
            assertions.run();
        }
    }
}
