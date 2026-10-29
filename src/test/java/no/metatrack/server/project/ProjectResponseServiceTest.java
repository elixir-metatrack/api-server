package no.metatrack.server.project;

import no.metatrack.server.auth.keycloak.IdentityLookupService;
import no.metatrack.server.auth.keycloak.KeycloakIdentityException;
import no.metatrack.server.sample.Sample;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProjectResponseServiceTest {
    @Test
    void singleProjectIncludesOwnerUsernameAndExistingData() {
        IdentityLookupService identities = mock(IdentityLookupService.class);
        UUID owner = UUID.randomUUID();
        when(identities.username(owner)).thenReturn(Optional.of("owner@example.org"));
        Project project = project(1L, owner, "Project one");
        project.samples.add(sample(project));
        project.samples.add(sample(project));

        ProjectResponse response = new ProjectService(identities).toResponse(project);

        assertEquals(project.id, response.id());
        assertEquals(project.name, response.name());
        assertEquals(project.description, response.description());
        assertEquals(owner, response.owner());
        assertEquals("owner@example.org", response.ownerUsername());
        assertEquals(2, response.sampleCount());
        assertEquals(project.createdOn, response.createdOn());
        assertEquals(project.modifiedOn, response.modifiedOn());
    }

    @Test
    void singleProjectReturnsProjectDataWhenIdentityProviderFails() {
        IdentityLookupService identities = mock(IdentityLookupService.class);
        UUID owner = UUID.randomUUID();
        when(identities.username(owner)).thenThrow(new KeycloakIdentityException("provider unavailable"));
        Project project = project(1L, owner, "Project one");

        ProjectResponse response = new ProjectService(identities).toResponse(project);

        assertEquals(project.id, response.id());
        assertEquals(owner, response.owner());
        assertNull(response.ownerUsername());
    }

    @Test
    void collectionResolvesDuplicateOwnersTogetherAndAllowsMissingUsers() {
        IdentityLookupService identities = mock(IdentityLookupService.class);
        UUID owner = UUID.randomUUID();
        UUID missingOwner = UUID.randomUUID();
        when(identities.username(owner)).thenReturn(Optional.of("owner@example.org"));
        when(identities.username(missingOwner)).thenReturn(Optional.empty());
        Project first = project(1L, owner, "First");
        Project second = project(2L, owner, "Second");
        Project missing = project(3L, missingOwner, "Missing");
        first.samples.add(sample(first));

        List<ProjectResponse> responses = new ProjectService(identities).toResponses(List.of(first, second, missing));

        assertEquals("owner@example.org", responses.getFirst().ownerUsername());
        assertEquals("owner@example.org", responses.get(1).ownerUsername());
        assertNull(responses.getLast().ownerUsername());
        assertEquals(1, responses.getFirst().sampleCount());
        assertEquals(0, responses.get(1).sampleCount());
        assertEquals(0, responses.getLast().sampleCount());
        verify(identities).username(owner);
        verify(identities).username(missingOwner);
    }

    @Test
    void collectionKeepsSuccessfulNamesWhenAnotherLookupFailsAndDeduplicatesOwners() {
        IdentityLookupService identities = mock(IdentityLookupService.class);
        UUID unavailableOwner = UUID.randomUUID();
        UUID availableOwner = UUID.randomUUID();
        when(identities.username(unavailableOwner))
                .thenThrow(new KeycloakIdentityException("provider unavailable"));
        when(identities.username(availableOwner)).thenReturn(Optional.of("available@example.org"));
        Project unavailableFirst = project(1L, unavailableOwner, "Unavailable first");
        Project available = project(2L, availableOwner, "Available");
        Project unavailableAgain = project(3L, unavailableOwner, "Unavailable again");

        List<ProjectResponse> responses = new ProjectService(identities)
                .toResponses(List.of(unavailableFirst, available, unavailableAgain));

        assertEquals(unavailableOwner, responses.getFirst().owner());
        assertNull(responses.getFirst().ownerUsername());
        assertEquals("available@example.org", responses.get(1).ownerUsername());
        assertEquals(unavailableOwner, responses.getLast().owner());
        assertNull(responses.getLast().ownerUsername());
        verify(identities).username(unavailableOwner);
        verify(identities).username(availableOwner);
    }

    @Test
    void failedLookupIsRetriedByLaterResponseAndCanRecover() {
        IdentityLookupService identities = mock(IdentityLookupService.class);
        UUID owner = UUID.randomUUID();
        when(identities.username(owner))
                .thenThrow(new KeycloakIdentityException("provider unavailable"))
                .thenReturn(Optional.of("owner@example.org"));
        Project project = project(1L, owner, "Project one");
        ProjectService service = new ProjectService(identities);

        assertNull(service.toResponse(project).ownerUsername());
        assertEquals("owner@example.org", service.toResponse(project).ownerUsername());
        verify(identities, times(2)).username(owner);
    }

    @Test
    void emptyCollectionDoesNotLookUpIdentities() {
        IdentityLookupService identities = mock(IdentityLookupService.class);

        assertEquals(List.of(), new ProjectService(identities).toResponses(List.of()));

        verifyNoInteractions(identities);
    }

    @Test
    void unrelatedIdentityLookupFailuresAreNotHidden() {
        IdentityLookupService identities = mock(IdentityLookupService.class);
        UUID owner = UUID.randomUUID();
        when(identities.username(owner)).thenThrow(new IllegalStateException("unexpected failure"));
        Project project = project(1L, owner, "Project one");

        assertThrows(IllegalStateException.class, () -> new ProjectService(identities).toResponse(project));
    }

    private Project project(long id, UUID owner, String name) {
        Project project = new Project();
        project.id = id;
        project.name = name;
        project.description = name + " description";
        project.owner = owner;
        project.createdOn = Instant.parse("2026-01-01T00:00:00Z");
        project.modifiedOn = Instant.parse("2026-01-02T00:00:00Z");
        return project;
    }

    private Sample sample(Project project) {
        Sample sample = new Sample();
        sample.project = project;
        return sample;
    }
}