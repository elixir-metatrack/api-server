package no.metatrack.server.project;

import no.metatrack.server.auth.keycloak.IdentityLookupService;
import no.metatrack.server.sample.Sample;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
    void collectionResolvesDuplicateOwnersTogetherAndAllowsMissingUsers() {
        IdentityLookupService identities = mock(IdentityLookupService.class);
        UUID owner = UUID.randomUUID();
        UUID missingOwner = UUID.randomUUID();
        List<UUID> ownerIds = List.of(owner, owner, missingOwner);
        when(identities.usernames(ownerIds))
                .thenReturn(Map.of(owner, Optional.of("owner@example.org"), missingOwner, Optional.empty()));
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
        verify(identities).usernames(ownerIds);
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