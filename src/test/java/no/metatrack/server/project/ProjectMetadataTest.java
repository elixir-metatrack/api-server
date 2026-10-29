package no.metatrack.server.project;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.BadRequestException;
import no.metatrack.server.auth.CurrentUser;
import no.metatrack.server.auth.UserService;
import no.metatrack.server.auth.keycloak.IdentityLookupService;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProjectMetadataTest {
    private final IdentityLookupService identities = mock(IdentityLookupService.class);
    private final ProjectService service = new ProjectService(identities);
    private final UUID user = UUID.randomUUID();

    ProjectMetadataTest() {
        service.identity = mock(SecurityIdentity.class);
        service.userService = mock(UserService.class);
        when(service.userService.requireCurrentUser())
                .thenReturn(new CurrentUser(user.toString(), "user", Set.of(), null, null, null));
    }

    @Test
    void newProjectsRejectMissingBlankAndOversizedOrganization() {
        for (String organization : new String[]{null, "", " \t", "x".repeat(256)}) {
            assertThrows(BadRequestException.class, () -> service.createProject(
                    new CreateProjectRequest("project", null, organization, null, null), user.toString()));
        }
    }

    @Test
    void updatePersistsMetadataAndAllowsClearingOptionalText() {
        Project project = project();
        withProject(project, () -> {
            service.updateProject(1L, new UpdateProjectRequest(null, null, "Organization", "Detailed", "Notes"));
            assertEquals("Organization", project.dataOwningOrganization);
            assertEquals("Detailed", project.detailedDescription);
            assertEquals("Notes", project.comments);
            assertNotNull(project.modifiedOn);
            service.updateProject(1L, new UpdateProjectRequest(null, null, null, "", ""));
            assertEquals("", project.detailedDescription);
            assertEquals("", project.comments);
            assertEquals("Organization", project.dataOwningOrganization);
        });
    }

    @Test
    void omittedMetadataPreservesLegacyNullAndExistingText() {
        Project project = project();
        withProject(project, () -> service.updateProject(1L, new UpdateProjectRequest(null, null, null, null, null)));
        assertNull(project.dataOwningOrganization);
        assertEquals("Detailed", project.detailedDescription);
        assertEquals("Notes", project.comments);
    }

    @Test
    void organizationCannotBeClearedOrReplacedWithWhitespace() {
        Project project = project();
        project.dataOwningOrganization = "Organization";
        withProject(project, () -> {
            for (String organization : List.of("", "  ", "x".repeat(256))) {
                assertThrows(BadRequestException.class, () -> service.updateProject(1L,
                        new UpdateProjectRequest(null, null, organization, null, null)));
                assertEquals("Organization", project.dataOwningOrganization);
            }
        });
    }

    @Test
    void legacyParentRequiresAnExplicitOrganizationForNewSubProject() {
        withProject(project(), () -> assertThrows(BadRequestException.class,
                () -> service.createSubProject(1L, "child", null, List.of(), user.toString())));
    }

    @Test
    void blankSubProjectOverrideIsNotSilentlyReplacedByParentOrganization() {
        Project parent = project();
        parent.dataOwningOrganization = "Parent organization";
        withProject(parent, () -> assertThrows(BadRequestException.class, () -> service.createSubProject(1L,
                new CreateSubProjectRequest("child", null, List.of(), " ", null, null), user.toString())));
    }

    @Test
    void commentsAreHiddenFromAnonymousReadersAndNonMembers() {
        Project project = project();
        when(service.identity.isAnonymous()).thenReturn(true);
        assertNull(service.toResponse(project).comments());
        verifyNoInteractions(service.userService);
        when(service.identity.isAnonymous()).thenReturn(false);
        assertNull(service.toResponse(project).comments());
    }

    @Test
    void allProjectMembersCanReadCommentsAndOtherMetadata() {
        Project project = project();
        project.dataOwningOrganization = "Organization";
        ProjectMember member = new ProjectMember();
        member.memberId = user;
        member.role = ProjectRole.VIEWER;
        project.projectMembers.add(member);
        ProjectResponse response = service.toResponse(project);
        assertEquals("Organization", response.dataOwningOrganization());
        assertEquals("Detailed", response.detailedDescription());
        assertEquals("Notes", response.comments());
    }

    private Project project() {
        Project project = new Project();
        project.id = 1L;
        project.owner = user;
        project.detailedDescription = "Detailed";
        project.comments = "Notes";
        when(identities.username(user)).thenReturn(Optional.empty());
        return project;
    }

    private void withProject(Project project, Runnable action) {
        try (MockedStatic<PanacheEntityBase> persistence = mockStatic(PanacheEntityBase.class)) {
            persistence.when(() -> Project.findByIdOptional(1L)).thenReturn(Optional.of(project));
            action.run();
        }
    }
}