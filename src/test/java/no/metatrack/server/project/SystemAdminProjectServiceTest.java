package no.metatrack.server.project;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import no.metatrack.server.auth.keycloak.IdentityLookupService;
import no.metatrack.server.auth.keycloak.KeycloakIdentity;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SystemAdminProjectServiceTest {
    private static final Long PROJECT_ID = 4L;
    private final EntityManager entityManager = mock(EntityManager.class);
    private final IdentityLookupService identityLookupService = mock(IdentityLookupService.class);
    private final SystemAdminProjectService service =
            new SystemAdminProjectService(entityManager, identityLookupService);

    @Test
    void ownershipTransferPromotesMemberAndDemotesOnlyThePreviousDesignatedOwner() {
        UUID previousOwnerId = UUID.randomUUID();
        UUID replacementId = UUID.randomUUID();
        Project project = new Project();
        project.id = PROJECT_ID;
        project.owner = previousOwnerId;
        ProjectMember previousOwner = member(previousOwnerId, ProjectRole.OWNER, project, 1L);
        ProjectMember replacement = member(replacementId, ProjectRole.ADMIN, project, 2L);
        ProjectMember coOwner = member(UUID.randomUUID(), ProjectRole.OWNER, project, 3L);
        when(entityManager.find(Project.class, PROJECT_ID, LockModeType.PESSIMISTIC_WRITE)).thenReturn(project);
        when(identityLookupService.identity(replacementId))
                .thenReturn(Optional.of(new KeycloakIdentity("replacement", "replacement@example.test")));

        try (MockedStatic<ProjectMember> members = mockStatic(ProjectMember.class)) {
            members.when(() -> ProjectMember.findMemberInProjectOptional(replacementId, PROJECT_ID))
                    .thenReturn(Optional.of(replacement));
            members.when(() -> ProjectMember.findMemberInProjectOptional(previousOwnerId, PROJECT_ID))
                    .thenReturn(Optional.of(previousOwner));

            service.transferOwnership(PROJECT_ID, replacementId, ProjectRole.EDITOR);

            assertEquals(replacementId, project.owner);
            assertEquals(ProjectRole.OWNER, replacement.role);
            assertEquals(ProjectRole.EDITOR, previousOwner.role);
            assertEquals(ProjectRole.OWNER, coOwner.role);
            verify(entityManager, never()).persist(replacement);
        }
    }

    @Test
    void ownershipTransferAddsAReplacementWhoIsNotAlreadyAMember() {
        UUID previousOwnerId = UUID.randomUUID();
        UUID replacementId = UUID.randomUUID();
        Project project = new Project();
        project.id = PROJECT_ID;
        project.owner = previousOwnerId;
        ProjectMember previousOwner = member(previousOwnerId, ProjectRole.OWNER, project, 1L);
        when(entityManager.find(Project.class, PROJECT_ID, LockModeType.PESSIMISTIC_WRITE)).thenReturn(project);
        when(identityLookupService.identity(replacementId))
                .thenReturn(Optional.of(new KeycloakIdentity("replacement", "replacement@example.test")));

        try (MockedStatic<ProjectMember> members = mockStatic(ProjectMember.class)) {
            members.when(() -> ProjectMember.findMemberInProjectOptional(replacementId, PROJECT_ID))
                    .thenReturn(Optional.empty());
            members.when(() -> ProjectMember.findMemberInProjectOptional(previousOwnerId, PROJECT_ID))
                    .thenReturn(Optional.of(previousOwner));

            service.transferOwnership(PROJECT_ID, replacementId, ProjectRole.VIEWER);

            ArgumentCaptor<ProjectMember> addedMember = ArgumentCaptor.forClass(ProjectMember.class);
            verify(entityManager).persist(addedMember.capture());
            assertEquals(replacementId, project.owner);
            assertEquals(replacementId, addedMember.getValue().memberId);
            assertEquals(ProjectRole.OWNER, addedMember.getValue().role);
            assertEquals(ProjectRole.VIEWER, previousOwner.role);
        }
    }

    @Test
    void directAdditionCreatesMembershipWithRequestedNonOwnerRole() {
        UUID memberId = UUID.randomUUID();
        Project project = new Project();
        project.id = PROJECT_ID;
        when(entityManager.find(Project.class, PROJECT_ID, LockModeType.PESSIMISTIC_WRITE)).thenReturn(project);
        when(identityLookupService.identity(memberId))
                .thenReturn(Optional.of(new KeycloakIdentity("member", "member@example.test")));

        try (MockedStatic<ProjectMember> members = mockStatic(ProjectMember.class)) {
            members.when(() -> ProjectMember.isMember(memberId, PROJECT_ID)).thenReturn(false);

            service.addMember(PROJECT_ID, memberId, ProjectRole.EDITOR);

            ArgumentCaptor<ProjectMember> addedMember = ArgumentCaptor.forClass(ProjectMember.class);
            verify(entityManager).persist(addedMember.capture());
            assertEquals(project, addedMember.getValue().project);
            assertEquals(memberId, addedMember.getValue().memberId);
            assertEquals(ProjectRole.EDITOR, addedMember.getValue().role);
        }
    }

    @Test
    void transferRejectsOwnerAsTheFormerOwnersReplacementRole() {
        assertThrows(BadRequestException.class,
                () -> service.transferOwnership(PROJECT_ID, UUID.randomUUID(), ProjectRole.OWNER));
    }

    @Test
    void directAdditionRejectsMissingKeycloakIdentity() {
        UUID memberId = UUID.randomUUID();
        when(identityLookupService.identity(memberId)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> service.addMember(PROJECT_ID, memberId, ProjectRole.ADMIN));
        verify(entityManager, never()).find(Project.class, PROJECT_ID, LockModeType.PESSIMISTIC_WRITE);
    }

    private ProjectMember member(UUID memberId, ProjectRole role, Project project, Long id) {
        ProjectMember member = new ProjectMember();
        member.id = id;
        member.memberId = memberId;
        member.role = role;
        member.project = project;
        return member;
    }
}